package com.hopa.app.feature.runningapps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hopa.app.di.AppContainer
import com.hopa.app.feature.settings.SettingsViewModel
import com.hopa.app.feature.settings.SettingsViewModelFactory
import com.hopa.core.router.CatalogRoutes
import com.hopa.core.router.RouteTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The running-apps page shares the settings store, and lists the apps it can open. */
@Composable
fun RunningAppsRoute(
    container: AppContainer,
    onBack: () -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenAllApps: () -> Unit,
    onOpenScreenAccess: () -> Unit,
    onOpenComputer: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()
    var apps by remember { mutableStateOf(emptyList<RouteTarget>()) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Screen access is granted in Android settings and binds a moment later, so
    // the pill is re-read on resume and while this page stays open.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshScreenAccess()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2_000)
            viewModel.refreshScreenAccess()
        }
    }

    LaunchedEffect(Unit) {
        val installed = withContext(Dispatchers.IO) {
            runCatching { container.packages.installed() }.getOrDefault(emptyList())
        }
        apps = runCatching { CatalogRoutes.targets(installed, "android") }
            .getOrDefault(emptyList())
            .filter { it.kind == "app" && it.status == "ready" }
            .sortedBy { it.display.lowercase() }
    }

    RunningAppsScreen(
        state = state,
        apps = apps,
        onBack = onBack,
        onScreenPath = viewModel::setScreenPath,
        onStartBackgroundHelper = viewModel::startBackgroundHelper,
        onPairWireless = viewModel::pairWireless,
        onRunCheck = viewModel::runScreenCheck,
        onPlanFirst = viewModel::setPlanFirst,
        onVision = viewModel::setVision,
        onReplaySkills = viewModel::setReplaySkills,
        onOpenApp = onOpenApp,
        onOpenAllApps = onOpenAllApps,
        onOpenScreenAccess = onOpenScreenAccess,
        onOpenComputer = onOpenComputer,
    )
}
