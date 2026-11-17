package com.hopa.app.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenAccessTest {
    @Test
    fun `screen access needs allowed enabled and bound`() {
        assertTrue(ScreenAccess.available(allowed = true, enabled = true, bound = true))
        assertFalse(ScreenAccess.available(allowed = true, enabled = true, bound = false))
        assertFalse(ScreenAccess.available(allowed = false, enabled = true, bound = true))
        assertFalse(ScreenAccess.available(allowed = true, enabled = false, bound = true))
    }

    @Test
    fun `allowed enabled and bound can run onscreen`() {
        assertEquals(
            RemoteRunPlan.Run,
            RemoteRunPlanner.plan(
                surfaceName = "accessibility",
                screenPath = "auto",
                screenOn = true,
                keyguardLocked = false,
                accessibilityOn = true,
                busy = false,
                accessibilityBound = true,
            ),
        )
    }

    @Test
    fun `switched on but unbound asks to open HOPA once`() {
        val plan = RemoteRunPlanner.plan(
            surfaceName = "fallback",
            screenPath = "auto",
            screenOn = true,
            keyguardLocked = false,
            accessibilityOn = true,
            busy = false,
            accessibilityBound = false,
        )

        assertTrue(plan is RemoteRunPlan.Refuse)
        plan as RemoteRunPlan.Refuse
        assertEquals("E_CAPABILITY", plan.code)
        assertTrue(plan.message.contains("not connected"))
        assertFalse(plan.retryable)
    }

    @Test
    fun `allowed off on the onscreen path is still a capability refusal`() {
        val plan = RemoteRunPlanner.plan(
            surfaceName = "fallback",
            screenPath = "onscreen",
            screenOn = true,
            keyguardLocked = false,
            accessibilityOn = false,
            busy = false,
            accessibilityBound = false,
        )

        assertEquals("E_CAPABILITY", (plan as RemoteRunPlan.Refuse).code)
        assertTrue(plan.message.contains("screen control is off"))
    }

    @Test
    fun `the no surface refusal carries the last source error`() {
        val plan = RemoteRunPlanner.plan(
            surfaceName = "fallback",
            screenPath = "auto",
            screenOn = true,
            keyguardLocked = false,
            accessibilityOn = false,
            busy = false,
            accessibilityBound = false,
            lastError = "background timed out after 8000 ms",
        )

        assertEquals("E_DEVICE", (plan as RemoteRunPlan.Refuse).code)
        assertTrue(plan.message.contains("background timed out after 8000 ms"))
    }
}
