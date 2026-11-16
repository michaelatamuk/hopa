package com.hopa.capabilities.backend

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Holds one `/v1/gateway` WebSocket: bearer token in the handshake header,
 * `device.hello` as the first frame, `Live` once the ack arrives. A frame that
 * cannot be decoded is ignored so a malformed message never costs the socket.
 *
 * Nothing here logs or prints a token; failures name the cause, not the
 * credentials. Reconnect, backoff and the auth refresh belong to a later task.
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

    override var onTask: (suspend (BackendEnvelope) -> Unit)? = null

    private var job: Job? = null

    @Volatile
    private var socket: WebSocket? = null

    @Volatile
    private var deviceId: String = ""

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
            val closed = CompletableDeferred<Unit>()
            socket = http.newWebSocket(helloRequest(current), listener(closed))
            closed.await()
        }
    }

    override suspend fun stop() {
        job?.cancel()
        job = null
        socket?.close(1000, "bye")
        socket = null
        _state.value = BackendState.Disconnected
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
            webSocket.send(BackendCodec.encode(BackendCodec.hello(deviceName, appVersion, pushToken())))
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val frame = BackendCodec.decodeOrNull(text) ?: return
            try {
                when (frame.type) {
                    "device.hello.ack" -> _state.value = BackendState.Live(
                        deviceId = frame.to?.takeIf { it.isNotBlank() } ?: deviceId,
                        queued = frame.body["queued"]?.jsonPrimitive?.intOrNull ?: 0,
                        peers = peersOf(frame),
                    )
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

    companion object {
        /** 15 s to connect, no read timeout: the gateway's pings keep it alive. */
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }
}
