package com.hopa.app.backend

/**
 * The one answer to "can a run use the visible screen right now?". The person
 * must allow it, Android must list the service as enabled, and the service must
 * be bound. Chat, the planner and the Running apps pill all read this, so a
 * screen that is switched on but still connecting is never reported as ready.
 */
object ScreenAccess {
    /** All three must hold before an onscreen surface may be offered. */
    fun available(allowed: Boolean, enabled: Boolean, bound: Boolean): Boolean =
        allowed && enabled && bound

    /** The permission is on; the service may still be connecting. */
    fun switchedOn(allowed: Boolean, enabled: Boolean): Boolean = allowed && enabled
}
