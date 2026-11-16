package com.hopa.capabilities.backend

import kotlinx.serialization.json.JsonObject

/**
 * One frame on the `/v1/gateway` socket, named exactly as
 * `contracts/schemas/gateway.v1.schema.json` names it. The optional fields are
 * absent, not null, on the wire; [BackendCodec] keeps it that way.
 */
data class BackendEnvelope(
    val v: Int = 1,
    val id: String,
    val type: String,
    val ts: Long,
    val trace: String? = null,
    val from: String? = null,
    val to: String? = null,
    val task: String? = null,
    val body: JsonObject = JsonObject(emptyMap()),
)
