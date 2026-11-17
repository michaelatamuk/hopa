package com.hopa.app.backend

/**
 * One shared deadline for a delivered task's resolve and run. Keeping the
 * subtraction here makes the short-service arithmetic testable without a clock.
 */
object RunBudget {
    /** What is left of [totalMs] after [elapsedMs], never negative. */
    fun remaining(totalMs: Long, elapsedMs: Long): Long =
        (totalMs - elapsedMs).coerceAtLeast(0)
}
