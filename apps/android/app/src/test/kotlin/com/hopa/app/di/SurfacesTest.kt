package com.hopa.app.di

import com.hopa.capabilities.device.UnavailableDevicePort
import com.hopa.core.observation.ScreenSourceDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacesTest {
    @Test
    fun `a cancelled resolve stops the display it started`() = runBlocking {
        var started = false
        var stopped = false
        val display = Any()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val job = scope.launch {
            Surfaces.opened(
                start = { started = true; display },
                close = { stopped = true },
                body = { awaitCancellation() },
            )
        }
        withTimeout(2_000) { while (!started) delay(10) }
        job.cancelAndJoin()

        assertTrue(stopped)
    }

    @Test
    fun `a completed resolve hands the display to the caller`() = runBlocking {
        var stopped = false

        val result = Surfaces.opened(
            start = { Any() },
            close = { stopped = true },
            body = {
                ScreenSourceDevice(sources = emptyList(), fallback = UnavailableDevicePort())
            },
        )

        assertFalse(stopped)
        assertTrue(result.sourceName.isEmpty())
    }
}
