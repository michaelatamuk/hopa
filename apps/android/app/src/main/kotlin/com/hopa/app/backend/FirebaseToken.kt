package com.hopa.app.backend

/**
 * The Firebase registration token, held in memory and filled by the messaging
 * service. It is handed to `device.hello`; it is never logged.
 */
object FirebaseToken {
    @Volatile
    private var token: String? = null

    fun current(): String? = token?.takeIf { it.isNotBlank() }

    fun set(value: String?) {
        token = value?.takeIf { it.isNotBlank() }
    }
}
