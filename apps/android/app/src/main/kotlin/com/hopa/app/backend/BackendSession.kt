package com.hopa.app.backend

import com.hopa.capabilities.backend.BackendCredentials
import com.hopa.capabilities.backend.BackendEnvelope
import com.hopa.capabilities.backend.BackendLink
import com.hopa.capabilities.backend.BackendPairing
import com.hopa.capabilities.backend.BackendState
import com.hopa.capabilities.backend.PairingLink
import com.hopa.data.settings.SettingsRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Owns the app's one gateway link: pair once, connect while open, close 30 s
 * after HOPA leaves, and hold a wake's socket until its queue drains or the run
 * ends. One [BackendLink] exists at a time across foreground [onForeground], a
 * wake and a refresh: two sockets could rotate the same token family. The push
 * token is asked for on start too, because a fresh install has none until
 * something requests one. No token is ever logged.
 */
class BackendSession(
    private val settings: SettingsRepository,
    pairingFactory: (String) -> BackendPairing,
    private val linkFactory: (
        credentials: BackendCredentials,
        deviceName: String,
        appVersion: String,
        pushToken: () -> String?,
        refresh: suspend () -> BackendCredentials?,
    ) -> BackendLink,
    private val deviceName: String,
    private val appVersion: String,
    private val pushTokenFetch: suspend () -> Boolean = { false },
    private val backgroundGraceMs: Long = BACKGROUND_GRACE_MS,
    private val wakeIdleMs: Long = WAKE_IDLE_MS,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Mutex()
    private val identity = BackendIdentity(settings, pairingFactory)
    private val window = LinkWindow(scope, gate) { closeLink() }

    private val _state = MutableStateFlow<BackendState>(BackendState.Disconnected)
    val state: StateFlow<BackendState> = _state.asStateFlow()

    private val _task = MutableStateFlow(BackendTaskState())
    val task: StateFlow<BackendTaskState> = _task.asStateFlow()

    @Volatile
    var onTask: (suspend (BackendEnvelope) -> Unit)? = null

    /** The live socket, for the runner that answers a delivery on it. */
    fun currentLink(): BackendLink? = link
    @Volatile
    private var link: BackendLink? = null
    private var stateJob: Job? = null

    @Volatile
    private var foreground = false
    @Volatile
    private var waking = false
    @Volatile
    private var disconnectPending = false

    /** Deliveries run on their own coroutines, so the count must not be lost. */
    private val runs = AtomicInteger()

    private val refresh: LinkRefresh =
        LinkRefresh(scope, gate, runs::get, { foreground || waking }, ::closeLink, ::connect)

    @Volatile
    private var knownDeviceId = ""
    @Volatile
    private var knownGatewayUrl = ""

    /** The identity last stored, readable without a suspend call. */
    val deviceId: String get() = knownDeviceId
    val gatewayUrl: String get() = knownGatewayUrl

    init {
        scope.launch {
            settings.settings.collect { current ->
                knownDeviceId = current.backendDeviceId
                knownGatewayUrl = current.backendUrl
            }
        }
    }

    /** Claims a pairing session and stores it; a refusal leaves the old identity. */
    suspend fun pair(link: PairingLink): Result<Unit> = try {
        val credentials = identity.claim(link, deviceName, FirebaseToken.current())
        knownDeviceId = credentials.deviceId
        knownGatewayUrl = credentials.gatewayUrl
        disconnect()
        if (foreground) {
            connect()
        }
        Result.success(Unit)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(error)
    }

    /** The activity is visible: keep presence open until [onBackground]. */
    fun onForeground() {
        foreground = true
        waking = false
        window.cancel()
        // Process death drops the in-memory token, so a cold start asks FCM
        // again while the link opens.
        scope.launch { loadPushToken() }
        scope.launch { connect() }
    }

    /**
     * Asks FCM for the registration token when none is held, so the next hello
     * carries it. A fresh install has no token until someone asks; a live link
     * says hello again once it lands, and otherwise the next connect reads it.
     */
    suspend fun loadPushToken() {
        if (FirebaseToken.current() != null || !pushTokenFetch()) {
            return
        }
        if (link?.state?.value is BackendState.Live) {
            reconnect()
        }
    }

    /** The activity left: close after 30 s unless a run is still in flight. */
    fun onBackground() {
        foreground = false
        if (runs.get() > 0) {
            return
        }
        if (waking) {
            armIdleStop()
        } else {
            scheduleStop()
        }
    }

    /** Forget the socket; the stored identity and tokens stay for the next run. */
    suspend fun disconnect() {
        waking = false
        window.cancel()
        // A run in flight owns the socket and its claim. Closing here would
        // strand the claim and lose the terminal frame, so wait for taskFinished.
        if (runs.get() > 0) {
            disconnectPending = true
            return
        }
        gate.withLock { closeLink() }
    }

    /** A new push token arrived: refresh the hello when a socket is safe. */
    fun reconnect() = refresh.request()

    /**
     * Opens the link for a wake and arms the idle stop. The caller's scope may be
     * short lived: the link lives on the session's scope so a run outlasts it.
     */
    fun wake(scope: CoroutineScope) {
        window.cancel()
        if (!foreground) {
            waking = true
            scope.launch { connect() }
            val live = link?.state?.value as? BackendState.Live
            if (live != null && live.queued == 0) {
                armIdleStop()
            }
        }
    }

    /** A run began: the wake window must not close under it. */
    fun taskStarted() {
        runs.incrementAndGet()
        window.cancel()
        _task.value = _task.value.copy(state = "running")
    }

    /** A run ended: re-arm whichever stop the current life cycle asks for. */
    fun taskFinished() {
        val active = runs.updateAndGet { current -> (current - 1).coerceAtLeast(0) }
        _task.value = _task.value.copy(state = "finished")
        if (active == 0 && disconnectPending) {
            disconnectPending = false
            scope.launch { disconnect() }
            return
        }
        // A token from the run is applied now only when visible; otherwise the
        // next connect carries it.
        if (refresh.onRunFinished(foreground) || active > 0 || foreground) {
            return
        }
        if (waking) {
            armIdleStop()
        } else {
            scheduleStop()
        }
    }

    private suspend fun connect() = gate.withLock {
        if (!foreground && !waking) {
            return@withLock
        }
        if (link != null) {
            return@withLock
        }
        val credentials = identity.stored() ?: run {
            _state.value = BackendState.Failed("the phone is not paired", needsPairing = true)
            return@withLock
        }
        val created = linkFactory(
            credentials,
            deviceName,
            appVersion,
            { FirebaseToken.current() },
        ) {
            identity.refresh()
        }
        created.onTask = { envelope ->
            _task.value = BackendTaskState(
                taskId = envelope.task.orEmpty(),
                goal = envelope.body["goal"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                state = "running",
            )
            onTask?.invoke(envelope)
        }
        link = created
        refresh.onConnected()
        stateJob = scope.launch { created.state.collect { publish(it) } }
        created.start(scope)
        publish(created.state.value)
    }

    private fun publish(next: BackendState) {
        _state.value = next
        if (!waking || foreground || runs.get() > 0) {
            return
        }
        // Queued is the only signal; Live does not re-emit, so hold until taskFinished().
        if (next is BackendState.Live && next.queued == 0) {
            armIdleStop()
        }
    }

    private fun armIdleStop() {
        window.arm(wakeIdleMs) { waking && !foreground && runs.get() == 0 }
    }

    private fun scheduleStop() {
        window.arm(backgroundGraceMs) { !foreground && runs.get() == 0 }
    }

    /** Caller holds [gate]. */
    private suspend fun closeLink() {
        val current = link
        link = null
        stateJob?.cancel()
        stateJob = null
        current?.stop()
        _state.value = BackendState.Disconnected
    }

    private companion object {
        const val BACKGROUND_GRACE_MS = 30_000L
        const val WAKE_IDLE_MS = 45_000L
    }
}
