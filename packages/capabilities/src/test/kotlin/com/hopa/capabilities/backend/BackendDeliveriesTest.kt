package com.hopa.capabilities.backend

import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendDeliveriesTest {
    @Test
    fun `a delivered task is handed to onTask and acked under its trace`() = runBlocking {
        val received = CompletableDeferred<BackendEnvelope>()
        val acked = CompletableDeferred<JsonObject>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(helloAck(id = "a1", queued = 1))
                webSocket.send(delivery())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.decodeFromString<JsonObject>(text)
                if (frame["type"]!!.jsonPrimitive.content == "task.ack") runBlocking { acked.complete(frame) }
            }
        }))
        val client = clientFor(server)
        client.onTask = { task -> received.complete(task); client.ack(task) }
        client.start(this)
        val frame = withTimeout(5_000) { received.await() }
        assertEquals("open settings", frame.body["goal"]!!.jsonPrimitive.content)
        val ack = withTimeout(5_000) { acked.await() }
        assertEquals("task.ack", ack["type"]!!.jsonPrimitive.content)
        assertEquals("task-1", ack["task"]!!.jsonPrimitive.content)
        assertEquals("dev-9", ack["to"]!!.jsonPrimitive.content)
        assertEquals(TRACE, ack["trace"]!!.jsonPrimitive.content)
        assertEquals("delivered", ack["body"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        client.stop()
        server.shutdown()
    }

    @Test
    fun `a caller holding only a BackendLink can ack a delivery`() = runBlocking {
        val acked = CompletableDeferred<JsonObject>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(helloAck(id = "a1", queued = 1))
                webSocket.send(delivery())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.decodeFromString<JsonObject>(text)
                if (frame["type"]!!.jsonPrimitive.content == "task.ack") runBlocking { acked.complete(frame) }
            }
        }))
        // The static type is the interface on purpose: Task 7's session and
        // Task 9's runner hold a BackendLink and must ack without a cast.
        val link: BackendLink = clientFor(server)
        link.onTask = { task -> link.ack(task) }
        link.start(this)
        val ack = withTimeout(5_000) { acked.await() }
        assertEquals("task-1", ack["task"]!!.jsonPrimitive.content)
        assertEquals("dev-9", ack["to"]!!.jsonPrimitive.content)
        assertEquals(TRACE, ack["trace"]!!.jsonPrimitive.content)
        link.stop()
        server.shutdown()
    }

    @Test
    fun `a frame that is not a task create is never handed to onTask`() = runBlocking {
        val calls = AtomicInteger()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send(
                    """{"v":1,"id":"m1","type":"task.needs_approval","ts":2,"from":"dev-9","to":"dev-1",
                    "task":"task-1","body":{"goal":"open settings"}}""".trimIndent()
                )
                webSocket.send(
                    """{"v":1,"id":"m2","type":"task.ack","ts":3,"from":"dev-1","to":"dev-9",
                    "task":"task-2","body":{"state":"delivered"}}""".trimIndent()
                )
                webSocket.send(
                    """{"v":1,"id":"m3","type":"task.result","ts":4,"from":"dev-1","to":"dev-9",
                    "task":"task-3","body":{"summary":"done"}}""".trimIndent()
                )
                webSocket.send(helloAck(id = "a1", queued = 1))
            }
        }))
        val client = clientFor(server)
        client.onTask = { calls.incrementAndGet() }
        client.start(this)
        withTimeout(5_000) { client.state.first { it is BackendState.Live } }
        delay(300) // a localhost frame is read in microseconds; this is ample
        assertEquals(0, calls.get())
        client.stop()
        server.shutdown()
    }

    @Test
    fun `a slow handler does not stall the socket reader`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val handled = CompletableDeferred<Unit>()
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                webSocket.send(helloAck(id = "a1", queued = 2))
                webSocket.send(delivery())
                webSocket.send(helloAck(id = "a2", queued = 7))
            }
        }))
        val client = clientFor(server)
        client.onTask = { gate.await(); handled.complete(Unit) }
        client.start(this)
        // The second ack is only read if the delivery above it did not block
        // the reader while the handler waited on the gate.
        withTimeout(2_000) {
            client.state.first { it is BackendState.Live && it.queued == 7 }
        }
        gate.complete(Unit)
        withTimeout(5_000) { handled.await() }
        client.stop()
        server.shutdown()
    }

    @Test
    fun `ping completes when the server answers pong`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().withWebSocketUpgrade(object : FakeGateway() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val frame = Json.decodeFromString<JsonObject>(text)
                when (frame["type"]!!.jsonPrimitive.content) {
                    "ping" -> webSocket.send("""{"v":1,"id":"p1","type":"pong","ts":2,"to":"dev-1"}""")
                    else -> webSocket.send(helloAck(id = "a1", queued = 0))
                }
            }
        }))
        val client = clientFor(server)
        client.start(this)
        withTimeout(5_000) { client.state.first { it is BackendState.Live } }
        withTimeout(5_000) { client.ping() }
        client.stop()
        server.shutdown()
    }

    @Test
    fun `reply builders copy the delivery and name their body`() {
        val progress = BackendReplies.progress(deliveryFrame(), "dev-1", "opening settings")
        assertEquals("task.progress", progress.type)
        assertEquals(TRACE, progress.trace)
        assertEquals("dev-1", progress.from)
        assertEquals("dev-9", progress.to)
        assertEquals("task-1", progress.task)
        assertEquals(1, progress.body["step"]!!.jsonPrimitive.int)
        assertEquals("opening settings", progress.body["note"]!!.jsonPrimitive.content)

        val result = BackendReplies.result(deliveryFrame(), "dev-1", "done")
        assertEquals("task.result", result.type)
        assertEquals("done", result.body["summary"]!!.jsonPrimitive.content)

        val error = BackendReplies.error(deliveryFrame(), "dev-1", "E_DEVICE", "no settings app", true)
        assertEquals("task.error", error.type)
        assertEquals("E_DEVICE", error.body["code"]!!.jsonPrimitive.content)
        assertEquals("no settings app", error.body["message"]!!.jsonPrimitive.content)
        assertTrue(error.body["retryable"]!!.jsonPrimitive.content.toBoolean())
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

    private fun helloAck(id: String, queued: Int): String =
        """{"v":1,"id":"$id","type":"device.hello.ack","ts":1,"to":"dev-1",
        "body":{"session":"s","serverTime":1,"heartbeatSec":25,"queued":$queued,"peers":["dev-2"]}}""".trimIndent()

    private fun delivery(): String = """{"v":1,"id":"m1","type":"task.create","ts":2,"from":"dev-9","to":"dev-1",
        "task":"task-1","trace":"$TRACE","body":{"goal":"open settings"}}""".trimIndent()

    private fun deliveryFrame(): BackendEnvelope = BackendEnvelope(
        id = "m1",
        type = "task.create",
        ts = 2,
        trace = TRACE,
        from = "dev-9",
        to = "dev-1",
        task = "task-1",
        body = JsonObject(mapOf("goal" to kotlinx.serialization.json.JsonPrimitive("open settings"))),
    )

    private companion object {
        const val TRACE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"
    }
}
