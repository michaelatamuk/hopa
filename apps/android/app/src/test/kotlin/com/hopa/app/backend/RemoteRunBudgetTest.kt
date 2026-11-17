package com.hopa.app.backend

import org.junit.Assert.assertEquals
import org.junit.Test

class RemoteRunBudgetTest {
    @Test
    fun `the run gets what the resolve left of one deadline`() {
        assertEquals(145_000L, RunBudget.remaining(totalMs = 170_000L, elapsedMs = 25_000L))
        assertEquals(170_000L, RunBudget.remaining(totalMs = 170_000L, elapsedMs = 0L))
    }

    @Test
    fun `an exhausted deadline is zero, never negative`() {
        assertEquals(0L, RunBudget.remaining(totalMs = 170_000L, elapsedMs = 170_000L))
        assertEquals(0L, RunBudget.remaining(totalMs = 170_000L, elapsedMs = 200_000L))
    }
}
