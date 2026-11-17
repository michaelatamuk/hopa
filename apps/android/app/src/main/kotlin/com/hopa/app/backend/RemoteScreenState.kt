package com.hopa.app.backend

/**
 * What the phone reports about the visible screen when a deliverable task
 * arrives. Kept as plain values so the planner and the runner can be tested
 * without Android. [accessibilityOn] means the person allowed it and Android
 * lists the service; [accessibilityBound] means the service actually answered.
 */
data class RemoteScreenState(
    val screenOn: Boolean,
    val keyguardLocked: Boolean,
    val accessibilityOn: Boolean,
    val accessibilityBound: Boolean = false,
)
