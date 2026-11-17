package com.hopa.capabilities.backend

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket

/**
 * Holds one `/v1/gateway` WebSocket: bearer token in the handshake header,
 * `device.hello` as the first frame, `Live` once the ack arrives. A frame that
 * cannot be decoded is ignored so a malformed message never costs the socket.
 * A `task.create` is handed to [onTask] on the session's coroutine scope, so a
 * slow handler neither stalls the reader nor dies when the socket reconnects.
 *
 * A `1008 E_AUTH` close refreshes the credentials once; a null refresh asks for
 * pairing instead of retrying, because a rotated token may never be presented
 * twice. A refresh that is only unreachable keeps the stored tokens and the
 * loop retries with backoff. Any other close retries after 1/2/4/8 s capped at
 * 30 s. Nothing here logs a token or a frame body.
 */
class BackendClient(
    private val credentials: () -> BackendCredentials?,
    private val pushToken: () -> String?,
    private val refresh: suspend () -> BackendCredentials?,
    private val deviceName: String,
    private val appVersion: String,
    private val http: OkHttpClient = defaultHttp(),
    private val handshakeTimeoutMs: Long = HELLO_ACK_TIMEOUT_MS,
) : BackendLink {
    private val _state = MutableStateFlow<BackendState>(BackendState.Disconnected)
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    @Volatile
    override var onTask: (suspend (BackendEnvelope) -> Unit)? = null

    private var job: Job? = null

    /** The connection attempt, not the socket field, owns the hello handshake. */
    @Volatile
    private var connection: BackendListener? = null

    /** Deliveries outlive one socket: a close must not kill a running task. */
    @Volatile
    private var session: CoroutineScope? = null

    @Volatile
    private var socket: WebSocket? = null
    @Volatile
    private var deviceId: String = ""

    /** One token per `pong`, buffered so a fast answer is never lost. */
    @Volatile
    private var pongs: Channel<Unit> = Channel(Channel.CONFLATED)

    override fun start(scope: CoroutineScope) {
        job?.cancel()
        session?.cancel()
        connection?.deactivate()
        socket?.close(1000, "restarting")
        socket = null
        val live = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
        session = live
        job = live.launch {
            var current = credentials() ?: run {
                _state.value = BackendState.Failed("the phone is not paired", needsPairing = true)
                return@launch
            }
            var attempt = 0
            while (isActive) {
                deviceId = current.deviceId
                _state.value = BackendState.Connecting
                pongs = Channel(Channel.CONFLATED)
                val conn = listener(live)
                connection = conn
                socket = http.newWebSocket(helloRequest(current), conn)
                val close = conn.closed.await()
                conn.deactivate()
                if (!isActive) return@launch
                val wasLive = _state.value is BackendState.Live
                _state.value = BackendState.Failed(close.message, needsPairing = false)
                if (close.auth) {
                    // One refresh per auth close: a rotated token presented
                    // twice revokes the device's whole token family.
                    current = refreshed() ?: run {
                        _state.value = BackendState.Failed("the phone must be paired again", needsPairing = true)
                        return@launch
                    }
                    attempt = 0
                }
                if (wasLive || close.auth) attempt = 0
                delay(backoffMs(attempt++))
            }
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        connection?.deactivate()
        connection = null
        socket?.close(1000, "bye")
        socket = null
        session?.cancel()
        session = null
        _state.value = BackendState.Disconnected
    }

    /** Tells the sender its task arrived. The server relays it under the task. */
    override suspend fun ack(task: BackendEnvelope) {
        send(
            BackendEnvelope(
                id = UUID.randomUUID().toString(), type = "task.ack", ts = System.currentTimeMillis(),
                trace = task.trace, from = deviceId.ifBlank { null },
                to = requireNotNull(task.from) { "a delivered task must name its sender" },
                task = requireNotNull(task.task) { "a delivered task must carry its id" },
                body = buildJsonObject { put("state", "delivered") },
            )
        )
    }

    /** Application-level liveness check: sends `ping`, waits up to 10 s for `pong`. */
    override suspend fun ping() {
        send(BackendEnvelope(id = UUID.randomUUID().toString(), type = "ping", ts = System.currentTimeMillis()))
        withTimeout(PING_TIMEOUT_MS) { pongs.receive() }
    }

    /** Replies use the socket that is open now, not the one the task arrived on. */
    override suspend fun send(frame: BackendEnvelope) {
        val open = socket ?: throw IllegalStateException("the gateway link is not open")
        check(open.send(BackendCodec.encode(frame))) { "the gateway link closed before the frame was sent" }
    }

    private fun helloRequest(current: BackendCredentials): Request = Request.Builder()
        .url(current.gatewayUrl).header("Authorization", "Bearer ${current.accessToken}").build()

    private fun listener(scope: CoroutineScope): BackendListener = BackendListener(
        deviceName = deviceName,
        appVersion = appVersion,
        pushToken = pushToken,
        session = scope,
        handshakeTimeoutMs = handshakeTimeoutMs,
        onFrame = ::handleFrame,
    )

    private fun handleFrame(frame: BackendEnvelope, webSocket: WebSocket) {
        when (frame.type) {
            "device.hello.ack" -> _state.value = BackendState.Live(
                deviceId = frame.to?.takeIf { it.isNotBlank() } ?: deviceId,
                queued = frame.body["queued"]?.jsonPrimitive?.intOrNull ?: 0,
                peers = peersOf(frame),
            )
            "pong" -> pongs.trySend(Unit)
            "ping" -> answerPing(webSocket, frame)
            "task.create" -> dispatch(frame)
        }
    }

    /** A refresh that throws counts as no refresh: retrying a rotated token is worse. */
    private suspend fun refreshed(): BackendCredentials? = try {
        refresh()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (unreachable: BackendPairingFailure.Unavailable) {
        // The token was not refused, only the network failed: keep the stored
        // pair and let the loop try again after the backoff.
        credentials()
    } catch (error: Exception) {
        null
    }

    private fun peersOf(frame: BackendEnvelope): List<String> =
        (frame.body["peers"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()

    /**
     * Answers a heartbeat `ping` on the socket it arrived on, not through
     * [send]: a concurrent close may already have nulled [socket].
     */
    private fun answerPing(webSocket: WebSocket, ping: BackendEnvelope) {
        val pong = BackendEnvelope(
            id = UUID.randomUUID().toString(), type = "pong", ts = System.currentTimeMillis(),
            trace = ping.trace, to = ping.from,
        )
        webSocket.send(BackendCodec.encode(pong))
    }

    /**
     * Runs the handler on the session's scope, so a reconnect does not cancel a
     * task that already started. A failed handler is the handler's problem.
     */
    private fun dispatch(frame: BackendEnvelope) {
        val handler = onTask ?: return
        val live = session ?: return
        live.launch {
            try {
                handler(frame)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A failed delivery must not close the link or kill the handler scope.
            }
        }
    }

    companion object {
        /** The contract's liveness answer is local; 10 s is generous. */
        private const val PING_TIMEOUT_MS = 10_000L
        private const val MAX_BACKOFF_MS = 30_000L

        /** A peer that never acks the hello is dropped so the loop reconnects. */
        const val HELLO_ACK_TIMEOUT_MS = 15_000L

        /** 1/2/4/8 s doubling, capped at 30 s. */
        internal fun backoffMs(attempt: Int): Long =
            minOf(1_000L shl attempt.coerceIn(0, 5), MAX_BACKOFF_MS)

        /** 15 s to connect, no read timeout: the gateway's pings keep it alive. */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
