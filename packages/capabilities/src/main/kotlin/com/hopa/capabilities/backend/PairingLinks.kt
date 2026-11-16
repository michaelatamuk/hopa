package com.hopa.capabilities.backend

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

/**
 * Parses the text a person pastes or scans: the QR's
 * `hopa://pair?v=1&h=<household>&p=<session>&u=<gateway>` in any parameter
 * order or percent-encoding, or a bare session when the gateway is already
 * stored in settings. Anything else is refused rather than guessed at.
 */
object PairingLinks {
    fun parse(text: String, fallbackGatewayUrl: String = ""): PairingLink? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val fallback = fallbackGatewayUrl.trim()
        if (!trimmed.startsWith(URI_PREFIX, ignoreCase = true)) {
            return bareSession(trimmed, fallback)
        }
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.scheme?.equals(SCHEME, ignoreCase = true) != true) return null
        if (uri.host?.equals(PAIR_HOST, ignoreCase = true) != true) return null
        val query = parameters(uri.rawQuery ?: return null)
        val session = query["p"]?.takeIf { it.isNotBlank() } ?: return null
        val gateway = query["u"]?.takeIf { it.isNotBlank() } ?: fallback
        if (gateway.isEmpty()) return null
        return PairingLink(
            householdId = query["h"].orEmpty(),
            session = session,
            gatewayUrl = gateway,
        )
    }

    /** A bare session only pairs when a gateway is configured to talk to. */
    private fun bareSession(text: String, fallback: String): PairingLink? {
        if (fallback.isEmpty() || !SESSION.matches(text)) return null
        return PairingLink(householdId = "", session = text, gatewayUrl = fallback)
    }

    private fun parameters(rawQuery: String): Map<String, String> = rawQuery
        .split('&')
        .mapNotNull { entry ->
            val separator = entry.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            decode(entry.substring(0, separator)) to decode(entry.substring(separator + 1))
        }
        .toMap()

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8) }.getOrDefault(value)

    private const val URI_PREFIX = "hopa://"
    private const val SCHEME = "hopa"
    private const val PAIR_HOST = "pair"
    private val SESSION = Regex("[A-Za-z0-9._-]{1,128}")
}
