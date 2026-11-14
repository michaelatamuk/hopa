package com.hopa.sidecar

import com.hopa.core.contracts.Action
import com.hopa.core.contracts.RunRecord
import com.hopa.core.records.RunChain
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class RecordStoreTest {
    private fun record(): RunRecord = RunRecord(
        runId = "run-1",
        step = 0,
        traceId = "trace-1",
        action = Action(kind = "ui.tap"),
        classification = "read",
        observationHash = "sha256:obs",
        beforeHash = "sha256:before",
        afterHash = "sha256:after",
        verified = true,
        executed = true,
        prevHash = RunChain.SEED,
        hash = "sha256:record",
    )

    @Test
    fun turns_are_appended_and_survive_a_reload() {
        val directory = Files.createTempDirectory("hopa-records")
        val path = directory.resolve("records.json")

        val store = RecordStore(path)
        store.append("c-1", "run-1", listOf(record()))
        store.append("c-1", "run-2", listOf(record(), record().copy(step = 1)))

        assertEquals(2, store.count())
        assertEquals(2, RecordStore(path).count())
    }

    @Test
    fun an_empty_turn_is_not_written() {
        val directory = Files.createTempDirectory("hopa-records-empty")
        val store = RecordStore(directory.resolve("records.json"))
        store.append("c-1", "run-1", emptyList())
        assertEquals(0, store.count())
    }
}
