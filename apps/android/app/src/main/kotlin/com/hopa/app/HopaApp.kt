package com.hopa.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.hopa.app.di.AppContainer
import com.hopa.app.navigation.HopaNavHost
import com.hopa.app.ui.component.ApprovalSheet
import com.hopa.app.ui.theme.HopaTheme

@Composable
fun HopaApp(container: AppContainer, initialGoal: String = "") {
    val pending by container.approval.pending.collectAsStateWithLifecycle()
    HopaTheme {
        HopaNavHost(container = container, initialGoal = initialGoal)
        // One sheet for every tab: a remote run may ask while the person is on
        // Running apps or My computer, not only in chat.
        pending?.let { request ->
            ApprovalSheet(
                request = request,
                onDeny = { container.approval.resolve(false) },
                onAllow = { container.approval.resolve(true) },
            )
        }
    }
}
