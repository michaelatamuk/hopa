package com.hopa.capabilities.backend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingLinksTest {
    @Test
    fun `parses a hopa pairing uri`() {
        val link = PairingLinks.parse(
            "hopa://pair?v=1&h=h1&p=s1&u=wss%3A%2F%2Fexample.test%2Fv1%2Fgateway",
        )!!
        assertEquals("h1", link.householdId)
        assertEquals("s1", link.session)
        assertEquals("wss://example.test/v1/gateway", link.gatewayUrl)
    }

    @Test
    fun `parameter order does not matter`() {
        val link = PairingLinks.parse("hopa://pair?u=wss://a/v1/gateway&p=s1&v=1&h=h1")!!
        assertEquals("s1", link.session)
        assertEquals("h1", link.householdId)
        assertEquals("wss://a/v1/gateway", link.gatewayUrl)
    }

    @Test
    fun `a bare session uses the configured gateway`() {
        val link = PairingLinks.parse("s1", "wss://fallback/v1/gateway")!!
        assertEquals("s1", link.session)
        assertEquals("wss://fallback/v1/gateway", link.gatewayUrl)
    }

    @Test
    fun `a pairing uri without a gateway falls back`() {
        val link = PairingLinks.parse(
            "hopa://pair?v=1&h=h1&p=s1",
            "wss://fallback/v1/gateway",
        )!!
        assertEquals("h1", link.householdId)
        assertEquals("wss://fallback/v1/gateway", link.gatewayUrl)
    }

    @Test
    fun `garbage is refused`() {
        assertNull(PairingLinks.parse("hello", ""))
        assertNull(PairingLinks.parse("", ""))
        assertNull(PairingLinks.parse("s1", ""))
        assertNull(PairingLinks.parse("hopa://pair?v=1&h=h1", "wss://fallback/v1/gateway"))
        assertNull(PairingLinks.parse("hopa://pair?v=1&h=h1&p=s1", ""))
        assertNull(PairingLinks.parse("hopa://pair?p=s1", ""))
        assertNull(PairingLinks.parse("https://example.test/v1/gateway", ""))
    }

    @Test
    fun `httpBase maps the socket scheme and drops the v1 path`() {
        assertEquals("https://example.test", BackendUrls.httpBase("wss://example.test/v1/gateway"))
        assertEquals("http://127.0.0.1:8080", BackendUrls.httpBase("ws://127.0.0.1:8080/v1/gateway"))
        assertEquals("https://example.test:8443", BackendUrls.httpBase("wss://example.test:8443/v1/gateway/"))
    }
}
