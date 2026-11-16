package com.hopa.capabilities.backend

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendCodecTest {
    @Test
    fun `round trip keeps the fields and drops nothing`() {
        val frame = BackendEnvelope(id = "i1", type = "task.ack", ts = 5, trace = "t1", to = "d2", task = "k1",
            body = buildJsonObject { put("state", "delivered") })
        val decoded = BackendCodec.decodeOrNull(BackendCodec.encode(frame))!!
        assertEquals(frame, decoded)
    }

    @Test
    fun `unknown extra fields are ignored`() {
        val decoded = BackendCodec.decodeOrNull("""{"v":1,"id":"i1","type":"presence","ts":1,"extra":true}""")!!
        assertEquals("presence", decoded.type)
    }

    @Test
    fun `a frame with no type or id is refused`() {
        assertNull(BackendCodec.decodeOrNull("""{"v":1,"id":"i1","ts":1}"""))
        assertNull(BackendCodec.decodeOrNull("not json"))
        assertNull(BackendCodec.decodeOrNull(""))
    }

    @Test
    fun `a frame whose major version is not one is refused`() {
        assertNull(BackendCodec.decodeOrNull("""{"v":2,"id":"i1","type":"presence","ts":1}"""))
        assertEquals(1, BackendCodec.decodeOrNull("""{"v":1,"id":"i1","type":"presence","ts":1}""")!!.v)
    }

    @Test
    fun `hello carries the push token only when there is one`() {
        val withToken = BackendCodec.hello("Xiaomi 15T Pro", "0.1.0", "tok")
        assertEquals("tok", withToken.body["pushToken"]!!.jsonPrimitive.content)
        assertNull(BackendCodec.hello("Xiaomi 15T Pro", "0.1.0", null).body["pushToken"])
    }
}
