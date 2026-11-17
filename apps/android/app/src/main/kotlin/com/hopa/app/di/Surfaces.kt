package com.hopa.app.di

import android.app.KeyguardManager
import android.os.PowerManager
import com.hopa.app.backend.RemoteScreenState
import com.hopa.capabilities.accessibility.AccessibilityState
import com.hopa.capabilities.accessibility.HopaAccessibilityService
import com.hopa.capabilities.device.UnavailableDevicePort
import com.hopa.capabilities.display.ShellDisplayDevice
import com.hopa.core.observation.ScreenPath
import com.hopa.core.observation.ScreenPathOrder
import com.hopa.core.observation.ScreenSource
import com.hopa.core.observation.ScreenSourceDevice
import com.hopa.data.settings.AppSettings

/**
 * Turns the person's `screenPath` setting into the ordered list of surfaces a
 * turn may use. Chat and remote runs share this so a delivered task is planned
 * against the same answer the chat screen shows.
 *
 * The background display is created here and released by the caller when the
 * turn ends, so an unseen run does not leave a virtual screen behind.
 */
object Surfaces {
    @Volatile
    private var background: ShellDisplayDevice? = null

    suspend fun resolve(
        container: AppContainer,
        settings: AppSettings,
    ): ScreenSourceDevice {
        val bridged = runCatching { container.bridgedDevice() }.getOrNull()
        val preferred = ScreenPath.of(settings.screenPath)
        val hidden = if (
            preferred == ScreenPath.AUTO || preferred == ScreenPath.BACKGROUND
        ) {
            runCatching { container.backgroundDevice() }.getOrNull()
        } else {
            null
        }
        val onscreen = settings.accessibilityAllowed &&
            HopaAccessibilityService.instance != null
        // The order is a setting: the background display leaves the person's
        // screen alone, so AUTO tries it first, then the bridge, then the screen.
        val ordered = ScreenPathOrder().order(
            preferred = preferred,
            background = hidden != null,
            bridge = bridged != null,
            onscreen = onscreen,
        )
        val sources = ordered.mapNotNull { name ->
            when (name) {
                "background" -> ScreenSource("background", hidden!!, budgetMs = 8_000)
                "bridge" -> ScreenSource("bridge", bridged!!, budgetMs = 15_000)
                else -> ScreenSource("accessibility", container.device, budgetMs = 8_000)
            }
        }
        background = hidden as? ShellDisplayDevice
        return ScreenSourceDevice(
            sources = sources,
            fallback = UnavailableDevicePort(),
        )
    }

    /** Close the hidden display this helper opened, if any. */
    fun release() {
        background?.stop()
        background = null
    }

    /** The visible screen's state, for the remote planner. */
    fun state(container: AppContainer, settings: AppSettings): RemoteScreenState {
        val power = container.context.getSystemService(PowerManager::class.java)
        val keyguard = container.context.getSystemService(KeyguardManager::class.java)
        return RemoteScreenState(
            screenOn = power?.isInteractive == true,
            keyguardLocked = keyguard?.isKeyguardLocked == true,
            accessibilityOn = settings.accessibilityAllowed &&
                AccessibilityState.isEnabled(container.context),
        )
    }
}
