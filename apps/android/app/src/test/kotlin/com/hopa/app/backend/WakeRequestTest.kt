package com.hopa.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WakeRequestTest {
    @Test
    fun `a data message with a task id is a wake`() {
        assertEquals(
            WakeRequest("t1", "tr1"),
            WakeRequests.from(mapOf("taskId" to "t1", "traceId" to "tr1")),
        )
    }

    @Test
    fun `a message without a task id is not a wake`() {
        assertNull(WakeRequests.from(mapOf("traceId" to "tr1")))
    }

    @Test
    fun `a blank task id is not a wake`() {
        assertNull(WakeRequests.from(mapOf("taskId" to "   ", "traceId" to "tr1")))
    }

    @Test
    fun `a message without a trace id has an empty one`() {
        assertEquals(WakeRequest("t1", ""), WakeRequests.from(mapOf("taskId" to "t1")))
    }
}
