package com.hopa.app.backend

import com.hopa.capabilities.backend.BackendCredentials
import com.hopa.capabilities.backend.BackendEnvelope
import com.hopa.capabilities.backend.BackendLink
import com.hopa.capabilities.backend.BackendPairing
import com.hopa.capabilities.backend.BackendState
import com.hopa.capabilities.backend.PairingLink
import com.hopa.data.settings.SettingsRepository
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
 * Owns the app's one gateway link: pair once, connect while HOPA is open, close
 * 30 s after it leaves, and keep a wake's socket alive until its queue drains or
 * the run that arrived finishes.
 *
 * Exactly one [BackendLink] exists at a time, including across a foreground
 * [onForeground], a wake and a refresh, because two sockets could rotate the
 * same token family and revoke it. No token is ever logged.
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

    @Volatile
    private var link: BackendLink? = null
    private var stateJob: Job? = null

    @Volatile
    private var foreground = false
    @Volatile
    private var waking = false
    @Volatile
    private var runs = 0

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

    /**
     * Claims a pairing session and stores the identity, tokens and private key.
     * The claim happens before any write, so a refusal leaves the old identity.
     */
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
        scope.launch { connect() }
    }

    /** The activity left: close after 30 s unless a run is still in flight. */
    fun onBackground() {
        foreground = false
        if (runs > 0) {
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
        gate.withLock { closeLink() }
    }

    /**
     * Opens the link for a wake and arms the idle stop. The caller's scope may be
     * short lived: the link itself lives on the session's scope so a run that
     * outlasts the messaging service is not cut off.
     */
    fun wake(scope: CoroutineScope) {
        window.cancel()
        if (!foreground) {
            waking = true
            scope.launch { connect() }
            if (link?.state?.value is BackendState.Live) {
                armIdleStop()
            }
        }
    }

    /** A run began: the wake window must not close under it. */
    fun taskStarted() {
        runs += 1
        window.cancel()
        _task.value = _task.value.copy(state = "running")
    }

    /** A run ended: re-arm whichever stop the current life cycle asks for. */
    fun taskFinished() {
        runs = (runs - 1).coerceAtLeast(0)
        _task.value = _task.value.copy(state = "finished")
        if (runs > 0 || foreground) {
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
        stateJob = scope.launch { created.state.collect { publish(it) } }
        created.start(scope)
        publish(created.state.value)
    }

    private fun publish(next: BackendState) {
        _state.value = next
        if (!waking || foreground || runs > 0) {
            return
        }
        if (next is BackendState.Live) {
            armIdleStop()
        }
    }

    private fun armIdleStop() {
        window.arm(WAKE_IDLE_MS) { waking && !foreground && runs == 0 }
    }

    private fun scheduleStop() {
        window.arm(BACKGROUND_GRACE_MS) { !foreground && runs == 0 }
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
