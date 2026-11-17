package com.hopa.capabilities.backend

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A durable "this task already ran" set, keyed by `task.id` and stamped with
 * the clock at the claim. The claim is written before execution starts, so a
 * redelivery inside the window is skipped instead of run twice; an entry older
 * than the window is pruned and may run again.
 *
 * Claims are serialized: two deliveries of the same id racing through a
 * load/save pair would otherwise both see an empty map and both run. Storage
 * is injected, so the log is testable without Android.
 */
class ProcessedTaskLog(
    private val load: suspend () -> Map<String, Long>,
    private val save: suspend (Map<String, Long>) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val ttlMs: Long = DEFAULT_TTL_MS,
) {
    private val claims = Mutex()

    /** True when the caller may run the task; records the claim when it may. */
    suspend fun claim(taskId: String): Boolean = claims.withLock {
        val at = now()
        val stored = load()
        val seen = stored[taskId]
        if (seen != null && at - seen < ttlMs) return@withLock false
        save(stored.filterValues { at - it < ttlMs } + (taskId to at))
        true
    }

    private companion object {
        /** One day, the design's dedupe window. */
        const val DEFAULT_TTL_MS = 86_400_000L
    }
}
