package com.hopa.capabilities.backend

/**
 * What a claimed pairing leaves the phone with. The gateway URL travels with
 * the tokens so a reconnect never has to read the pairing text again.
 */
data class BackendCredentials(
    val deviceId: String,
    val householdId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: String,
    val gatewayUrl: String,
)
