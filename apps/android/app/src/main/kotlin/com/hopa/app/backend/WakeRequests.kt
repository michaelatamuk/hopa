package com.hopa.app.backend

/**
 * Reads the ids out of a data-only FCM message. A message without a task id is
 * not a wake, and [WakeRequest.traceId] is empty when the sender omitted it.
 */
object WakeRequests {
    fun from(data: Map<String, String>): WakeRequest? {
        val taskId = data["taskId"]?.takeIf { it.isNotBlank() } ?: return null
        return WakeRequest(taskId, data["traceId"].orEmpty())
    }
}
