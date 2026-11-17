package com.hopa.app.feature.runningapps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hopa.app.feature.settings.SettingsUiState
import com.hopa.app.ui.component.HopaPageHeader
import com.hopa.app.ui.component.HopaStatusPill
import com.hopa.app.ui.theme.HopaColor

/**
 * The computer as a surface: pair once with a code, test it, and switch it on.
 * The address is the only field a person normally needs; a token is a fold-out
 * for people who already have one.
 */
@Composable
fun ComputerScreen(
    state: SettingsUiState,
    onBack: () -> Unit,
    onSave: (String, String, String, String, Boolean, Boolean, String, String, Boolean) -> Unit,
    onTestBridge: (String, String) -> Unit,
    onPairBridge: (String, String) -> Unit,
    onPairBackend: (String, String) -> Unit,
    onDisconnectBackend: () -> Unit,
    onRefreshBackend: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(state.settings.bridgeUrl) }
    var code by rememberSaveable { mutableStateOf("") }
    var token by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(false) }
    val paired = state.settings.bridgeUrl.isNotBlank()

    LaunchedEffect(state.settings) {
        url = state.settings.bridgeUrl
    }

    fun persist(useBridge: Boolean = state.settings.useBridge) {
        onSave(
            state.settings.provider,
            state.settings.endpoint,
            state.settings.model,
            "",
            state.settings.notifyAnswers,
            state.settings.webEnabled,
            url,
            token,
            useBridge,
        )
        token = ""
    }

    Scaffold(
        containerColor = HopaColor.Bg,
        topBar = {
            HopaPageHeader(
                title = "My computer",
                onBack = onBack,
                status = {
                    HopaStatusPill(
                        text = if (paired) "Paired" else "Not set up",
                        color = if (paired) HopaColor.Success else HopaColor.Warning,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BackendCard(
                state = state, onPair = onPairBackend,
                onDisconnect = onDisconnectBackend, onRefresh = onRefreshBackend,
            )

            Surface(
                color = HopaColor.Surface,
                shape = RoundedCornerShape(22.dp),
                border = BorderStroke(1.dp, HopaColor.Divider),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text(
                        if (paired) "Your computer is paired" else "Let your PC do the tapping",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = HopaColor.TextPrimary,
                    )
                    Text(
                        if (paired) {
                            "It acts on this phone over adb while the bridge runs."
                        } else {
                            "Start the bridge on the PC, then type the address and the " +
                                "code it prints."
                        },
                        fontSize = 12.sp,
                        color = HopaColor.TextHint,
                    )
                    if (paired) {
                        OutlinedButton(
                            onClick = { onTestBridge(url, "") },
                            enabled = !state.busy,
                        ) {
                            Text(if (state.busy) "Testing\u2026" else "Test connection")
                        }
                    }
                }
            }

            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Address, e.g. http://192.168.1.5:8766") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            if (!paired) {
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it },
                    label = { Text("Pair code from the bridge console") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        persist()
                        onPairBridge(url.trim(), code.trim())
                    },
                    enabled = !state.busy && url.isNotBlank() && code.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Text(if (state.busy) "Pairing\u2026" else "Pair")
                }
            }

            TextButton(onClick = { advanced = !advanced }) {
                Text(
                    if (advanced) "Hide the token field" else "I have a token instead",
                    fontSize = 13.sp,
                    color = HopaColor.Primary,
                )
            }
            if (advanced) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    label = { Text("Bridge token") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = { persist() },
                    enabled = !state.busy && url.isNotBlank() && token.isNotBlank(),
                ) {
                    Text("Use this token")
                }
            }

            state.bridgeStatus?.let { message ->
                Text(
                    message,
                    fontSize = 12.sp,
                    color = if (message.contains("ok", ignoreCase = true)) {
                        HopaColor.Success
                    } else {
                        HopaColor.TextSecondary
                    },
                )
            }

            Surface(
                color = HopaColor.Surface,
                shape = RoundedCornerShape(20.dp),
                border = BorderStroke(1.dp, HopaColor.Divider),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Use my computer for taps", fontSize = 14.sp, color = HopaColor.TextPrimary)
                        Text(
                            "Off means this phone acts on its own.",
                            fontSize = 11.sp,
                            color = HopaColor.TextHint,
                        )
                    }
                    Switch(
                        checked = state.settings.useBridge,
                        onCheckedChange = { persist(useBridge = it) },
                    )
                }
            }

            Text(
                "Without a computer the phone still works: pick Hidden or Here on " +
                    "Running apps.",
                fontSize = 11.sp,
                color = HopaColor.TextHint,
            )
        }
    }
}
