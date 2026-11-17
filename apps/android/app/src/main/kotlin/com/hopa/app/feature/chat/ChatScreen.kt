package com.hopa.app.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.hopa.app.feature.chat.component.AccessibilityBanner
import com.hopa.app.feature.chat.component.ChatEmptyState
import com.hopa.app.feature.chat.component.Composer
import com.hopa.app.feature.chat.component.MessageBubble
import com.hopa.app.feature.chat.component.PendingAttachmentRow
import com.hopa.app.feature.chat.component.ThinkingIndicator
import com.hopa.app.ui.component.HopaBottomBar
import com.hopa.app.ui.component.HopaBottomBarEasy
import com.hopa.app.ui.component.HopaMascot
import com.hopa.app.ui.component.HopaTopBar
import com.hopa.app.ui.theme.HopaColor
import com.hopa.capabilities.accessibility.AccessibilityState
import com.hopa.capabilities.accessibility.HopaAccessibilityService
import com.hopa.data.sessions.ChatMessage

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    state: ChatUiState,
    activeTab: String,
    onSelectTab: (String) -> Unit,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
    onOption: (String) -> Unit,
    onQuick: (String) -> Unit,
    onNewChat: () -> Unit,
    onOpenConversations: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onDismissVoiceError: () -> Unit,
    onSpeak: (String) -> Unit,
    onRetry: () -> Unit,
    onUndo: () -> Unit,
    onDiscardAttachment: () -> Unit,
    onDismissAttachmentNotice: () -> Unit,
    easy: Boolean = false,
    onOpenSettings: () -> Unit = {},
    onApprovePlan: () -> Unit = {},
    onCancelPlan: () -> Unit = {},
    onSaveSkill: (String, List<String>) -> Unit = { _, _ -> },
    tasks: List<com.hopa.app.tasks.TaskItem> = emptyList(),
    onOpenTask: (com.hopa.app.tasks.TaskItem) -> Unit = {},
    onDismissTask: (String) -> Unit = {},
) {
    val messages = state.current?.messages.orEmpty()
    var evidence by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    // Phone access is granted in Android settings, so it is re-read every time
    // the app comes back; a stale "off" banner would hide the only real fix.
    var accessEnabled by remember { mutableStateOf(AccessibilityState.isEnabled(context)) }
    var accessConnected by remember { mutableStateOf(HopaAccessibilityService.instance != null) }
    // Android turns the service off on every reinstall and the binding can come
    // back a moment later, so the state is re-read on resume and then polled.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                accessEnabled = AccessibilityState.isEnabled(context)
                accessConnected = HopaAccessibilityService.instance != null
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(2_000)
            accessEnabled = AccessibilityState.isEnabled(context)
            accessConnected = HopaAccessibilityService.instance != null
        }
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            onStartVoice()
        }
    }
    val requestMic = {
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            onStartVoice()
        } else {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
    LaunchedEffect(messages.size, state.busy) {
        if (messages.isNotEmpty()) {
            // Two passes: the first scroll runs on estimated item heights, and a
            // tall attachment bubble settles only after the next measure.
            val last = if (state.busy) messages.size else messages.lastIndex
            listState.animateScrollToItem(last)
            withFrameNanos { }
            listState.animateScrollToItem(last)
        }
    }
    Scaffold(
        containerColor = HopaColor.Bg,
        topBar = {
            HopaTopBar {
                Row(
                    modifier = Modifier
                        .height(44.dp)
                        .clickable(onClick = onOpenConversations)
                        .padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        Icons.Outlined.Schedule,
                        contentDescription = "Chats",
                        tint = HopaColor.TextPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                    Text("Chats", fontSize = 12.sp, color = HopaColor.TextSecondary)
                }
                if (messages.isNotEmpty()) {
                    Text(
                        text = state.current?.title ?: "Chat",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                        color = HopaColor.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (state.error != null) {
                    Row(
                        modifier = Modifier
                            .padding(end = 6.dp)
                            .background(HopaColor.Danger.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                            .clickable { onSelectTab("activity") }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        HopaMascot(sizeDp = 20.dp, state = "error", interactive = false)
                        Text(
                            "Problem",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                            color = HopaColor.Danger,
                        )
                    }
                }
                if (messages.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .size(width = 40.dp, height = 44.dp)
                            .clickable(onClick = onNewChat),
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .background(HopaColor.PrimarySoft, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "New chat",
                                tint = HopaColor.Primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        },
        bottomBar = {
            val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
            Column(
                modifier = Modifier
                    .navigationBarsPadding()
                    .imePadding()
                    .background(HopaColor.Bg),
            ) {
                if (state.options.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        state.options.forEach { option ->
                            AssistChip(
                                onClick = { onOption(option) },
                                label = { Text(option) },
                            )
                        }
                    }
                }
                state.attachment?.let { attachment ->
                    PendingAttachmentRow(
                        attachment = attachment,
                        onDiscard = onDiscardAttachment,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    )
                }
                if (tasks.isNotEmpty()) {
                    androidx.compose.foundation.layout.Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        tasks.forEach { task ->
                            TaskChip(
                                task = task,
                                onOpen = { onOpenTask(task) },
                                onDismiss = { onDismissTask(task.id) },
                            )
                        }
                    }
                }
                if (!accessEnabled || !accessConnected) {
                    AccessibilityBanner(
                        onOpenSettings = { AccessibilityState.openSettings(context) },
                        enabled = accessEnabled,
                        connected = accessConnected,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
                    )
                }
                Box(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
                ) {
                    Composer(
                        value = state.input,
                        busy = state.busy,
                        onValueChange = onInput,
                        onSend = {
                            // A sent message ends the typing: the field loses focus
                            // and the keyboard goes away so the answer is visible.
                            focusManager.clearFocus()
                            keyboard?.hide()
                            onSend()
                        },
                        recording = state.recording,
                        transcribing = state.transcribing,
                        onMic = {
                            if (state.recording) {
                                onStopVoice()
                            } else {
                                requestMic()
                            }
                        },
                        hasAttachment = state.attachment != null,
                    )
                }
                state.attachmentNotice?.let { notice ->
                    Text(
                        notice,
                        fontSize = 11.sp,
                        color = HopaColor.Danger,
                        modifier = Modifier
                            .clickable(onClick = onDismissAttachmentNotice)
                            .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                    )
                }
                if (state.transcribing) {
                    Text(
                        "Transcribing\u2026",
                        fontSize = 11.sp,
                        color = HopaColor.TextHint,
                        modifier = Modifier.padding(start = 20.dp, bottom = 4.dp),
                    )
                }
                state.voiceError?.let { error ->
                    Text(
                        error,
                        fontSize = 11.sp,
                        color = HopaColor.Danger,
                        modifier = Modifier
                            .clickable(onClick = onDismissVoiceError)
                            .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
                    )
                }
                if (!imeVisible && easy) {
                    HopaBottomBarEasy(
                        active = activeTab,
                        onSelect = { key ->
                            if (key == "settings") onOpenSettings() else onSelectTab(key)
                        },
                    )
                } else if (!imeVisible) {
                    HopaBottomBar(
                        active = activeTab,
                        onSelect = onSelectTab,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 8.dp),
        ) {
            if (state.busy) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = HopaColor.Primary,
                    trackColor = HopaColor.PrimarySoft,
                )
            }
            if (messages.isNotEmpty() && state.lastPath.isNotEmpty()) {
                Text(
                    "Ran on " + when (state.lastPath) {
                        "background" -> "the hidden screen"
                        "bridge" -> "the PC bridge"
                        else -> "this screen"
                    },
                    fontSize = 10.sp,
                    color = HopaColor.TextHint,
                    modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                )
            }
            val run = state.lastRun
            if (run != null && messages.isNotEmpty()) {
                Text(
                    "What happened: ${run.executed} steps, ${run.verified} confirmed",
                    fontSize = 11.sp,
                    color = HopaColor.Primary,
                    modifier = Modifier
                        .padding(start = 8.dp, top = 2.dp)
                        .clickable { evidence = true },
                )
            }
            if (evidence && run != null) {
                EvidenceSheet(
                    run = run,
                    onSaveSkill = {
                        onSaveSkill(run.goal, run.steps)
                        evidence = false
                    },
                    onDismiss = { evidence = false },
                )
            }
            state.pendingPlan?.let { plan ->
                PlanSheet(
                    steps = plan,
                    onApprove = onApprovePlan,
                    onCancel = onCancelPlan,
                )
            }
            if (messages.isEmpty()) {
                ChatEmptyState(onQuick = onQuick)
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = ChatMessage::id) { message ->
                        MessageBubble(
                            message = message,
                            onSpeak = onSpeak,
                            onRetry = onRetry,
                            onUndo = onUndo,
                        )
                    }
                    if (state.busy) {
                        item(key = "thinking") {
                            ThinkingIndicator()
                        }
                    }
                }
            }
            state.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }
    }
}
