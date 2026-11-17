package com.hopa.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.hopa.app.di.AppContainer
import com.hopa.app.service.ScreenCaptureRequester
import com.hopa.app.service.ScreenCaptureService
import com.hopa.capabilities.bridge.BridgeClient
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Screen-capture consent (Android 10 screenshots). Only an Activity can ask;
    // on OK the token goes to the foreground service that holds the projection.
    private val screenCapture = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data)
        }
    }

    override fun onStart() {
        super.onStart()
        com.hopa.app.service.AppVisibility.foreground = true
        (application as HopaApplication).container.backend.onForeground()
        ScreenCaptureRequester.launch = {
            val manager = getSystemService(MediaProjectionManager::class.java)
            runCatching { screenCapture.launch(manager.createScreenCaptureIntent()) }
        }
    }

    override fun onStop() {
        com.hopa.app.service.AppVisibility.foreground = false
        (application as HopaApplication).container.backend.onBackground()
        ScreenCaptureRequester.launch = null
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as HopaApplication).container
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 41)
        }
        // A launch pairing claims a one-shot session, so it runs only on a fresh
        // launch (not a rotation) and on the container's scope, which outlives
        // this Activity.
        applyLaunchConfig(intent, container, pairBackend = savedInstanceState == null)
        // A cold-start share must copy its file too; a rotation must not copy it
        // again, because the container already holds the pending attachment.
        if (savedInstanceState == null) {
            intakeFrom(intent, container)
        }
        val goal = goalFrom(intent)
        setContent {
            HopaApp(container = container, initialGoal = goal)
        }
    }

    /**
     * `hopa://ask?text=...`, `ACTION_PROCESS_TEXT` and `ACTION_SEND` drop the
     * words into the composer; nothing is sent without a tap.
     */
    private fun goalFrom(intent: Intent?): String {
        if (intent == null) {
            return ""
        }
        if (intent.action == Intent.ACTION_PROCESS_TEXT) {
            return intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString().orEmpty()
        }
        if (intent.action == Intent.ACTION_SEND) {
            return intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        }
        val data = intent.data ?: return ""
        return if (data.scheme == "hopa" && data.host == "ask") {
            data.getQueryParameter("text").orEmpty()
        } else {
            ""
        }
    }

    /** A shared file waits in the composer; its bytes are copied first. */
    private fun intakeFrom(intent: Intent, container: AppContainer) {
        if (intent.action != Intent.ACTION_SEND) {
            return
        }
        val uri = streamFrom(intent) ?: return
        if (intent.type?.startsWith("text/") == true) {
            return
        }
        lifecycleScope.launch {
            val previous = container.pendingAttachment.value
            val result = container.intake.accept(uri, hint = intent.type)
            if (previous != null && previous.path != result.attachment?.path) {
                container.intake.delete(previous)
            }
            container.pendingNotice.value = result.notice
            container.pendingAttachment.value = result.attachment
        }
    }

    @Suppress("DEPRECATION")
    private fun streamFrom(intent: Intent): Uri? = intent.getParcelableExtra(Intent.EXTRA_STREAM)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val container = (application as HopaApplication).container
        applyLaunchConfig(intent, container)
        if (streamFrom(intent) == null) {
            val goal = goalFrom(intent)
            if (goal.isNotBlank()) {
                container.pendingGoal.value = goal
            }
        }
        intakeFrom(intent, container)
    }

    /**
     * Automation hooks for E2E: `--es hopa.bridgeUrl ...` (plus an optional
     * token or pair code) configures the bridge without tapping the settings
     * form. `hopa.backendUrl` / `hopa.backendPair` pair the gateway the same
     * way. Nothing happens when the extras are absent.
     */
    private fun applyLaunchConfig(
        intent: Intent,
        container: AppContainer,
        pairBackend: Boolean = true,
    ) {
        // The gateway is handled before the bridge's early return: a device
        // test may pair the backend while leaving the PC bridge untouched.
        val backendName = intent.getStringExtra(EXTRA_BACKEND_NAME)
        if (!backendName.isNullOrBlank()) {
            com.hopa.app.backend.BackendLaunchName.value = backendName.trim()
        }
        if (pairBackend) {
            val backendUrl = intent.getStringExtra(EXTRA_BACKEND_URL)
            val backendPair = intent.getStringExtra(EXTRA_BACKEND_PAIR)
            if (!backendUrl.isNullOrBlank() || !backendPair.isNullOrBlank()) {
                // The claim runs off the Activity so a rotation cannot cancel a
                // POST the gateway has already accepted.
                container.appScope.launch {
                    LaunchPairing.apply(
                        backendUrl = backendUrl,
                        backendPair = backendPair,
                        notice = container.backendNotice,
                        storeUrl = { url ->
                            container.settings.update { it.copy(backendUrl = url) }
                        },
                        claim = { link -> container.backend.pair(link) },
                    )
                }
            }
        }
        val url = intent.getStringExtra(EXTRA_BRIDGE_URL)
        if (url.isNullOrBlank()) {
            return
        }
        lifecycleScope.launch {
            container.settings.update { it.copy(bridgeUrl = url.trim(), useBridge = true) }
            val token = intent.getStringExtra(EXTRA_BRIDGE_TOKEN)
            if (!token.isNullOrBlank()) {
                container.settings.setBridgeToken(token.trim())
            }
            val code = intent.getStringExtra(EXTRA_BRIDGE_PAIR)
            if (!code.isNullOrBlank()) {
                runCatching { BridgeClient(url, "").pair(code.trim()) }
                    .onSuccess { session ->
                        container.settings.setBridgeToken(session.token)
                        container.settings.setBridgeRefresh(session.refresh)
                    }
            }
        }
    }

    private companion object {
        const val EXTRA_BRIDGE_URL = "hopa.bridgeUrl"
        const val EXTRA_BRIDGE_TOKEN = "hopa.bridgeToken"
        const val EXTRA_BRIDGE_PAIR = "hopa.bridgePair"
        const val EXTRA_BACKEND_URL = "hopa.backendUrl"
        const val EXTRA_BACKEND_PAIR = "hopa.backendPair"
        const val EXTRA_BACKEND_NAME = "hopa.backendName"
    }
}
