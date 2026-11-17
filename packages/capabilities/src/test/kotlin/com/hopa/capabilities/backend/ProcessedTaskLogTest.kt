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

    @Test
    fun `a released claim may run again`() = runBlocking {
        var stored = emptyMap<String, Long>()
        val log = ProcessedTaskLog(load = { stored }, save = { stored = it }, now = { 1_000 })
        assertTrue(log.claim("t1"))
        assertFalse(log.claim("t1"))

        log.release("t1")

        assertTrue(log.claim("t1"))
        assertEquals(mapOf("t1" to 1_000L), stored)
    }

    @Test
    fun `releasing an unknown id writes nothing`() = runBlocking {
        var stored = mapOf("kept" to 10L)
        var saves = 0
        val log = ProcessedTaskLog(
            load = { stored },
            save = { stored = it; saves += 1 },
            now = { 1_000 },
        )

        log.release("missing")

        assertEquals(0, saves)
        assertEquals(mapOf("kept" to 10L), stored)
    }

    @Test
    fun `seen reports a claim without taking one`() = runBlocking {
        var stored = emptyMap<String, Long>()
        val log = ProcessedTaskLog(load = { stored }, save = { stored = it }, now = { 1_000 })
        assertFalse(log.seen("t1"))
        assertTrue(log.claim("t1"))
        assertTrue(log.seen("t1"))
    }

    @Test
    fun `seen forgets an entry older than a day`() = runBlocking {
        var stored = mapOf("t1" to 0L)
        val log = ProcessedTaskLog(load = { stored }, save = { stored = it }, now = { 86_400_001L })
        assertFalse(log.seen("t1"))
    }
}
