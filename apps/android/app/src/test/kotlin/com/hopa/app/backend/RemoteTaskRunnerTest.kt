package com.hopa.app.backend

import com.hopa.capabilities.backend.BackendEnvelope
import com.hopa.capabilities.backend.BackendLink
import com.hopa.capabilities.backend.BackendState
import com.hopa.capabilities.backend.ProcessedTaskLog
import com.hopa.capabilities.device.UnavailableDevicePort
import com.hopa.core.contracts.Observation
import com.hopa.core.contracts.ObservationNode
import com.hopa.core.contracts.ScreenSize
import com.hopa.core.loop.TurnResult
import com.hopa.core.observation.ScreenSource
import com.hopa.core.observation.ScreenSourceDevice
import com.hopa.core.ports.DevicePort
import com.hopa.core.ports.InstalledApp
import com.hopa.data.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteTaskRunnerTest {
    @Test
    fun `a locked phone is acked before it is refused`() = runBlocking {
        val fixture = Fixture(locked = true)

        fixture.runner.handle(delivery("t-locked"))

        assertEquals(listOf("task.ack", "task.error"), fixture.link.frames.map { it.type })
        val error = fixture.link.frames[1]
        assertEquals("E_DEVICE", error.body["code"]?.jsonPrimitive?.contentOrNull)
        assertEquals(
            "phone is locked; send it again when you pick it up",
            error.body["message"]?.jsonPrimitive?.contentOrNull,
        )
        assertEquals("true", error.body["retryable"]?.jsonPrimitive?.contentOrNull)
        assertFalse(fixture.log.claim("t-locked"))
        assertEquals(0, fixture.runs)
    }

    @Test
    fun `a phone with no surface says what to set up`() = runBlocking {
        val fixture = Fixture(locked = false, surface = { noSurface() })

        fixture.runner.handle(delivery("t-nothing"))

        assertEquals(listOf("task.ack", "task.error"), fixture.link.frames.map { it.type })
        val message = fixture.link.frames[1].body["message"]?.jsonPrimitive?.contentOrNull
        assertTrue(message.orEmpty().contains("hidden screen"))
        assertEquals(0, fixture.runs)
    }

    @Test
    fun `a busy phone is acked and refused before a surface is opened`() = runBlocking {
        val fixture = Fixture(locked = false, busy = true)

        fixture.runner.handle(delivery("t-busy"))

        assertEquals(listOf("task.ack", "task.error"), fixture.link.frames.map { it.type })
        val error = fixture.link.frames[1]
        assertEquals("E_DEVICE", error.body["code"]?.jsonPrimitive?.contentOrNull)
        assertEquals("true", error.body["retryable"]?.jsonPrimitive?.contentOrNull)
        assertEquals(0, fixture.resolutions)
        assertEquals(0, fixture.runs)
    }

    @Test
    fun `a run posts one progress, one result and the approval notice`() = runBlocking {
        val fixture = Fixture(locked = false, foreground = false)

        fixture.runner.handle(delivery("t-run"))

        assertEquals(
            listOf("task.ack", "task.progress", "task.result"),
            fixture.link.frames.map { it.type },
        )
        assertEquals("Done.", fixture.link.frames[2].body["summary"]?.jsonPrimitive?.contentOrNull)
        assertEquals(1, fixture.approvals)
        assertEquals(1, fixture.started)
        assertEquals(1, fixture.finished)
        assertEquals(1, fixture.services)
    }

    @Test
    fun `a visible HOPA posts no approval notice`() = runBlocking {
        val fixture = Fixture(locked = false, foreground = true)

        fixture.runner.handle(delivery("t-visible"))

        assertEquals(0, fixture.approvals)
        assertEquals(1, fixture.runs)
    }

    @Test
    fun `a redelivery is acked but not run twice`() = runBlocking {
        val fixture = Fixture(locked = false)

        fixture.runner.handle(delivery("t-once"))
        fixture.runner.handle(delivery("t-once"))

        assertEquals(1, fixture.runs)
        assertEquals(
            listOf("task.ack", "task.progress", "task.result", "task.ack"),
            fixture.link.frames.map { it.type },
        )
    }
}

private fun delivery(id: String): BackendEnvelope = BackendEnvelope(
    id = "e-$id",
    type = "task.create",
    ts = 0,
    trace = "trace-$id",
    from = "server",
    to = "d1",
    task = id,
    body = buildJsonObject { put("goal", "open settings") },
)

private fun log(): ProcessedTaskLog {
    var stored: Map<String, Long> = emptyMap()
    return ProcessedTaskLog(load = { stored }, save = { stored = it })
}

private fun accessibilitySurface(): ScreenSourceDevice = ScreenSourceDevice(
    sources = listOf(ScreenSource("accessibility", StubDevice(), budgetMs = 50)),
    fallback = UnavailableDevicePort(),
)

private fun noSurface(): ScreenSourceDevice = ScreenSourceDevice(
    sources = emptyList(),
    fallback = UnavailableDevicePort(),
)

private class Fixture(
    locked: Boolean,
    private val foreground: Boolean = true,
    private val busy: Boolean = false,
    surface: () -> ScreenSourceDevice = { accessibilitySurface() },
) {
    val link = RecordingLink()
    val log = log()
    var runs = 0
    var resolutions = 0
    var approvals = 0
    var started = 0
    var finished = 0
    var services = 0

    val runner = RemoteTaskRunner(
        deviceId = { "d1" },
        link = { link },
        processed = log,
        settings = { AppSettings(screenPath = "auto", accessibilityAllowed = true) },
        surfaces = {
            resolutions += 1
            surface()
        },
        screen = {
            RemoteScreenState(
                screenOn = true,
                keyguardLocked = locked,
                accessibilityOn = true,
            )
        },
        busy = { busy },
        cancel = MutableStateFlow(false),
        foreground = { foreground },
        startService = { services += 1 },
        stopService = {},
        postApproval = { approvals += 1 },
        releaseSurface = {},
        run = {
            runs += 1
            TurnResult("Done.")
        },
        taskStarted = { started += 1 },
        taskFinished = { finished += 1 },
    )
}

private class RecordingLink : BackendLink {
    val frames = mutableListOf<BackendEnvelope>()
    override val state = MutableStateFlow<BackendState>(BackendState.Disconnected)
    override var onTask: (suspend (BackendEnvelope) -> Unit)? = null

    override fun start(scope: CoroutineScope) = Unit
    override suspend fun stop() = Unit
    override suspend fun send(frame: BackendEnvelope) {
        frames += frame
    }

    override suspend fun ack(task: BackendEnvelope) {
        frames += task.copy(id = "ack-${task.task}", type = "task.ack")
    }

    override suspend fun ping() = Unit
}

private class StubDevice : DevicePort {
    override suspend fun observe(): Observation = Observation(
        id = "obs-test",
        timestamp = 0,
        screenSize = ScreenSize(width = 1080, height = 2400),
        tree = ObservationNode(type = "root"),
    )

    override suspend fun tap(ref: String?, x: Int?, y: Int?) = Unit
    override suspend fun type(text: String, ref: String?) = Unit
    override suspend fun swipe(fromX: Int, fromY: Int, toX: Int, toY: Int, velocity: Int) = Unit
    override suspend fun pressBack() = Unit
    override suspend fun pressHome() = Unit
    override suspend fun launch(packageName: String) = Unit
    override suspend fun stop(packageName: String) = Unit
    override suspend fun foreground(): String = "com.hopa.android"
    override suspend fun screenshot(): ByteArray? = null
    override suspend fun apps(): List<InstalledApp> = emptyList()
}
