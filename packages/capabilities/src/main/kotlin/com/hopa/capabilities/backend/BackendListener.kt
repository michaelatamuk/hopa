package com.hopa.capabilities.backend

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One socket's listener, with its own liveness flag so the handshake never
 * depends on the client's shared `socket` field being stored first: OkHttp can
 * deliver `onOpen` before the field assignment is visible.
 *
 * After the hello is sent a short timer waits for `device.hello.ack`. A peer
 * that answers nothing is closed and [closed] completes, so the client's backoff
 * loop reconnects instead of waiting forever on a read timeout of zero.
 */
internal class BackendListener(
    private val deviceName: String,
    private val appVersion: String,
    private val pushToken: () -> String?,
    private val session: CoroutineScope,
    private val handshakeTimeoutMs: Long,
    private val onFrame: (BackendEnvelope, WebSocket) -> Unit,
) : WebSocketListener() {
    private val active = AtomicBoolean(true)
    @Volatile
    private var handshake: Job? = null
    @Volatile
    private var acked = false

    val closed = CompletableDeferred<BackendClose>()

    /** A newer connection (or a stop) owns the link now; ignore this one. */
    fun deactivate() {
        active.set(false)
        handshake?.cancel()
    }

    override fun onOpen(webSocket: WebSocket, response: Response) {
        if (!active.get()) return
        val hello = BackendCodec.encode(BackendCodec.hello(deviceName, appVersion, pushToken()))
        if (!webSocket.send(hello)) {
            finish(BackendClose(0, "", "the gateway is unreachable"))
            return
        }
        handshake = session.launch {
            delay(handshakeTimeoutMs)
            if (active.get() && !acked) {
                webSocket.close(CLOSE_HELLO, "hello timeout")
                finish(BackendClose(0, "", HELLO_TIMEOUT))
            }
        }
    }

    override fun onMessage(webSocket: WebSocket, text: String) {
        if (!active.get()) return
        val frame = BackendCodec.decodeOrNull(text) ?: return
        if (frame.type == HELLO_ACK) {
            acked = true
            handshake?.cancel()
        }
        try {
            onFrame(frame, webSocket)
        } catch (error: Exception) {
            // A frame we cannot read is ignored; the socket stays open.
        }
    }

    // `onClosing` is the first event of a server close and already carries
    // its code; `onClosed` may wait forever on a peer's close handshake.
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) =
        finish(BackendClose(code, reason, "the gateway closed the link"))

    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
        finish(BackendClose(code, reason, "the gateway closed the link"))

    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
        finish(BackendClose(0, "", "the gateway is unreachable"))

    private fun finish(close: BackendClose) {
        if (!active.compareAndSet(true, false)) return
        handshake?.cancel()
        closed.complete(close)
    }

    private companion object {
        const val HELLO_ACK = "device.hello.ack"
        const val CLOSE_HELLO = 4000
        const val HELLO_TIMEOUT = "the gateway did not answer the hello"
    }
}
