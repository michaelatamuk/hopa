package com.hopa.capabilities.backend

/**
 * What the app shows about the gateway link. [Live] is only entered after a
 * `device.hello.ack`, so its counts are the server's answer, not a guess.
 */
sealed interface BackendState {
    data object Disconnected : BackendState
    data object Connecting : BackendState
    data class Live(val deviceId: String, val queued: Int, val peers: List<String>) : BackendState
    data class Failed(val reason: String, val needsPairing: Boolean) : BackendState
}
