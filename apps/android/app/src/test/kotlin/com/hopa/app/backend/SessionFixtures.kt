package com.hopa.app.backend

import com.hopa.capabilities.backend.BackendCredentials
import com.hopa.capabilities.backend.BackendEnvelope
import com.hopa.capabilities.backend.BackendLink
import com.hopa.capabilities.backend.BackendPairing
import com.hopa.capabilities.backend.BackendState
import com.hopa.data.settings.AppSettings
import com.hopa.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer

/** Fakes and small helpers shared by [BackendSessionTest]. */
internal object SessionFixtures {
    fun session(
        settings: SettingsRepository,
        pairing: (String) -> BackendPairing = { BackendPairing(it) },
        backgroundGraceMs: Long = 30_000L,
        wakeIdleMs: Long = 45_000L,
        linkFactory: (
            BackendCredentials,
            String,
            String,
            () -> String?,
            suspend () -> BackendCredentials?,
        ) -> BackendLink = { credentials, _, _, pushToken, refresh ->
            FakeLink(credentials, pushToken, refresh)
        },
    ) = BackendSession(
        settings = settings,
        pairingFactory = pairing,
        linkFactory = linkFactory,
        deviceName = "Xiaomi",
        appVersion = "0.1.0",
        backgroundGraceMs = backgroundGraceMs,
        wakeIdleMs = wakeIdleMs,
    )

    fun pairingServer(vararg responses: MockResponse): MockWebServer =
        MockWebServer().apply {
            responses.forEach(::enqueue)
            start()
        }

    fun gateway(server: MockWebServer): String = server.url("/v1/gateway").toString()

    fun claimResponse(): String =
        """{"deviceId":"d1","householdId":"h1","accessToken":"a1","refreshToken":"r1",
           "expiresAt":"2026-11-17T00:00:00Z"}""".trimIndent()

    class FakeLink(
        val credentials: BackendCredentials,
        val pushToken: () -> String?,
        val refresh: suspend () -> BackendCredentials?,
        private val queued: Int = 0,
    ) : BackendLink {
        override val state = MutableStateFlow<BackendState>(BackendState.Disconnected)
        override var onTask: (suspend (BackendEnvelope) -> Unit)? = null

        @Volatile
        var started = false

        @Volatile
        var stopped = false

        override fun start(scope: CoroutineScope) {
            started = true
            state.value = BackendState.Live(credentials.deviceId, queued = queued, peers = emptyList())
        }

        override suspend fun stop() {
            stopped = true
            state.value = BackendState.Disconnected
        }

        override suspend fun send(frame: BackendEnvelope) = Unit
        override suspend fun ack(task: BackendEnvelope) = Unit
        override suspend fun ping() = Unit
    }

    class FakeSettings : SettingsRepository {
        private val _settings = MutableStateFlow(AppSettings())
        override val settings: StateFlow<AppSettings> = _settings
        val snapshot: AppSettings get() = _settings.value
        private var access = ""
        private var refresh = ""
        private var privateKey = ""

        fun seed(
            url: String,
            device: String,
            household: String,
            accessToken: String,
            refreshToken: String,
            privateKey: String,
        ) {
            _settings.value = _settings.value.copy(
                backendUrl = url,
                backendDeviceId = device,
                backendHouseholdId = household,
            )
            access = accessToken
            refresh = refreshToken
            this.privateKey = privateKey
        }

        fun paired(url: String): FakeSettings = apply {
            seed(url, "d1", "h1", "a1", "r1", "key")
        }

        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            _settings.value = transform(_settings.value)
        }

        override suspend fun modelKey(): String = ""
        override suspend fun hasModelKey(): Boolean = false
        override suspend fun setModelKey(value: String) = Unit
        override suspend fun bridgeToken(): String = ""
        override suspend fun setBridgeToken(value: String) = Unit
        override suspend fun bridgeRefresh(): String = ""
        override suspend fun setBridgeRefresh(value: String) = Unit
        override suspend fun backendAccessToken(): String = access
        override suspend fun setBackendAccessToken(value: String) { access = value }
        override suspend fun backendRefreshToken(): String = refresh
        override suspend fun setBackendRefreshToken(value: String) { refresh = value }
        override suspend fun backendPrivateKey(): String = privateKey
        override suspend fun setBackendPrivateKey(value: String) { privateKey = value }

        override suspend fun clearBackend() {
            access = ""
            refresh = ""
            privateKey = ""
            _settings.value = _settings.value.copy(
                backendUrl = "",
                backendDeviceId = "",
                backendHouseholdId = "",
            )
        }
    }
}
