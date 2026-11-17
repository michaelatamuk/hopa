package com.hopa.app.backend

import com.hopa.core.observation.ScreenSourceDevice
import com.hopa.data.settings.AppSettings

/**
 * One turn a delivered task asks for. The runner owns the surface and the
 * reply, so the Android wiring can build the context outside the runner and a
 * JVM test can supply a fake.
 */
data class RemoteRunRequest(
    val goal: String,
    val device: ScreenSourceDevice,
    val settings: AppSettings,
    val runId: String,
    val traceId: String,
    val isCancelled: () -> Boolean = { false },
)
