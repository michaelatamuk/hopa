package com.hopa.app.backend

import com.google.firebase.messaging.FirebaseMessaging
import kotlin.coroutines.resume
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * The one on-demand read of the FCM registration token. A fresh install has none
 * until something asks FCM for one, so the app asks on start; the result lives
 * in [FirebaseToken] and is never logged.
 */
internal object PushTokenFetch {
    /**
     * Fetches the token and stores it in [FirebaseToken]. Returns true when a
     * token is held afterwards. A refusal, or Firebase not being initialized, is
     * a quiet false: a missing token only means the gateway cannot wake us.
     */
    suspend fun load(): Boolean {
        val fetched = try {
            await()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        if (fetched != null) {
            FirebaseToken.set(fetched)
        }
        return FirebaseToken.current() != null
    }

    private suspend fun await(): String? = suspendCancellableCoroutine { done ->
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            done.resume(if (task.isSuccessful) task.result else null)
        }
    }
}
