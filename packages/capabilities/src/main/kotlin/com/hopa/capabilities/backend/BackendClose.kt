package com.hopa.capabilities.backend

/**
 * Why one socket ended. Only a `1008 E_AUTH` close earns a refresh; every other
 * close is retried with backoff. The message says what the person can see.
 */
internal class BackendClose(val code: Int, val reason: String, val message: String) {
    val auth: Boolean get() = code == AUTH_CLOSE && reason == AUTH_REASON

    private companion object {
        const val AUTH_CLOSE = 1008
        const val AUTH_REASON = "E_AUTH"
    }
}
