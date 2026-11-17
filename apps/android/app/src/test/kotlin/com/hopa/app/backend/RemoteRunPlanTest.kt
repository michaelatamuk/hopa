package com.hopa.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteRunPlanTest {
    @Test
    fun `a hidden or bridge surface runs even with the screen off`() {
        assertEquals(
            RemoteRunPlan.Run,
            RemoteRunPlanner.plan("background", "hidden", false, true, false, false),
        )
        assertEquals(
            RemoteRunPlan.Run,
            RemoteRunPlanner.plan("bridge", "bridge", false, true, false, false),
        )
    }

    @Test
    fun `onscreen while locked is refused with a retryable device error`() {
        val plan = RemoteRunPlanner.plan(
            "accessibility",
            "onscreen",
            screenOn = false,
            keyguardLocked = true,
            accessibilityOn = true,
            busy = false,
        )
        assertEquals(
            RemoteRunPlan.Refuse(
                "E_DEVICE",
                "phone is locked; send it again when you pick it up",
                true,
            ),
            plan,
        )
    }

    @Test
    fun `no surface at all names the setup step`() {
        val plan = RemoteRunPlanner.plan("fallback", "hidden", true, false, false, false)
        assertEquals("E_DEVICE", (plan as RemoteRunPlan.Refuse).code)
        assertTrue(plan.message.contains("hidden screen"))
    }

    @Test
    fun `a busy phone is refused as retryable`() {
        val plan = RemoteRunPlanner.plan(
            "accessibility",
            "auto",
            true,
            false,
            true,
            busy = true,
            accessibilityBound = true,
        )
        assertEquals("E_DEVICE", (plan as RemoteRunPlan.Refuse).code)
    }

    @Test
    fun `screen control off on the onscreen path is a capability error`() {
        val plan = RemoteRunPlanner.plan("fallback", "onscreen", true, false, false, false)
        assertEquals("E_CAPABILITY", (plan as RemoteRunPlan.Refuse).code)
    }
}
