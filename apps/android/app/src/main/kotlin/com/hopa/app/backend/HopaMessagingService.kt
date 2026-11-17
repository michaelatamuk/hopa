package com.hopa.app.backend

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.hopa.app.HopaApplication
import com.hopa.app.service.AppVisibility
import com.hopa.app.service.Notifier
import com.hopa.capabilities.backend.BackendState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The way in while HOPA is asleep. A data-only message is read for its task id
 * and nothing else: the payload never reaches a log, a notification or a screen.
 */
class HopaMessagingService : FirebaseMessagingService() {
    override fun onMessageReceived(message: RemoteMessage) {
        if (WakeRequests.from(message.data) == null) {
            return
        }
        val container = (application as HopaApplication).container
        if (!AppVisibility.foreground) {
            Notifier.postWake(this)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        container.backend.wake(scope)
        // The session keeps its own socket; this bounds only the wake attempt.
        scope.launch {
            delay(WAKE_WINDOW_MS)
            scope.cancel()
        }
    }

    override fun onNewToken(token: String) {
        FirebaseToken.set(token)
        val backend = (application as HopaApplication).container.backend
        if (backend.state.value is BackendState.Live) {
            backend.reconnect()
        }
    }

    private companion object {
        const val WAKE_WINDOW_MS = 50_000L
    }
}
