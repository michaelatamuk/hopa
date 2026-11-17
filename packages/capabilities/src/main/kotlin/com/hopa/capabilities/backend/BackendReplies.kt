package com.hopa.capabilities.backend

import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builds the frames a device sends back for one delivered task. Every reply
 * copies the delivery's task id and traceparent and addresses the device that
 * sent it, so the server can relay it without guessing.
 */
object BackendReplies {
    fun progress(task: BackendEnvelope, deviceId: String, note: String): BackendEnvelope = reply(
        delivery = task,
        deviceId = deviceId,
        type = "task.progress",
        body = buildJsonObject {
            put("step", 1)
            put("note", note)
        },
    )

    fun result(task: BackendEnvelope, deviceId: String, summary: String): BackendEnvelope = reply(
        delivery = task,
        deviceId = deviceId,
        type = "task.result",
        body = buildJsonObject { put("summary", summary) },
    )

    /** [code] is one of the stable codes in `contracts/errors.md`. */
    fun error(
        task: BackendEnvelope,
        deviceId: String,
        code: String,
        message: String,
        retryable: Boolean,
    ): BackendEnvelope = reply(
        delivery = task,
        deviceId = deviceId,
        type = "task.error",
        body = buildJsonObject {
            put("code", code)
            put("message", message)
            put("retryable", retryable)
        },
    )

    private fun reply(
        delivery: BackendEnvelope,
        deviceId: String,
        type: String,
        body: JsonObject,
    ): BackendEnvelope = BackendEnvelope(
        id = UUID.randomUUID().toString(),
        type = type,
        ts = System.currentTimeMillis(),
        trace = delivery.trace,
        from = deviceId,
        to = requireNotNull(delivery.from) { "a delivered task must name its sender" },
        task = requireNotNull(delivery.task) { "a delivered task must carry its id" },
        body = body,
    )
}
