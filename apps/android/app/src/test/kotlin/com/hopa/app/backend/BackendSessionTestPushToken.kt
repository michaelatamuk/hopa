package com.hopa.app.backend

import com.hopa.app.backend.SessionFixtures.FakeLink
import com.hopa.app.backend.SessionFixtures.FakeSettings
import com.hopa.app.backend.SessionFixtures.session
import com.hopa.capabilities.backend.BackendState
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** A fresh install must ask FCM for the token before the first hello. */
class BackendSessionTestPushToken {
    @Test
    fun `foreground asks for a token only while none is held`() = runBlocking {
        val settings = FakeSettings().paired("wss://x/v1/gateway")
        val asks = AtomicInteger()
        val created = Collections.synchronizedList(mutableListOf<FakeLink>())
        val session = session(
            settings,
            pushTokenFetch = {
                asks.incrementAndGet()
                FirebaseToken.set("fetched")
                true
            },
            linkFactory = { credentials, _, _, pushToken, refresh ->
                FakeLink(credentials, pushToken, refresh).also { created += it }
            },
        )
        try {
            FirebaseToken.set(null)
            session.onForeground()
            withTimeout(2_000) { while (created.size < 1 || !created[0].started) delay(20) }
            withTimeout(2_000) { while (FirebaseToken.current() == null) delay(20) }
            session.onForeground()
            delay(150)

            assertEquals(1, asks.get())
            assertEquals("fetched", created[0].pushToken())
        } finally {
            FirebaseToken.set(null)
            session.disconnect()
        }
    }

    @Test
    fun `a token that lands after the hello asks for a new one`() = runBlocking {
        val settings = FakeSettings().paired("wss://x/v1/gateway")
        val gate = CompletableDeferred<Unit>()
        val created = Collections.synchronizedList(mutableListOf<FakeLink>())
        val session = session(
            settings,
            pushTokenFetch = {
                gate.await()
                FirebaseToken.set("late")
                true
            },
            linkFactory = { credentials, _, _, pushToken, refresh ->
                FakeLink(credentials, pushToken, refresh).also { created += it }
            },
        )
        try {
            FirebaseToken.set(null)
            session.onForeground()
            withTimeout(2_000) { while (created.size < 1 || !created[0].started) delay(20) }
            withTimeout(2_000) {
                while (session.state.value !is BackendState.Live) delay(20)
            }

            gate.complete(Unit)
            withTimeout(2_000) { while (created.size < 2 || !created[1].started) delay(20) }

            assertEquals("late", created[1].pushToken())
        } finally {
            FirebaseToken.set(null)
            session.disconnect()
        }
    }
}
