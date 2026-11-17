package com.hopa.app.di

import android.app.KeyguardManager
import android.os.PowerManager
import com.hopa.app.backend.RemoteScreenState
import com.hopa.app.backend.ScreenAccess
import com.hopa.capabilities.accessibility.AccessibilityState
import com.hopa.capabilities.accessibility.HopaAccessibilityService
import com.hopa.capabilities.device.UnavailableDevicePort
import com.hopa.capabilities.display.ShellDisplayDevice
import com.hopa.core.observation.ScreenPath
import com.hopa.core.observation.ScreenPathOrder
import com.hopa.core.observation.ScreenSource
import com.hopa.core.observation.ScreenSourceDevice
import com.hopa.data.settings.AppSettings
import kotlinx.coroutines.CancellationException

/**
 * Turns the person's `screenPath` setting into the ordered list of surfaces a
 * turn may use. Chat and remote runs share this so a delivered task is planned
 * against the same answer the chat screen shows.
 *
 * The background display is created here and released by the caller when the
 * turn ends, so an unseen run does not leave a virtual screen behind. A resolve
 * that is cancelled stops whatever it already started in the same [opened]
 * scope; nothing is hidden behind a `runCatching` that swallows cancellation.
 */
object Surfaces {
    @Volatile
    private var background: ShellDisplayDevice? = null

    suspend fun resolve(
        container: AppContainer,
        settings: AppSettings,
    ): ScreenSourceDevice {
        val bridged = attempt { container.bridgedDevice() }
        val preferred = ScreenPath.of(settings.screenPath)
        return opened(
            start = {
                if (preferred == ScreenPath.AUTO || preferred == ScreenPath.BACKGROUND) {
                    attempt { container.backgroundDevice() }
                } else {
                    null
                }
            },
            close = { device -> (device as? ShellDisplayDevice)?.stop() },
            body = { hidden ->
                val onscreen = ScreenAccess.available(
                    allowed = settings.accessibilityAllowed,
                    enabled = AccessibilityState.isEnabled(container.context),
                    bound = HopaAccessibilityService.instance != null,
                )
                // The order is a setting: the background display leaves the
                // person's screen alone, so AUTO tries it first, then the
                // bridge, then the screen.
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
                ScreenSourceDevice(
                    sources = sources,
                    fallback = UnavailableDevicePort(),
                )
            },
        )
    }

    /**
     * Opens one optional device and always closes it when the body never took
     * ownership. Cancellation is rethrown, never converted into a partial
     * surface, and the close runs in a `finally` so a cancel cannot leave a
     * started helper display behind.
     */
    internal suspend fun <T> opened(
        start: suspend () -> T?,
        close: (T) -> Unit,
        body: suspend (T?) -> ScreenSourceDevice,
    ): ScreenSourceDevice {
        val device = attempt { start() }
        var handedOff = false
        try {
            val result = body(device)
            handedOff = true
            return result
        } finally {
            if (!handedOff && device != null) {
                close(device)
            }
        }
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
        val enabled = AccessibilityState.isEnabled(container.context)
        val bound = HopaAccessibilityService.instance != null
        return RemoteScreenState(
            screenOn = power?.isInteractive == true,
            keyguardLocked = keyguard?.isKeyguardLocked == true,
            accessibilityOn = ScreenAccess.switchedOn(
                allowed = settings.accessibilityAllowed,
                enabled = enabled,
            ),
            accessibilityBound = ScreenAccess.available(
                allowed = settings.accessibilityAllowed,
                enabled = enabled,
                bound = bound,
            ),
        )
    }

    private suspend fun <T> attempt(block: suspend () -> T?): T? = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        null
    }
}
