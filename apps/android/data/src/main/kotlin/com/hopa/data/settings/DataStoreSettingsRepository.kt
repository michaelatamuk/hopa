package com.hopa.data.settings

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.hopa.data.secrets.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "hopa_settings")

class DataStoreSettingsRepository(
    private val context: Context,
    private val secrets: SecretStore,
) : SettingsRepository {
    override val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            provider = prefs[PROVIDER] ?: "stub",
            endpoint = prefs[ENDPOINT] ?: "https://api.deepseek.com/v1",
            model = prefs[MODEL] ?: "deepseek-v4-flash",
            profile = prefs[PROFILE] ?: "everyday",
            notifyAnswers = prefs[NOTIFY] ?: true,
            webEnabled = prefs[WEB] ?: true,
            selectedConversation = prefs[SELECTED] ?: "",
            bridgeUrl = prefs[BRIDGE_URL] ?: "",
            useBridge = prefs[USE_BRIDGE] ?: true,
            accessibilityAllowed = prefs[ACCESSIBILITY_ALLOWED] ?: true,
            tone = prefs[TONE] ?: "friendly",
            chattiness = prefs[CHATTINESS] ?: "balanced",
            screenPath = prefs[SCREEN_PATH] ?: "auto",
            planFirst = prefs[PLAN_FIRST] ?: false,
            replaySkills = prefs[REPLAY_SKILLS] ?: true,
            vision = prefs[VISION] ?: false,
            trustedApps = prefs[TRUSTED_APPS] ?: emptySet(),
            backendUrl = prefs[BACKEND_URL] ?: "",
            backendDeviceId = prefs[BACKEND_DEVICE_ID] ?: "",
            backendHouseholdId = prefs[BACKEND_HOUSEHOLD_ID] ?: "",
        )
    }

    override suspend fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(settings.first())
        context.settingsDataStore.edit { prefs ->
            prefs[PROVIDER] = next.provider
            prefs[ENDPOINT] = next.endpoint
            prefs[MODEL] = next.model
            prefs[PROFILE] = next.profile
            prefs[NOTIFY] = next.notifyAnswers
            prefs[WEB] = next.webEnabled
            prefs[SELECTED] = next.selectedConversation
            prefs[BRIDGE_URL] = next.bridgeUrl
            prefs[USE_BRIDGE] = next.useBridge
            prefs[ACCESSIBILITY_ALLOWED] = next.accessibilityAllowed
            prefs[TONE] = next.tone
            prefs[CHATTINESS] = next.chattiness
            prefs[SCREEN_PATH] = next.screenPath
            prefs[PLAN_FIRST] = next.planFirst
            prefs[REPLAY_SKILLS] = next.replaySkills
            prefs[VISION] = next.vision
            prefs[TRUSTED_APPS] = next.trustedApps
            prefs[BACKEND_URL] = next.backendUrl
            prefs[BACKEND_DEVICE_ID] = next.backendDeviceId
            prefs[BACKEND_HOUSEHOLD_ID] = next.backendHouseholdId
        }
    }

    override suspend fun modelKey(): String = secrets.get(MODEL_KEY)

    override suspend fun hasModelKey(): Boolean = secrets.present(MODEL_KEY)

    override suspend fun setModelKey(value: String) = secrets.set(MODEL_KEY, value)

    override suspend fun bridgeToken(): String = secrets.get(BRIDGE_TOKEN)

    override suspend fun setBridgeToken(value: String) = secrets.set(BRIDGE_TOKEN, value)

    override suspend fun bridgeRefresh(): String = secrets.get(BRIDGE_REFRESH)

    override suspend fun setBridgeRefresh(value: String) = secrets.set(BRIDGE_REFRESH, value)

    override suspend fun backendAccessToken(): String = secrets.get(BACKEND_ACCESS)

    override suspend fun setBackendAccessToken(value: String) {
        if (value.isBlank()) secrets.clear(BACKEND_ACCESS) else secrets.set(BACKEND_ACCESS, value)
    }

    override suspend fun backendRefreshToken(): String = secrets.get(BACKEND_REFRESH)

    override suspend fun setBackendRefreshToken(value: String) {
        if (value.isBlank()) secrets.clear(BACKEND_REFRESH) else secrets.set(BACKEND_REFRESH, value)
    }

    override suspend fun backendPrivateKey(): String = secrets.get(BACKEND_PRIVATE_KEY)

    override suspend fun setBackendPrivateKey(value: String) {
        if (value.isBlank()) secrets.clear(BACKEND_PRIVATE_KEY) else secrets.set(BACKEND_PRIVATE_KEY, value)
    }

    override suspend fun clearBackend() {
        secrets.clear(BACKEND_ACCESS)
        secrets.clear(BACKEND_REFRESH)
        secrets.clear(BACKEND_PRIVATE_KEY)
        update { it.copy(backendUrl = "", backendDeviceId = "", backendHouseholdId = "") }
    }

    private companion object {
        val PROVIDER = stringPreferencesKey("provider")
        val ENDPOINT = stringPreferencesKey("endpoint")
        val MODEL = stringPreferencesKey("model")
        val PROFILE = stringPreferencesKey("profile")
        val NOTIFY = booleanPreferencesKey("notify_answers")
        val WEB = booleanPreferencesKey("web_enabled")
        val SELECTED = stringPreferencesKey("selected_conversation")
        val BRIDGE_URL = stringPreferencesKey("bridge_url")
        val USE_BRIDGE = booleanPreferencesKey("use_bridge")
        val ACCESSIBILITY_ALLOWED = booleanPreferencesKey("accessibility_allowed")
        val TONE = stringPreferencesKey("tone")
        val CHATTINESS = stringPreferencesKey("chattiness")
        val SCREEN_PATH = stringPreferencesKey("screen_path")
        val PLAN_FIRST = booleanPreferencesKey("plan_first")
        val REPLAY_SKILLS = booleanPreferencesKey("replay_skills")
        val VISION = booleanPreferencesKey("vision")
        val TRUSTED_APPS = stringSetPreferencesKey("trusted_apps")
        val BACKEND_URL = stringPreferencesKey("backend_url")
        val BACKEND_DEVICE_ID = stringPreferencesKey("backend_device_id")
        val BACKEND_HOUSEHOLD_ID = stringPreferencesKey("backend_household_id")
        const val MODEL_KEY = "model_key"
        const val BRIDGE_TOKEN = "bridge_token"
        const val BRIDGE_REFRESH = "bridge_refresh"
        const val BACKEND_ACCESS = "backend_access"
        const val BACKEND_REFRESH = "backend_refresh"
        const val BACKEND_PRIVATE_KEY = "backend_private_key"
    }
}
