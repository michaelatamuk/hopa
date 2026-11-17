package com.hopa.capabilities.backend

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessedTaskLogTest {
    @Test
    fun `the first claim is true and the second is false`() = runBlocking {
        var stored = emptyMap<String, Long>()
        val log = ProcessedTaskLog(load = { stored }, save = { stored = it }, now = { 1_000 })
        assertTrue(log.claim("t1"))
        assertFalse(log.claim("t1"))
    }

    @Test
    fun `an entry older than a day can run again`() = runBlocking {
        var stored = mapOf("t1" to 0L)
        val log = ProcessedTaskLog(load = { stored }, save = { stored = it }, now = { 86_400_001L })
        assertTrue(log.claim("t1"))
    }

    @Test
    fun `a claim records the clock and forgets expired entries`() = runBlocking {
        var stored = mapOf("old" to 0L, "kept" to 50_000L)
        val log = ProcessedTaskLog(load = { stored }, save = { stored = it }, now = { 86_400_001L })
        assertTrue(log.claim("t2"))
        assertEquals(mapOf("kept" to 50_000L, "t2" to 86_400_001L), stored)
    }
}
