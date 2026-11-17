package com.hopa.capabilities.backend

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class BackendReconnectTest {
    @Test
    fun `an E_AUTH close refreshes once and the second one asks for pairing`() = runBlocking {
        var refreshes = 0
        val tokens = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        repeat(2) { enqueueAuthClose(server, tokens) }
        val client = clientFor(
            server,
            refresh = {
                refreshes++
                if (refreshes == 1) credentials(server, accessToken = "a2") else null
            },
        )
        client.start(this)
        withTimeout(5_000) { client.state.first { it is BackendState.Failed && it.needsPairing } }
        assertEquals(2, refreshes)
        // The refreshed token, not the old one, went out on the second handshake.
        assertEquals(listOf("Bearer a1", "Bearer a2"), tokens.toList())
        client.stop()
        server.shutdown()
    }

    @Test
    fun `a close that is not an auth close reconnects without refreshing`() = runBlocking {
        val refreshes = AtomicInteger()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.close(1000, "bye")
            }
        }))
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send(helloAck())
            }
        }))
        val client = clientFor(server, refresh = { refreshes.incrementAndGet(); null })
        client.start(this)
        withTimeout(5_000) { client.state.first { it is BackendState.Live } }
        assertEquals(0, refreshes.get())
        client.stop()
        server.shutdown()
    }

    @Test
    fun `an auth close whose refresh throws also asks for pairing`() = runBlocking {
        val tokens = CopyOnWriteArrayList<String>()
        val server = MockWebServer()
        enqueueAuthClose(server, tokens)
        val client = clientFor(server, refresh = { throw IllegalStateException("refresh refused") })
        client.start(this)
        withTimeout(5_000) { client.state.first { it is BackendState.Failed && it.needsPairing } }
        client.stop()
        server.shutdown()
    }

    @Test
    fun `backoff doubles from one second and caps at thirty`() {
        assertEquals(
            listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map { BackendClient.backoffMs(it) },
        )
    }

    private open class FakeGateway : WebSocketListener() {
        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }
    }

    private fun enqueueAuthClose(server: MockWebServer, tokens: MutableList<String>) {
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                tokens += response.request.header("Authorization").orEmpty()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.close(1008, "E_AUTH")
            }
        }))
    }

    private fun clientFor(
        server: MockWebServer,
        refresh: suspend () -> BackendCredentials?,
    ): BackendClient = BackendClient(
        credentials = { credentials(server) },
        pushToken = { "tok" },
        refresh = refresh,
        deviceName = "Xiaomi 15T Pro",
        appVersion = "0.1.0",
        http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build(),
    )

    private fun credentials(server: MockWebServer, accessToken: String = "a1"): BackendCredentials =
        BackendCredentials(
            deviceId = "dev-1",
            householdId = "h1",
            accessToken = accessToken,
            refreshToken = "r1",
            expiresAt = "2026-11-16T00:00:00Z",
            gatewayUrl = server.url("/v1/gateway").toString().replace("http://", "ws://"),
        )

    private fun helloAck(): String =
        """{"v":1,"id":"a1","type":"device.hello.ack","ts":1,"to":"dev-1",
        "body":{"session":"s","serverTime":1,"heartbeatSec":25,"queued":2,"peers":["dev-2"]}}""".trimIndent()
}
