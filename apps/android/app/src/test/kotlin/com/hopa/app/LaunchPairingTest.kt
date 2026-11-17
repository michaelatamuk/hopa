package com.hopa.app

import com.hopa.capabilities.backend.PairingLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LaunchPairingTest {
    @Test
    fun `a failed claim is left where the card reads it`() = runBlocking {
        val notice = MutableStateFlow<String?>(null)
        val claimed = mutableListOf<PairingLink>()
        LaunchPairing.apply(
            backendUrl = "wss://x/v1/gateway",
            backendPair = "s1",
            notice = notice,
            storeUrl = { error("nothing to store") },
            claim = { link ->
                claimed += link
                Result.failure(IllegalStateException("the session was already used"))
            },
        )
        assertEquals(listOf("wss://x/v1/gateway"), claimed.map { it.gatewayUrl })
        assertEquals("Gateway: the session was already used", notice.value)
    }

    @Test
    fun `an address alone is stored and never claimed`() = runBlocking {
        val notice = MutableStateFlow<String?>(null)
        val stored = mutableListOf<String>()
        var claims = 0
        LaunchPairing.apply(
            backendUrl = "wss://x/v1/gateway",
            backendPair = null,
            notice = notice,
            storeUrl = { stored += it },
            claim = { claims += 1; Result.success(Unit) },
        )
        assertEquals(listOf("wss://x/v1/gateway"), stored)
        assertEquals(0, claims)
        assertNull(notice.value)
    }

    @Test
    fun `an invalid pairing link is reported and never claimed`() = runBlocking {
        val notice = MutableStateFlow<String?>(null)
        var claims = 0
        LaunchPairing.apply(
            backendUrl = "wss://x/v1/gateway",
            backendPair = "not a session!",
            notice = notice,
            storeUrl = {},
            claim = { claims += 1; Result.success(Unit) },
        )
        assertEquals(0, claims)
        assertEquals("Gateway: the launch pairing link is not valid", notice.value)
    }

    @Test
    fun `a bare session without an address is reported, not guessed`() = runBlocking {
        val notice = MutableStateFlow<String?>(null)
        var claims = 0
        LaunchPairing.apply(
            backendUrl = null,
            backendPair = "s1",
            notice = notice,
            storeUrl = {},
            claim = { claims += 1; Result.success(Unit) },
        )
        assertEquals(0, claims)
        assertEquals("Gateway: the launch pairing link is not valid", notice.value)
    }
}
