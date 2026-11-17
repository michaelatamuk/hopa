package com.hopa.app.backend

/**
 * Decides whether a delivered task can run on the surface that answered. The
 * person's `screenPath` setting decides which surface is tried; this only reads
 * what the phone reports, so it is testable without Android.
 *
 * Order matters: a hidden or bridge surface runs whatever the visible screen is
 * doing, an onscreen run needs the screen on, unlocked and accessible, and any
 * remaining refusal is a retryable device condition. A service that is switched
 * on but not bound is its own capability refusal: the fix is to open HOPA once,
 * not to pair or start the hidden screen.
 */
object RemoteRunPlanner {
    fun plan(
        surfaceName: String,
        screenPath: String,
        screenOn: Boolean,
        keyguardLocked: Boolean,
        accessibilityOn: Boolean,
        busy: Boolean,
        accessibilityBound: Boolean = false,
        lastError: String = "",
    ): RemoteRunPlan {
        val surface = surfaceName.trim().lowercase()
        val path = screenPath.trim().lowercase()
        if (surface == "background" || surface == "bridge") {
            return RemoteRunPlan.Run
        }
        if (surface != "accessibility") {
            return when {
                path in setOf("auto", "onscreen") && accessibilityOn && !accessibilityBound ->
                    RemoteRunPlan.Refuse("E_CAPABILITY", CAPABILITY_UNBOUND, false)

                path == "onscreen" && !accessibilityOn ->
                    RemoteRunPlan.Refuse("E_CAPABILITY", CAPABILITY_OFF, false)

                else ->
                    RemoteRunPlan.Refuse("E_DEVICE", noSurface(lastError), true)
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
        if (!accessibilityBound) {
            return RemoteRunPlan.Refuse("E_CAPABILITY", CAPABILITY_UNBOUND, false)
        }
        if (busy) {
            return busyRefusal()
        }
        return RemoteRunPlan.Run
    }

    /** The refusal for a phone that is already running another turn. */
    fun busyRefusal(): RemoteRunPlan.Refuse = RemoteRunPlan.Refuse("E_DEVICE", BUSY, true)

    /** The setup sentence, plus why the surfaces that were tried said no. */
    private fun noSurface(lastError: String): String =
        if (lastError.isBlank()) NO_SURFACE else "$NO_SURFACE (${lastError.take(180)})"

    private const val LOCKED = "phone is locked; send it again when you pick it up"
    private const val SCREEN_OFF = "the screen is off; send it again when you pick it up"
    private const val BUSY = "the phone is busy with another task; send it again in a moment"
    private const val CAPABILITY_OFF = "screen control is off; turn on HOPA's screen access"
    private const val CAPABILITY_UNBOUND =
        "screen access is on but not connected - open HOPA once"
    private const val NO_SURFACE =
        "I cannot reach your apps: open Running apps, turn on screen access, " +
            "start the hidden screen, or pair your computer."
}
