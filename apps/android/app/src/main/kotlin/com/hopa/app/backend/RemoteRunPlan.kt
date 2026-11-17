package com.hopa.app.backend

/**
 * What a delivered task may do on this phone right now. A refusal is an answer
 * too: it carries a stable code, a sentence for the sender and whether a later
 * attempt could work.
 */
sealed interface RemoteRunPlan {
    /** The phone can run the task on the surface that answered. */
    data object Run : RemoteRunPlan

    data class Refuse(
        val code: String,
        val message: String,
        val retryable: Boolean,
    ) : RemoteRunPlan
}
