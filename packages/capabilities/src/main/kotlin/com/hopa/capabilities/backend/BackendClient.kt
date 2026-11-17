package com.hopa.capabilities.backend

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Holds one `/v1/gateway` WebSocket: bearer token in the handshake header,
 * `device.hello` as the first frame, `Live` once the ack arrives. A frame that
 * cannot be decoded is ignored so a malformed message never costs the socket.
 * A `task.create` is handed to [onTask] on its own coroutine, so a slow handler
 * never stalls the reader; other types never reach it.
 *
 * Nothing here logs or prints a token, and no frame body is ever logged. A
 * failure names the cause, not the credentials. Reconnect, backoff and the auth
 * refresh belong to a later task.
 */
class BackendClient(
    private val credentials: () -> BackendCredentials?,
    private val pushToken: () -> String?,
    private val refresh: suspend () -> BackendCredentials?,
    private val deviceName: String,
    private val appVersion: String,
    private val http: OkHttpClient = defaultHttp(),
) : BackendLink {
    private val _state = MutableStateFlow<BackendState>(BackendState.Disconnected)
    override val state: StateFlow<BackendState> = _state.asStateFlow()

    @Volatile
    override var onTask: (suspend (BackendEnvelope) -> Unit)? = null

    private var job: Job? = null

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var deviceId: String = ""

    /** Where delivered tasks run, one coroutine each, so the reader moves on. */
    @Volatile
    private var deliveries: CoroutineScope? = null

    /** One token per `pong`, buffered so a fast answer is never lost. */
    @Volatile
    private var pongs: Channel<Unit> = Channel(Channel.CONFLATED)

    override fun start(scope: CoroutineScope) {
        job?.cancel()
        socket?.close(1000, "restarting")
        socket = null
        job = scope.launch {
            val current = credentials()
            if (current == null) {
                _state.value = BackendState.Failed("the phone is not paired", needsPairing = true)
                return@launch
            }
            deviceId = current.deviceId
            _state.value = BackendState.Connecting
            pongs = Channel(Channel.CONFLATED)
            val closed = CompletableDeferred<Unit>()
            val handlers = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            deliveries = handlers
            socket = http.newWebSocket(helloRequest(current), listener(closed))
            closed.await()
            handlers.cancel()
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, "bye")
        socket = null
        deliveries?.cancel()
        deliveries = null
        _state.value = BackendState.Disconnected
    }

    /** Tells the sender its task arrived. The server relays it under the task. */
    override suspend fun ack(task: BackendEnvelope) {
        send(
            BackendEnvelope(
                id = UUID.randomUUID().toString(),
                type = "task.ack",
                ts = System.currentTimeMillis(),
                trace = task.trace,
                from = deviceId.ifBlank { null },
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

    override suspend fun send(frame: BackendEnvelope) {
        val open = socket ?: throw IllegalStateException("the gateway link is not open")
        if (!open.send(BackendCodec.encode(frame))) {
            throw IllegalStateException("the gateway link closed before the frame was sent")
        }
    }

    private fun helloRequest(current: BackendCredentials): Request = Request.Builder()
        .url(current.gatewayUrl)
        .header("Authorization", "Bearer ${current.accessToken}")
        .build()

    private fun listener(closed: CompletableDeferred<Unit>) = object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket !== webSocket) return
            webSocket.send(BackendCodec.encode(BackendCodec.hello(deviceName, appVersion, pushToken())))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            val frame = BackendCodec.decodeOrNull(text) ?: return
            try {
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
            } catch (error: Exception) {
                // A frame we cannot read is ignored; the socket stays open.
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (socket === webSocket) {
                socket = null
                if (_state.value !is BackendState.Failed) _state.value = BackendState.Disconnected
            }
            closed.complete(Unit)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            if (socket === webSocket) {
                socket = null
                if (_state.value !is BackendState.Disconnected) {
                    _state.value = BackendState.Failed("the gateway is unreachable", needsPairing = false)
                }
            }
            closed.complete(Unit)
        }
    }

    private fun peersOf(frame: BackendEnvelope): List<String> =
        (frame.body["peers"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .orEmpty()

    /**
     * Answers a heartbeat `ping` on the socket it arrived on, not through
     * [send]: a concurrent close may already have nulled [socket], and a pong
     * the reader can still write on that socket is what keeps the phone alive.
     */
    private fun answerPing(webSocket: WebSocket, ping: BackendEnvelope) {
        webSocket.send(
            BackendCodec.encode(
                BackendEnvelope(
                    id = UUID.randomUUID().toString(),
                    type = "pong",
                    ts = System.currentTimeMillis(),
                    trace = ping.trace,
                    to = ping.from,
                )
            )
        )
    }

    /**
     * Runs the handler off the reader thread. Only `task.create` reaches here,
     * and a handler that fails or is cancelled is the handler's problem, not
     * the socket's.
     */
    private fun dispatch(frame: BackendEnvelope) {
        val handler = onTask ?: return
        val scope = deliveries ?: return
        scope.launch {
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

        /** 15 s to connect, no read timeout: the gateway's pings keep it alive. */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
