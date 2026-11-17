package com.hopa.app.feature.settings

import com.hopa.app.backend.BackendTaskState
import com.hopa.capabilities.backend.BackendState
import com.hopa.capabilities.llm.ProviderState
import com.hopa.data.settings.AppSettings

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val key: String = "",
    /** True when a key is on disk, even if it cannot be decrypted. */
    val keySaved: Boolean = false,
    val bridgeKey: String = "",
    val providerState: ProviderState = ProviderState.DEMO,
    val busy: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
    val bridgeStatus: String? = null,
    val notice: String? = null,
    /** The last "Test the model" outcome, shown next to the button. */
    val modelTest: String? = null,
    /** Results of the screen-access self-check, each with its next action. */
    val screenCheck: List<ScreenCheck> = emptyList(),
    /** True when the accessibility service is enabled and bound now. */
    val phoneAccess: Boolean = false,
    /** The gateway this phone talks to, and the identity it paired as. */
    val backendUrl: String = "",
    val backendDeviceId: String = "",
    val backendState: BackendState = BackendState.Disconnected,
    /** The FCM registration, never shown whole. */
    val backendToken: String? = null,
    val fcmAvailable: Boolean = false,
    /** What the gateway last asked this phone to run. */
    val backendTask: BackendTaskState = BackendTaskState(),
    /** The label a launch config gave this phone, if any. */
    val backendName: String = "",
    /** The last pairing attempt's answer, shown under the card's buttons. */
    val backendStatus: String? = null,
)
