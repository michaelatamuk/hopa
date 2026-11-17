package com.hopa.app.feature.runningapps

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hopa.app.backend.BackendTaskState
import com.hopa.app.feature.settings.PairInput
import com.hopa.app.feature.settings.SettingsUiState
import com.hopa.app.feature.settings.pathLabel
import com.hopa.app.ui.theme.HopaColor
import com.hopa.capabilities.backend.BackendState

/**
 * The gateway card: the address, the one pairing field, and what this phone is
 * to the household right now. A token is only ever shown by its last six
 * characters; the copy action hands the whole value to the clipboard.
 */
@Composable
fun BackendCard(
    state: SettingsUiState,
    onPair: (String, String) -> Unit,
    onDisconnect: () -> Unit,
    onRefresh: () -> Unit,
) {
    var url by rememberSaveable { mutableStateOf(state.backendUrl) }
    var text by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val parsed = PairInput.normalise(text, url)
    val paired = state.backendDeviceId.isNotBlank()

    LaunchedEffect(state.backendUrl) {
        url = state.backendUrl
    }

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
                "HOPA gateway",
                fontSize = 19.sp,
                fontWeight = FontWeight.SemiBold,
                color = HopaColor.TextPrimary,
            )
            Text(
                "Pair this phone once, then tasks and messages reach it here.",
                fontSize = 12.sp,
                color = HopaColor.TextHint,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Gateway address, e.g. wss://host/v1/gateway") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = text,
                onValueChange = { value ->
                    text = value
                    // A whole link carries its own gateway; a bare session uses
                    // the address already on screen.
                    val whole = value.trim().startsWith("hopa://", ignoreCase = true)
                    val link = PairInput.normalise(value, "")
                    if (whole && link != null) {
                        url = link.gatewayUrl
                    }
                },
                label = { Text("Pairing link, or the session alone") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = { onPair(url.trim(), text.trim()) },
                    enabled = !state.busy && parsed != null,
                ) {
                    Text(if (state.busy) "Pairing\u2026" else "Pair")
                }
                OutlinedButton(
                    onClick = onDisconnect,
                    enabled = !state.busy && paired,
                ) {
                    Text("Disconnect")
                }
                TextButton(onClick = onRefresh) {
                    Text("Refresh", fontSize = 13.sp, color = HopaColor.Primary)
                }
            }
            state.backendStatus?.let { message ->
                Text(
                    message,
                    fontSize = 12.sp,
                    color = if (message.startsWith("Gateway: ")) {
                        HopaColor.Danger
                    } else {
                        HopaColor.TextSecondary
                    },
                )
            }
            Text(
                deviceLine(state),
                fontSize = 12.sp,
                color = HopaColor.TextPrimary,
            )
            FcmLine(
                available = state.fcmAvailable,
                token = state.backendToken,
                onCopy = { token -> clipboard.setText(AnnotatedString(token)) },
            )
            Text(
                "Last task: " + taskLabel(state.backendTask),
                fontSize = 12.sp,
                color = HopaColor.TextSecondary,
            )
            Text(
                "How apps open: " + pathLabel(state.settings.screenPath) +
                    " - " + pathNeed(state.settings.screenPath),
                fontSize = 11.sp,
                color = HopaColor.TextHint,
            )
        }
    }
}

@Composable
private fun FcmLine(
    available: Boolean,
    token: String?,
    onCopy: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        val label = when {
            !available -> "FCM unavailable"
            token == null -> "FCM waiting for a token"
            else -> "FCM " + masked(token)
        }
        Text(label, fontSize = 12.sp, color = HopaColor.TextSecondary)
        if (available && token != null) {
            TextButton(onClick = { onCopy(token) }) {
                Text("Copy", fontSize = 12.sp, color = HopaColor.Primary)
            }
        }
    }
}

/** At most the last six characters, so a screenshot cannot carry a token. */
private fun masked(token: String): String =
    if (token.length <= 6) "\u2022\u2022\u2022\u2022" else "\u2022\u2022\u2022\u2022" + token.takeLast(6)

private fun deviceLine(state: SettingsUiState): String {
    val name = if (state.backendName.isBlank()) "" else state.backendName + " \u00b7 "
    val id = state.backendDeviceId.take(8).ifBlank { "not paired" }
    return "Device " + name + id + " \u00b7 " + connectionLabel(state.backendState)
}

private fun connectionLabel(state: BackendState): String = when (state) {
    BackendState.Disconnected -> "not connected"
    BackendState.Connecting -> "connecting"
    is BackendState.Failed -> if (state.needsPairing) "not paired" else "failed: ${state.reason}"
    is BackendState.Live ->
        "connected" + if (state.queued > 0) ", ${state.queued} waiting" else ""
}

private fun taskLabel(task: BackendTaskState): String {
    if (task.taskId.isBlank() && task.state == "idle") {
        return "none yet"
    }
    val goal = if (task.goal.isNotBlank()) ": ${task.goal.take(60)}" else ""
    return task.state + goal
}

private fun pathNeed(screenPath: String): String = when (screenPath) {
    "background" -> "needs the background setup"
    "onscreen" -> "needs the screen on and unlocked"
    "bridge" -> "needs the PC bridge running"
    else -> "picks the best path it can"
}
