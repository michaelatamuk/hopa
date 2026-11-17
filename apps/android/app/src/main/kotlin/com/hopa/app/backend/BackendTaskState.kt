package com.hopa.app.backend

/**
 * What the app knows about the last delivered task, for the Backend card. The
 * runner brackets a run with [BackendSession.taskStarted] and
 * [BackendSession.taskFinished], so this never claims more than happened.
 */
data class BackendTaskState(
    val taskId: String = "",
    val goal: String = "",
    val state: String = "idle",
    val note: String = "",
)
