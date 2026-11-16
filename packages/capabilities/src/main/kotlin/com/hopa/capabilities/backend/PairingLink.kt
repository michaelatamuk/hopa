package com.hopa.capabilities.backend

/**
 * The three values a pairing QR carries: the household it joins, the
 * single-use session, and the gateway socket the phone will connect to.
 */
data class PairingLink(
    val householdId: String,
    val session: String,
    val gatewayUrl: String,
)
