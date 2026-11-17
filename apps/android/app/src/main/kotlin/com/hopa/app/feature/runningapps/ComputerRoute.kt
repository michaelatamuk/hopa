package com.hopa.app.feature.runningapps

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.hopa.app.di.AppContainer
import com.hopa.app.feature.settings.SettingsViewModel
import com.hopa.app.feature.settings.SettingsViewModelFactory

/** The computer as a surface: address, pairing and the two checks. */
@Composable
fun ComputerRoute(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModelFactory(container))
    val state by viewModel.state.collectAsStateWithLifecycle()

    ComputerScreen(
        state = state,
        onBack = onBack,
        onSave = viewModel::save,
        onTestBridge = viewModel::testBridge,
        onPairBridge = viewModel::pairBridge,
        onPairBackend = viewModel::pair,
        onDisconnectBackend = viewModel::disconnectBackend,
        onRefreshBackend = viewModel::refresh,
    )
}
