package com.hopa.capabilities.backend

import com.hopa.core.json.HopaJson
import java.util.UUID
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Encodes and decodes the v1 gateway envelope. Unknown fields are ignored on
 * the way in, as the frozen schema promises; a frame that cannot be read is
 * null, never an exception.
 */
object BackendCodec {
    fun encode(frame: BackendEnvelope): String = buildJsonObject {
        put("v", frame.v)
        put("id", frame.id)
        put("type", frame.type)
        put("ts", frame.ts)
        frame.trace?.let { put("trace", it) }
        frame.from?.let { put("from", it) }
        frame.to?.let { put("to", it) }
        frame.task?.let { put("task", it) }
        put("body", frame.body)
    }.toString()

    /** Null for anything that is not a v1 frame with both an id and a type. */
    fun decodeOrNull(text: String): BackendEnvelope? = try {
        val frame = HopaJson.element.parseToJsonElement(text).jsonObject
        BackendEnvelope(
            v = frame["v"]?.jsonPrimitive?.intOrNull ?: 1,
            id = frame["id"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            type = frame["type"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            ts = frame["ts"]?.jsonPrimitive?.longOrNull ?: 0,
            trace = frame["trace"]?.jsonPrimitive?.contentOrNull,
            from = frame["from"]?.jsonPrimitive?.contentOrNull,
            to = frame["to"]?.jsonPrimitive?.contentOrNull,
            task = frame["task"]?.jsonPrimitive?.contentOrNull,
            body = frame["body"]?.jsonObject ?: JsonObject(emptyMap()),
        ).takeIf { it.v == 1 && it.id.isNotBlank() && it.type.isNotBlank() }
    } catch (error: Exception) {
        null
    }

    fun hello(name: String, appVersion: String, pushToken: String?): BackendEnvelope = BackendEnvelope(
        id = UUID.randomUUID().toString(),
        type = "device.hello",
        ts = System.currentTimeMillis(),
        body = buildJsonObject {
            put("platform", "android")
            put("name", name)
            put("appVersion", appVersion)
            if (pushToken != null) put("pushToken", pushToken)
        },
    )
}
