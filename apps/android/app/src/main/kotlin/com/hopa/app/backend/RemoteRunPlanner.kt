package com.hopa.app.backend

/**
 * Decides whether a delivered task can run on the surface that answered. The
 * person's `screenPath` setting decides which surface is tried; this only reads
 * what the phone reports, so it is testable without Android.
 *
 * Order matters: a hidden or bridge surface runs whatever the visible screen is
 * doing, an onscreen run needs the screen on, unlocked and accessible, and any
 * remaining refusal is a retryable device condition.
 */
object RemoteRunPlanner {
    fun plan(
        surfaceName: String,
        screenPath: String,
        screenOn: Boolean,
        keyguardLocked: Boolean,
        accessibilityOn: Boolean,
        busy: Boolean,
    ): RemoteRunPlan {
        val surface = surfaceName.trim().lowercase()
        val path = screenPath.trim().lowercase()
        if (surface == "background" || surface == "bridge") {
            return RemoteRunPlan.Run
        }
        if (surface != "accessibility") {
            return if (path == "onscreen" && !accessibilityOn) {
                RemoteRunPlan.Refuse("E_CAPABILITY", CAPABILITY_OFF, false)
            } else {
                RemoteRunPlan.Refuse("E_DEVICE", NO_SURFACE, true)
            }
        }
        if (keyguardLocked) {
            return RemoteRunPlan.Refuse("E_DEVICE", LOCKED, true)
        }
        if (!screenOn) {
            return RemoteRunPlan.Refuse("E_DEVICE", SCREEN_OFF, true)
        }
        if (!accessibilityOn) {
            return RemoteRunPlan.Refuse("E_CAPABILITY", CAPABILITY_OFF, false)
        }
        if (busy) {
            return busyRefusal()
        }
        return RemoteRunPlan.Run
    }

    /** The refusal for a phone that is already running another turn. */
    fun busyRefusal(): RemoteRunPlan.Refuse = RemoteRunPlan.Refuse("E_DEVICE", BUSY, true)

    private const val LOCKED = "phone is locked; send it again when you pick it up"
    private const val SCREEN_OFF = "the screen is off; send it again when you pick it up"
    private const val BUSY = "the phone is busy with another task; send it again in a moment"
    private const val CAPABILITY_OFF = "screen control is off; turn on HOPA's screen access"
    private const val NO_SURFACE =
        "I cannot reach your apps: open Running apps, turn on screen access, " +
            "start the hidden screen, or pair your computer."
}
