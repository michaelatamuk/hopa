package com.hopa.data.settings

import kotlinx.coroutines.flow.Flow

interface SettingsRepository {
    val settings: Flow<AppSettings>

    suspend fun update(transform: (AppSettings) -> AppSettings)

    suspend fun modelKey(): String

    /** True when a model key is stored, even if it cannot be decrypted. */
    suspend fun hasModelKey(): Boolean

    suspend fun setModelKey(value: String)

    suspend fun bridgeToken(): String

    suspend fun setBridgeToken(value: String)

    suspend fun bridgeRefresh(): String

    suspend fun setBridgeRefresh(value: String)

    suspend fun backendAccessToken(): String

    suspend fun setBackendAccessToken(value: String)

    suspend fun backendRefreshToken(): String

    suspend fun setBackendRefreshToken(value: String)

    suspend fun backendPrivateKey(): String

    suspend fun setBackendPrivateKey(value: String)

    /** Forget the gateway pairing: tokens and key, plus the identity fields. */
    suspend fun clearBackend()
}
