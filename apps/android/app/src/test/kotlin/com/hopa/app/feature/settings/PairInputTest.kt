package com.hopa.app.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairInputTest {
    @Test
    fun `the field accepts a whole uri or a bare session`() {
        assertEquals("s1", PairInput.normalise("hopa://pair?p=s1&h=h1&u=wss://x/v1/gateway", "")!!.session)
        assertEquals("s1", PairInput.normalise("  s1  ", "wss://y/v1/gateway")!!.session)
    }

    @Test
    fun `a whole uri carries its own gateway`() {
        val link = PairInput.normalise(
            "hopa://pair?v=1&h=h1&p=s1&u=wss%3A%2F%2Fx%2Fv1%2Fgateway",
            "",
        )
        assertEquals("wss://x/v1/gateway", link?.gatewayUrl)
    }

    @Test
    fun `a bare session needs a saved gateway`() {
        assertNull(PairInput.normalise("s1", "   "))
    }
}
