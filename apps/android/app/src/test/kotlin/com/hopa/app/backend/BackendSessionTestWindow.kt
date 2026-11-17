package com.hopa.app.backend

import com.hopa.app.backend.SessionFixtures.FakeLink
import com.hopa.app.backend.SessionFixtures.FakeSettings
import com.hopa.app.backend.SessionFixtures.session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendSessionTestWindow {
    @Test
    fun `taskStarted cancels the background clock and taskFinished re-arms it`() = runBlocking {
        val created = CompletableDeferred<FakeLink>()
        val session = session(
            FakeSettings().paired("wss://x/v1/gateway"),
            backgroundGraceMs = 80,
            linkFactory = { credentials, _, _, pushToken, refresh ->
                FakeLink(credentials, pushToken, refresh).also { created.complete(it) }
            },
        )
        try {
            session.onForeground()
            val link = withTimeout(2_000) { created.await() }
            session.onBackground()
            session.taskStarted()
            delay(250)
            assertFalse(link.stopped)

            session.taskFinished()
            awaitStop(link)
            assertTrue(link.stopped)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `onBackground with a run in flight does not arm a close`() = runBlocking {
        val created = CompletableDeferred<FakeLink>()
        val session = session(
            FakeSettings().paired("wss://x/v1/gateway"),
            backgroundGraceMs = 80,
            linkFactory = { credentials, _, _, pushToken, refresh ->
                FakeLink(credentials, pushToken, refresh).also { created.complete(it) }
            },
        )
        try {
            session.onForeground()
            val link = withTimeout(2_000) { created.await() }
            session.taskStarted()
            session.onBackground()
            delay(250)
            assertFalse(link.stopped)

            session.taskFinished()
            awaitStop(link)
            assertTrue(link.stopped)
        } finally {
            session.disconnect()
        }
    }

    @Test
    fun `a wake with a queued task stays open until the run finishes`() = runBlocking {
        val created = CompletableDeferred<FakeLink>()
        val session = session(
            FakeSettings().paired("wss://x/v1/gateway"),
            wakeIdleMs = 80,
            linkFactory = { credentials, _, _, pushToken, refresh ->
                FakeLink(credentials, pushToken, refresh, queued = 3).also { created.complete(it) }
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            session.wake(scope)
            val link = withTimeout(2_000) { created.await() }
            delay(250)
            assertFalse(link.stopped)

            session.taskStarted()
            delay(50)
            session.taskFinished()
            awaitStop(link)
            assertTrue(link.stopped)
        } finally {
            session.disconnect()
            scope.cancel()
        }
    }

    @Test
    fun `an empty wake queue fires the idle clock`() = runBlocking {
        val created = CompletableDeferred<FakeLink>()
        val session = session(
            FakeSettings().paired("wss://x/v1/gateway"),
            wakeIdleMs = 80,
            linkFactory = { credentials, _, _, pushToken, refresh ->
                FakeLink(credentials, pushToken, refresh).also { created.complete(it) }
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            session.wake(scope)
            val link = withTimeout(2_000) { created.await() }

            awaitStop(link)
            assertTrue(link.stopped)
        } finally {
            session.disconnect()
            scope.cancel()
        }
    }

    private suspend fun awaitStop(link: FakeLink) {
        withTimeout(3_000) {
            while (!link.stopped) {
                delay(10)
            }
        }
    }
}
