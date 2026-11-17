package com.hopa.app.backend

import com.hopa.capabilities.backend.BackendCredentials
import com.hopa.capabilities.backend.BackendPairing
import com.hopa.capabilities.backend.PairingLink
import com.hopa.capabilities.backend.X25519Keys
import com.hopa.data.settings.SettingsRepository
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * The stored gateway identity: claim a pairing, read what is saved and rotate
 * the tokens. Pairing writes only after the claim succeeds, so a refused or
 * unreachable gateway leaves the previous device exactly as it was.
 */
internal class BackendIdentity(
    private val settings: SettingsRepository,
    private val pairingFactory: (String) -> BackendPairing,
) {
    suspend fun claim(
        link: PairingLink,
        deviceName: String,
        pushToken: String?,
    ): BackendCredentials {
        val keys = X25519Keys.generate()
        val publicKey = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(X25519Keys.publicKeyBytes(keys.public))
        val credentials = pairingFactory(link.gatewayUrl).claim(
            link = link,
            name = deviceName,
            publicKey = publicKey,
            pushToken = pushToken,
        )
        persist(credentials, X25519Keys.privateKeyBase64(keys.private))
        return credentials
    }

    suspend fun stored(): BackendCredentials? {
        val current = settings.settings.first()
        if (current.backendDeviceId.isBlank() || current.backendUrl.isBlank()) {
            return null
        }
        val access = settings.backendAccessToken()
        val refresh = settings.backendRefreshToken()
        if (access.isBlank() || refresh.isBlank()) {
            return null
        }
        return BackendCredentials(
            deviceId = current.backendDeviceId,
            householdId = current.backendHouseholdId,
            accessToken = access,
            refreshToken = refresh,
            expiresAt = "",
            gatewayUrl = current.backendUrl,
        )
    }

    /** A refresh carries new tokens only; the stored identity fills the blanks. */
    suspend fun refresh(): BackendCredentials? {
        val saved = settings.backendRefreshToken()
        if (saved.isBlank()) {
            return null
        }
        val current = settings.settings.first()
        if (current.backendUrl.isBlank()) {
            return null
        }
        val refreshed = try {
            pairingFactory(current.backendUrl).refresh(saved)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            return null
        }
        val merged = refreshed.copy(
            deviceId = refreshed.deviceId.ifBlank { current.backendDeviceId },
            householdId = refreshed.householdId.ifBlank { current.backendHouseholdId },
            gatewayUrl = current.backendUrl,
        )
        settings.setBackendAccessToken(merged.accessToken)
        settings.setBackendRefreshToken(merged.refreshToken)
        return merged
    }

    private suspend fun persist(credentials: BackendCredentials, privateKey: String) {
        settings.update {
            it.copy(
                backendUrl = credentials.gatewayUrl,
                backendDeviceId = credentials.deviceId,
                backendHouseholdId = credentials.householdId,
            )
        }
        settings.setBackendAccessToken(credentials.accessToken)
        settings.setBackendRefreshToken(credentials.refreshToken)
        settings.setBackendPrivateKey(privateKey)
    }
}
