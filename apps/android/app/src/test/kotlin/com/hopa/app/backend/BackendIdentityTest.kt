package com.hopa.app.backend

import com.hopa.app.backend.SessionFixtures.FakeSettings
import com.hopa.app.backend.SessionFixtures.gateway
import com.hopa.app.backend.SessionFixtures.pairingServer
import com.hopa.capabilities.backend.BackendPairing
import com.hopa.capabilities.backend.BackendPairingFailure
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendIdentityTest {
    @Test
    fun `a cancelled refresh still persists the rotated pair`() = runBlocking {
        val server = pairingServer(
            MockResponse().setResponseCode(200)
                .setBody("""{"accessToken":"a2","refreshToken":"r2","expiresAt":"2026-11-17T00:15:00Z"}""")
                .setBodyDelay(300, TimeUnit.MILLISECONDS),
        )
        try {
            val settings = FakeSettings().paired(gateway(server))
            val identity = BackendIdentity(settings) { BackendPairing(it) }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

            val job = scope.launch { identity.refresh() }
            // Wait until the POST has reached the server, so the cancel lands
            // while the rotated pair is in flight, not before the request.
            assertTrue(server.takeRequest(2, TimeUnit.SECONDS) != null)
            job.cancelAndJoin()

            withTimeout(2_000) { while (settings.backendAccessToken() != "a2") delay(20) }
            assertEquals("a2", settings.backendAccessToken())
            assertEquals("r2", settings.backendRefreshToken())
            assertEquals("d1", settings.snapshot.backendDeviceId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an unreachable refresh keeps the stored identity`() = runBlocking {
        val server = pairingServer(
            MockResponse().setResponseCode(503)
                .setBody("""{"code":"E_UNAVAILABLE","message":"try later"}"""),
        )
        try {
            val settings = FakeSettings().paired(gateway(server))
            val identity = BackendIdentity(settings) { BackendPairing(it) }

            val result = runCatching { identity.refresh() }

            assertTrue(result.exceptionOrNull() is BackendPairingFailure.Unavailable)
            assertEquals("a1", settings.backendAccessToken())
            assertEquals("r1", settings.backendRefreshToken())
            assertEquals("d1", settings.snapshot.backendDeviceId)
            assertEquals("h1", settings.snapshot.backendHouseholdId)
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a refused refresh asks for pairing but keeps the tokens`() = runBlocking {
        val server = pairingServer(
            MockResponse().setResponseCode(401)
                .setBody("""{"code":"E_AUTH","message":"expired"}"""),
        )
        try {
            val settings = FakeSettings().paired(gateway(server))
            val identity = BackendIdentity(settings) { BackendPairing(it) }

            val refreshed = identity.refresh()

            assertEquals(null, refreshed)
            assertEquals("a1", settings.backendAccessToken())
            assertEquals("r1", settings.backendRefreshToken())
        } finally {
            server.shutdown()
        }
    }
}
