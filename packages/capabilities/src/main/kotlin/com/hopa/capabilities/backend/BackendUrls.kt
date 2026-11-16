package com.hopa.capabilities.backend

import java.net.URI

/**
 * The HTTP side of the gateway's origin: pairing and token refresh are plain
 * HTTPS on the same host and port the WebSocket URL names.
 */
object BackendUrls {
    fun httpBase(gatewayUrl: String): String {
        val trimmed = gatewayUrl.trim()
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return trimmed.trimEnd('/')
        val scheme = when (uri.scheme?.lowercase()) {
            "wss", "https" -> "https"
            "ws", "http" -> "http"
            else -> return trimmed.trimEnd('/')
        }
        val host = uri.host?.takeIf { it.isNotBlank() } ?: return trimmed.trimEnd('/')
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://$host$port"
    }
}
