package com.hopa.sidecar

import com.hopa.core.contracts.Action
import com.hopa.core.gate.ApprovalRequest
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Test

class PendingApprovalsTest {
    private val request = ApprovalRequest(
        title = "Confirm payment",
        body = "Money may leave your account.",
        amount = "100",
        merchant = "Cafe",
        consequence = "Cannot be undone",
        isPayment = true,
        action = Action(kind = "payment.pay"),
    )

    @Test
    fun a_pending_approval_is_written_and_denied_on_restart() {
        val directory = Files.createTempDirectory("hopa-approvals")
        val path = directory.resolve("approvals.json")

        val store = PendingApprovals(path)
        store.save("ap-1", request)
        assertEquals(1, store.restoreAndDeny())

        // A new process sees the file cleared: the restart denied the request.
        assertEquals(0, PendingApprovals(path).restoreAndDeny())
    }

    @Test
    fun clearing_one_request_keeps_the_other() {
        val directory = Files.createTempDirectory("hopa-approvals-two")
        val store = PendingApprovals(directory.resolve("approvals.json"))
        store.save("ap-1", request)
        store.save("ap-2", request)
        store.clear("ap-1")
        assertEquals(1, PendingApprovals(directory.resolve("approvals.json")).restoreAndDeny())
    }
}
