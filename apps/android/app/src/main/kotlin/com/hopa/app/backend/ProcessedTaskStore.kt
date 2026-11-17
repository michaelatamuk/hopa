package com.hopa.app.backend

import android.content.Context
import com.hopa.capabilities.backend.ProcessedTaskLog

/**
 * The processed-task log on disk: one preferences entry per task id. A
 * redelivery after a process death is still skipped instead of run twice, while
 * the log's 24 h window keeps the file from growing forever.
 */
object ProcessedTaskStore {
    private const val FILE = "hopa_processed_tasks"

    fun open(context: Context): ProcessedTaskLog {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        return ProcessedTaskLog(
            load = {
                prefs.all.mapNotNull { (id, at) -> (at as? Long)?.let { id to it } }.toMap()
            },
            save = { claims ->
                prefs.edit().clear().apply {
                    claims.forEach { (id, at) -> putLong(id, at) }
                }.commit()
            },
        )
    }
}
