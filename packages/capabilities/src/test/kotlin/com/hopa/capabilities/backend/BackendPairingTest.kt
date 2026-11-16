package com.hopa.capabilities.backend

import java.security.KeyFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPairingTest {
    @Test
    fun `claim sends the session and returns the tokens`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setResponseCode(201).setBody(
                    """
                    {"deviceId":"d1","householdId":"h1","accessToken":"a1","refreshToken":"r1",
                     "expiresAt":"2026-11-17T00:00:00Z"}
                    """.trimIndent(),
                ),
            )
            start()
        }
        try {
            val pairing = BackendPairing(gateway(server))
            val credentials = pairing.claim(
                link(server),
                name = "Xiaomi 15T Pro",
                publicKey = "pk",
                pushToken = "tok",
            )

            assertEquals("d1", credentials.deviceId)
            assertEquals("h1", credentials.householdId)
            assertEquals("a1", credentials.accessToken)
            assertEquals("r1", credentials.refreshToken)
            assertEquals("2026-11-17T00:00:00Z", credentials.expiresAt)
            assertEquals(gateway(server), credentials.gatewayUrl)

            val request = server.takeRequest()
            assertEquals("/v1/pair/claim", request.path)
            val body = request.body.readUtf8()
            assertTrue(body.contains("\"session\":\"s1\""))
            assertTrue(body.contains("\"platform\":\"android\""))
            assertTrue(body.contains("\"name\":\"Xiaomi 15T Pro\""))
            assertTrue(body.contains("\"publicKey\":\"pk\""))
            assertTrue(body.contains("\"alg\":\"x25519\""))
            assertTrue(body.contains("\"pushToken\":\"tok\""))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `refresh posts the refresh token and keeps the gateway`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setResponseCode(200).setBody(
                    """{"accessToken":"a2","refreshToken":"r2","expiresAt":"2026-11-17T00:15:00Z"}""",
                ),
            )
            start()
        }
        try {
            val credentials = BackendPairing(gateway(server)).refresh("r1")

            assertEquals("a2", credentials.accessToken)
            assertEquals("r2", credentials.refreshToken)
            assertEquals("2026-11-17T00:15:00Z", credentials.expiresAt)
            assertEquals(gateway(server), credentials.gatewayUrl)

            val request = server.takeRequest()
            assertEquals("/v1/token/refresh", request.path)
            assertEquals("""{"refreshToken":"r1"}""", request.body.readUtf8())
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a refused claim throws with the server message`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setResponseCode(400)
                    .setBody("""{"code":"E_SCHEMA","message":"expired"}"""),
            )
            start()
        }
        try {
            val pairing = BackendPairing(gateway(server))
            val message = try {
                pairing.claim(link(server), name = "Xiaomi 15T Pro", publicKey = "pk", pushToken = null)
                "no error"
            } catch (error: IllegalStateException) {
                error.message.orEmpty()
            }

            assertEquals("pairing refused: E_SCHEMA expired", message)
            assertFalse(message.contains("s1"))
            val body = server.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"session\":\"s1\""))
            assertFalse(body.contains("pushToken"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a refused refresh never prints the refresh token`() = runBlocking {
        val server = MockWebServer().apply {
            enqueue(
                MockResponse().setResponseCode(401)
                    .setBody("""{"code":"E_AUTH","message":"bad r1"}"""),
            )
            start()
        }
        try {
            val message = try {
                BackendPairing(gateway(server)).refresh("r1")
                "no error"
            } catch (error: IllegalStateException) {
                error.message.orEmpty()
            }

            assertEquals("pairing refused: E_AUTH bad <redacted>", message)
            assertFalse(message.contains("r1"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an x25519 public key is thirty two bytes and a private key round-trips`() {
        val pair = X25519Keys.generate()
        assertEquals(32, X25519Keys.publicKeyBytes(pair.public).size)

        val encoded = X25519Keys.privateKeyBase64(pair.`private`)
        assertTrue(encoded.isNotBlank())
        val rebuilt = KeyFactory.getInstance("X25519")
            .generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)))
        assertEquals(pair.`private`.encoded.toList(), rebuilt.encoded.toList())
    }

    private fun link(server: MockWebServer) = PairingLink(
        householdId = "h1",
        session = "s1",
        gatewayUrl = gateway(server),
    )

    /** MockWebServer speaks plain HTTP, so the socket scheme is `ws`; the
     *  `wss` to `https` mapping is pinned by PairingLinksTest. */
    private fun gateway(server: MockWebServer) =
        "ws://${server.hostName}:${server.port}/v1/gateway"
}
