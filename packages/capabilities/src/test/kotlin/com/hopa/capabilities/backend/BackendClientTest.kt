package com.hopa.capabilities.backend

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendClientTest {
    @Test
    fun `hello is answered and the client goes live`() = runBlocking {
        val server = MockWebServer()
        val seen = CompletableDeferred<JsonObject>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runBlocking { seen.complete(Json.decodeFromString<JsonObject>(text)) }
                webSocket.send(
                    """{"v":1,"id":"a1","type":"device.hello.ack","ts":1,"to":"dev-1",
                    "body":{"session":"s","serverTime":1,"heartbeatSec":25,"queued":2,"peers":["dev-2"]}}""".trimIndent()
                )
            }
        }))
        val client = clientFor(server)
        client.start(this)
        withTimeout(5_000) { client.state.first { it is BackendState.Live } }
        assertEquals("device.hello", seen.await()["type"]!!.jsonPrimitive.content)
        assertEquals("dev-1", (client.state.value as BackendState.Live).deviceId)
        assertEquals(2, (client.state.value as BackendState.Live).queued)
        client.stop()
        server.shutdown()
    }

    @Test
    fun `the access token travels in the authorization header, never the URL`() = runBlocking {
        val server = MockWebServer()
        val handshake = CompletableDeferred<Request>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                handshake.complete(webSocket.request())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send(helloAck())
            }
        }))
        val client = clientFor(server)
        client.start(this)
        withTimeout(5_000) { client.state.first { it is BackendState.Live } }
        val request = withTimeout(5_000) { handshake.await() }
        assertEquals("Bearer a1", request.header("Authorization"))
        assertNull(request.url.queryParameter("access_token"))
        client.stop()
        server.shutdown()
    }

    @Test
    fun `a malformed frame does not close the socket`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send("not json")
                webSocket.send(helloAck())
            }
        }))
        val client = clientFor(server)
        client.start(this)
        withTimeout(5_000) { client.state.first { it is BackendState.Live } }
        assertEquals(2, (client.state.value as BackendState.Live).queued)
        client.stop()
        server.shutdown()
    }

    @Test
    fun `a frame that arrives after stop cannot go live again`() = runBlocking {
        val server = MockWebServer()
        val peer = CompletableDeferred<WebSocket>()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                peer.complete(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send(helloAck())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                // Withhold the close reply so the peer stays readable: a frame
                // sent below must reach the client after stop() has completed.
            }
        }))
        val client = clientFor(server)
        client.start(this)
        withTimeout(5_000) { client.state.first { it is BackendState.Live } }
        client.stop()
        assertEquals(BackendState.Disconnected, client.state.value)

        val gateway = withTimeout(5_000) { peer.await() }
        assertTrue(gateway.send(lateHelloAck()))
        delay(500) // a localhost frame is read in microseconds; this is ample
        assertEquals(BackendState.Disconnected, client.state.value)

        gateway.close(1000, "done")
        server.shutdown()
    }

    private open class FakeGateway : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun clientFor(
        server: MockWebServer,
        refresh: suspend () -> BackendCredentials? = { null },
    ): BackendClient = BackendClient(
        credentials = { credentials(server) },
        pushToken = { "tok" },
        refresh = refresh,
        deviceName = "Xiaomi 15T Pro",
        appVersion = "0.1.0",
        http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
    )

    private fun credentials(server: MockWebServer): BackendCredentials = BackendCredentials(
        deviceId = "dev-1",
        householdId = "h1",
        accessToken = "a1",
        refreshToken = "r1",
        expiresAt = "2026-11-16T00:00:00Z",
        gatewayUrl = server.url("/v1/gateway").toString().replace("http://", "ws://"),
    )

    private fun helloAck(): String =
        """{"v":1,"id":"a1","type":"device.hello.ack","ts":1,"to":"dev-1",
        "body":{"session":"s","serverTime":1,"heartbeatSec":25,"queued":2,"peers":["dev-2"]}}""".trimIndent()

    private fun lateHelloAck(): String =
        """{"v":1,"id":"a2","type":"device.hello.ack","ts":2,"to":"dev-9",
        "body":{"session":"s2","serverTime":2,"heartbeatSec":25,"queued":9,"peers":[]}}""".trimIndent()
}
