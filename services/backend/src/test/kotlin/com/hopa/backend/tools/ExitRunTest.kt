package com.hopa.backend.tools

import com.hopa.backend.ServerConfig
import com.hopa.backend.harness.PostgresHarness
import com.hopa.backend.module
import com.hopa.backend.pairing.ClaimRequest
import com.hopa.backend.pairing.PairingOutcome
import com.hopa.backend.pairing.PairingService
import com.hopa.backend.pairing.PgAuth
import com.hopa.backend.pairing.TokenIssuer
import com.hopa.backend.store.PgDevices
import com.hopa.backend.store.PgTaskStore
import com.hopa.backend.store.PostgresProbe
import com.hopa.backend.trace.PgTraceStore
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import java.util.Base64
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ExitRunTest {
    private data class Paired(val householdId: String, val access: String)

    private fun pair(pairing: PairingService, name: String, householdId: String?): Paired {
        val minted = pairing.mint("Exit", name, "127.0.0.1", householdId)
        check(minted is PairingOutcome.Ok) { "mint failed: $minted" }
        val claimed = pairing.claim(
            ClaimRequest(
                session = minted.value.session,
                platform = "android",
                name = name,
                publicKey = Base64.getEncoder().encodeToString(ByteArray(32) { 6 }),
                alg = "x25519",
            ),
            "127.0.0.1",
        )
        check(claimed is PairingOutcome.Ok) { "claim failed: $claimed" }
        return Paired(claimed.value.householdId, claimed.value.accessToken)
    }

    @Test
    fun `twenty round-trips run twice over a real socket stay clean`() {
        val db = PostgresHarness.appDatabase()
        val clock = AtomicLong(1_763_212_800_000)
        val auth = PgAuth(db, clock = clock::get)
        val tokens = TokenIssuer(db, clock = clock::get, onRevoke = auth::revoke)
        val pairing = PairingService(db, clock = clock::get, tokens = tokens)
        val traces = PgTraceStore(db, clock::get)
        val config = ServerConfig(
            version = "0.1.0",
            auth = auth,
            devices = PgDevices(db),
            db = PostgresProbe(db),
            pairing = pairing,
            bootstrapToken = "boot-secret",
            tokenIssuer = tokens,
            tasks = PgTaskStore(db, clock::get),
            traces = traces,
            traceQuery = traces,
        )
        val server = embeddedServer(Netty, port = 0) {
            module(config)
        }.start(wait = false)

        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            val a = pair(pairing, "A", null)
            val b = pair(pairing, "B", a.householdId)

            val first = ExitRun.run(
                url = "ws://127.0.0.1:$port/v1/gateway",
                tokenA = a.access,
                tokenB = b.access,
                rounds = 20,
            )
            // A second run must not see the first run's tasks again: every one
            // was acked, so nothing is left queued or delivered.
            val second = ExitRun.run(
                url = "ws://127.0.0.1:$port/v1/gateway",
                tokenA = a.access,
                tokenB = b.access,
                rounds = 20,
            )

            assertEquals(20, first.size)
            assertEquals(20, first.toSet().size)
            assertEquals(20, second.size)
            assertEquals(20, second.toSet().size)
            assertEquals(40, (first + second).toSet().size)
        } finally {
            server.stop(0, 0)
        }
    }
}
