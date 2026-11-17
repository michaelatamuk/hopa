package com.hopa.app.feature.chat

import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.hopa.app.di.AppContainer
import com.hopa.app.di.Surfaces
import com.hopa.core.gate.ApprovalRequest
import com.hopa.core.loop.TurnContext
import com.hopa.core.model.ModelMessage
import com.hopa.core.model.ModelRequest
import com.hopa.data.sessions.Attachment
import com.hopa.data.sessions.AttachmentRules
import com.hopa.data.sessions.ChatMessage
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class ChatViewModel(
    private val container: AppContainer,
) : ViewModel() {
    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
        viewModelScope.launch {
            container.approval.pending.collect { request ->
                _state.update { it.copy(pendingApproval = request) }
            }
        }
        viewModelScope.launch {
            container.pendingAttachment.collect { attachment ->
                if (attachment?.path != _state.value.attachment?.path) {
                    _state.update { it.copy(attachment = attachment) }
                }
            }
        }
        viewModelScope.launch {
            container.pendingNotice.collect { notice ->
                _state.update { it.copy(attachmentNotice = notice) }
            }
        }
        viewModelScope.launch {
            container.selectedConversation.collect { id ->
                if (id == null || id == _state.value.current?.id) {
                    return@collect
                }
                val all = container.conversations.all()
                val selected = all.firstOrNull { it.id == id } ?: return@collect
                _state.update {
                    it.copy(current = selected, conversations = all, options = emptyList())
                }
            }
        }
    }

    fun onInput(value: String) {
        _state.update { it.copy(input = value) }
    }

    /** A deep link or a shared selection lands in the composer, never sent. */
    fun prefill(text: String) {
        _state.update { it.copy(input = text.trim()) }
    }

    fun startVoice() {
        if (_state.value.busy || _state.value.recording) {
            return
        }
        if (!container.voice.hasPermission()) {
            _state.update { it.copy(voiceError = "Microphone permission is off") }
            return
        }
        if (!container.voice.start()) {
            _state.update { it.copy(voiceError = "Could not start the microphone") }
            return
        }
        _state.update { it.copy(recording = true, voiceError = null) }
    }

    fun stopVoice() {
        if (!_state.value.recording) {
            return
        }
        _state.update { it.copy(recording = false, transcribing = true) }
        viewModelScope.launch {
            val wav = runCatching { container.voice.stop() }.getOrNull()
            if (wav == null) {
                _state.update { it.copy(transcribing = false, voiceError = "Nothing was recorded") }
                return@launch
            }
            val result = runCatching { container.transcribe(wav) }
            val text = result.getOrNull()
            _state.update {
                it.copy(
                    transcribing = false,
                    input = text?.takeIf { value -> value.isNotBlank() } ?: it.input,
                    voiceError = when {
                        result.isFailure -> "Voice failed: ${result.exceptionOrNull()?.message}"
                        text == null -> "Connect the bridge to dictate"
                        text.isBlank() -> "I did not catch that"
                        else -> null
                    },
                )
            }
        }
    }

    fun dismissVoiceError() {
        _state.update { it.copy(voiceError = null) }
    }

    /** Read a reply through the PC voice or Android TTS. */
    fun speak(text: String) {
        if (text.isBlank()) {
            return
        }
        viewModelScope.launch { container.speak(text) }
    }

    fun newChat() {
        viewModelScope.launch {
            val created = container.conversations.create()
            container.selectedConversation.value = created.id
            container.settings.update { it.copy(selectedConversation = created.id) }
            _state.update {
                it.copy(
                    current = created,
                    conversations = container.conversations.all(),
                    input = "",
                    error = null,
                    options = emptyList(),
                )
            }
        }
    }

    fun select(id: String) {
        viewModelScope.launch {
            val all = container.conversations.all()
            val selected = all.firstOrNull { it.id == id } ?: return@launch
            container.selectedConversation.value = id
            container.settings.update { it.copy(selectedConversation = id) }
            _state.update { it.copy(current = selected, conversations = all, options = emptyList()) }
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            container.conversations.delete(id)
            load()
        }
    }

    fun rename(id: String, title: String) {
        viewModelScope.launch {
            container.conversations.rename(id, title)
            load()
        }
    }

    fun send() {
        val text = _state.value.input.trim()
        val attachment = _state.value.attachment
        if (text.isEmpty() && attachment == null) {
            return
        }
        if (_state.value.busy) {
            // The main turn is still going. A second goal goes to its own hidden
            // display instead of being dropped on the floor.
            if (text.isNotBlank() && container.runner.canStart()) {
                container.runner.start(text)
                _state.update { it.copy(input = "") }
            }
            return
        }
        sendText(text, attachment = attachment)
    }

    /** Drop the shared file before it is sent, and remove its bytes. */
    fun discardAttachment() {
        val attachment = _state.value.attachment
        container.pendingAttachment.value = null
        container.pendingNotice.value = null
        _state.update { it.copy(attachment = null, attachmentNotice = null) }
        if (attachment != null) {
            viewModelScope.launch { container.intake.delete(attachment) }
        }
    }

    fun dismissAttachmentNotice() {
        container.pendingNotice.value = null
        _state.update { it.copy(attachmentNotice = null) }
    }

    fun sendOption(option: String) {
        sendText(option)
    }

    fun sendQuick(text: String) {
        sendText(text)
    }

    private var planApprovedFor = ""
    private var pendingGoal = ""

    /** The person read the plan; run exactly that goal now. */
    fun approvePlan() {
        val goal = pendingGoal
        val plan = _state.value.pendingPlan
        if (goal.isBlank() || plan == null) {
            return
        }
        planApprovedFor = goal
        _state.update { it.copy(pendingPlan = null) }
        sendText(goal)
    }

    fun cancelPlan() {
        pendingGoal = ""
        _state.update { it.copy(pendingPlan = null) }
    }

    /** Keep the work as a reusable skill, so the next similar ask starts ahead. */
    fun saveSkill(goal: String, steps: List<String>) {
        if (goal.isBlank() || steps.isEmpty()) {
            return
        }
        viewModelScope.launch {
            runCatching {
                container.skills.add(
                    trigger = goal.take(80),
                    action = steps.joinToString("\n"),
                )
            }
        }
    }

    /** Remove the last exchange from the thread. */
    fun undoLast() {
        if (_state.value.busy) {
            return
        }
        val conversation = _state.value.current ?: return
        if (conversation.messages.size < 2) {
            return
        }
        viewModelScope.launch {
            container.conversations.dropLastExchange(conversation.id)
            val all = container.conversations.all()
            _state.update {
                it.copy(
                    current = all.firstOrNull { item -> item.id == conversation.id },
                    conversations = all,
                )
            }
        }
    }

    /** Re-run the last user goal without appending the message twice. */
    fun retryLast() {
        if (_state.value.busy) {
            return
        }
        val conversation = _state.value.current ?: return
        val lastUser = conversation.messages.lastOrNull { it.role == "user" } ?: return
        sendText(lastUser.text, appendUser = false)
    }

    fun resolveApproval(allow: Boolean) {
        container.approval.resolve(allow)
    }

    private fun sendText(
        text: String,
        attachment: Attachment? = null,
        appendUser: Boolean = true,
    ) {
        val conversation = _state.value.current ?: return
        val body = text.ifBlank { attachment?.let(AttachmentRules::describe).orEmpty() }
        if (planApprovedFor.isNotEmpty() && planApprovedFor != body) {
            planApprovedFor = ""
        }
        _state.update {
            it.copy(
                input = "",
                busy = true,
                error = null,
                options = emptyList(),
                attachment = null,
                attachmentNotice = null,
            )
        }
        container.pendingAttachment.value = null
        container.pendingNotice.value = null
        container.busy.value = true
        container.cancelRequested.value = false
        startRunService()
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            if (appendUser) {
                container.conversations.append(
                    conversation.id,
                    ChatMessage(
                        id = "m-$now-${UUID.randomUUID().toString().take(6)}",
                        role = "user",
                        text = body,
                        timestamp = now,
                        attachment = attachment,
                    ),
                )
                // The store is the source of truth, but the screen reads the state,
                // so the bubble has to be published now or it waits for the answer.
                val shown = container.conversations.all()
                _state.update {
                    it.copy(
                        current = shown.firstOrNull { item -> item.id == conversation.id },
                        conversations = shown,
                    )
                }
            }
            val history = conversation.messages.map { ModelMessage(it.role, it.text) }
            val current = container.settings.settings.first()
            container.policy.setTrustedApps(current.trustedApps.toList())
            if (current.planFirst && planApprovedFor != body) {
                // One approval for the whole chain: the model writes the plan, the
                // person reads it, and only then does anything get tapped.
                val plan = runCatching {
                    container.model.complete(
                        ModelRequest(
                            messages = listOf(
                                ModelMessage(
                                    "system",
                                    "You are planning, not acting. Reply with a numbered " +
                                        "plan of at most six short steps, one per line, " +
                                        "no other text.",
                                ),
                                ModelMessage("user", body),
                            ),
                            maxTokens = 400,
                        ),
                    ).text
                }.getOrNull()
                val steps = plan.orEmpty()
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { it.replaceFirst(Regex("^\\d+[.)]\\s*"), "") }
                    .take(6)
                    .toList()
                container.busy.value = false
                stopRunService()
                if (steps.isEmpty()) {
                    _state.update { it.copy(busy = false, error = "No plan came back") }
                } else {
                    pendingGoal = body
                    _state.update { it.copy(busy = false, pendingPlan = steps) }
                }
                return@launch
            }
            val facts = runCatching { container.memory.promptText() }.getOrDefault("")
            val character = "Character: ${current.tone} tone, ${current.chattiness} length."
            val memory = listOf(facts, character).filter { it.isNotBlank() }
                .joinToString("\n\n")
            val skills = runCatching { container.skills.promptText() }.getOrDefault("")
            val installed = runCatching { container.packages.installed() }.getOrDefault(emptyList())
            val device = container.surfaces(current)
            device.observe()
            if (device.sourceName == "fallback") {
                container.busy.value = false
                stopRunService()
                container.conversations.append(
                    conversation.id,
                    ChatMessage(
                        id = "m-${System.currentTimeMillis()}-screen",
                        role = "assistant",
                        // The refusal now comes from the surface that was actually
                        // tried, so a run on the hidden screen or the computer is not
                        // blocked by a local permission it does not need.
                        text = "I cannot reach your apps: " +
                            device.lastError.take(180).ifBlank { "no screen answered" } +
                            ". Open Running apps: turn on screen access, start the " +
                            "hidden screen, or pair your computer.",
                        timestamp = System.currentTimeMillis(),
                    ),
                )
                val all = container.conversations.all()
                _state.update {
                    it.copy(
                        current = all.firstOrNull { item -> item.id == conversation.id },
                        conversations = all,
                        busy = false,
                    )
                }
                return@launch
            }
            // A goal that worked before runs from its recipe: no model, no tokens.
            if (current.replaySkills) {
                val recipe = runCatching { container.recipes.find(body) }.getOrNull()
                if (recipe != null) {
                    val replayed = runCatching {
                        com.hopa.app.skills.Replayer(container).replay(recipe, device)
                    }.getOrDefault(0)
                    if (replayed == recipe.steps.size) {
                        Surfaces.release()
                        container.busy.value = false
                        val answer = "Done - repeated the $replayed steps that worked last time."
                        container.conversations.append(
                            conversation.id,
                            ChatMessage(
                                id = "m-${System.currentTimeMillis()}-replay",
                                role = "assistant",
                                text = answer,
                                timestamp = System.currentTimeMillis(),
                            ),
                        )
                        val shown = container.conversations.all()
                        _state.update {
                            it.copy(
                                current = shown.firstOrNull { item -> item.id == conversation.id },
                                conversations = shown,
                                busy = false,
                                lastPath = device.sourceName,
                                lastRun = RunSummary(
                                    goal = body,
                                    path = device.sourceName,
                                    steps = recipe.steps.mapIndexed { index, action ->
                                        "${index + 1}. ${action.kind}"
                                    },
                                    executed = replayed,
                                    verified = replayed,
                                    tokens = 0,
                                    millis = System.currentTimeMillis() - now,
                                ),
                            )
                        }
                        return@launch
                    }
                }
            }
            val loop = container.loopFor(device)
            val result = try {
                withTimeoutOrNull(120_000) {
                    loop.run(
                        runId = "run-$now",
                        traceId = UUID.randomUUID().toString(),
                        context = TurnContext(
                            goal = text,
                            history = history,
                            memory = memory,
                            skills = skills,
                            installedApps = installed,
                            appFunctions = typedFunctions(device),
                            vision = current.vision,
                            platform = "android",
                            phoneAccess = device.sourceName != "fallback",
                        ),
                        isCancelled = { container.cancelRequested.value },
                    )
                }
            } finally {
                stopRunService()
                Surfaces.release()
            }
            val answer = result?.answer ?: "I stopped after two minutes."
            container.conversations.append(
                conversation.id,
                ChatMessage(
                    id = "m-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(6)}",
                    role = "assistant",
                    text = answer,
                    timestamp = System.currentTimeMillis(),
                ),
            )
            val all = container.conversations.all()
            container.busy.value = false
            val steps = result?.records.orEmpty().map { record ->
                val target = record.action.app
                    ?: record.action.ref
                    ?: record.action.url
                    ?: record.action.text?.take(40)
                    ?: ""
                val suffix = if (target.isBlank()) "" else " $target"
                "${record.step}. ${record.action.kind}$suffix"
            }
            if (!result?.error?.message.isNullOrBlank().not()) {
                // only a clean run becomes a recipe
            }
            if (result != null && result.records.isNotEmpty() &&
                result.records.all { it.executed }
            ) {
                runCatching {
                    container.recipes.save(body, result.records.map { it.action })
                }
            }
            val summary = RunSummary(
                goal = body,
                path = device.sourceName,
                steps = steps,
                executed = result?.records?.count { it.executed } ?: 0,
                verified = result?.records?.count { it.verified } ?: 0,
                tokens = result?.usage?.totalTokens ?: 0,
                millis = System.currentTimeMillis() - now,
            )
            runCatching {
                container.runHistory.record(
                    at = System.currentTimeMillis(),
                    goal = body,
                    path = device.sourceName,
                    steps = steps.size,
                    tokens = summary.tokens,
                    conversationId = conversation.id,
                )
            }
            com.hopa.app.privacy.TurnLog.add(
                com.hopa.app.privacy.TurnLogEntry(
                    at = System.currentTimeMillis(),
                    path = device.sourceName,
                    steps = steps.size,
                    tokens = summary.tokens,
                    charactersSent = history.sumOf { it.content.length } + body.length,
                    goal = body.take(80),
                ),
            )
            _state.update {
                it.copy(
                    current = all.firstOrNull { item -> item.id == conversation.id },
                    conversations = all,
                    busy = false,
                    error = result?.error?.message,
                    options = result?.options.orEmpty(),
                    lastPath = device.sourceName,
                    lastRun = summary,
                )
            }
            answerOutsideApp(answer)
        }.invokeOnCompletion { container.busy.value = false }
    }

    /**
     * A run happens inside another app, so the answer is invisible until the
     * person switches back. Try to bring HOPA forward and leave a notification in
     * case Android refuses a background activity start.
     */
    private suspend fun answerOutsideApp(text: String) {
        if (text.isBlank() || com.hopa.app.service.AppVisibility.foreground) {
            return
        }
        val notify = runCatching { container.settings.settings.first().notifyAnswers }
            .getOrDefault(true)
        if (notify) {
            runCatching { com.hopa.app.service.Notifier.postAnswer(container.context, text) }
        }
        container.bringToFront()
    }

    /**
     * Ask the helper what the foreground app publishes, so the model can choose a
     * typed call over a series of taps. Empty when no shell is available.
     */
    private suspend fun typedFunctions(
        device: com.hopa.core.observation.ScreenSourceDevice,
    ): String {
        if (!container.shell.connected) {
            return ""
        }
        val foreground = runCatching { device.foreground() }.getOrDefault("")
        if (foreground.isBlank()) {
            return ""
        }
        return runCatching { container.shell.appFunctions(foreground).second }
            .getOrDefault("")
    }

    private fun startRunService() {
        val intent = Intent(container.context, com.hopa.app.service.RunService::class.java)
        runCatching {
            ContextCompat.startForegroundService(container.context, intent)
        }
    }

    private fun stopRunService() {
        runCatching {
            container.context.stopService(
                Intent(container.context, com.hopa.app.service.RunService::class.java),
            )
        }
    }

    private suspend fun load() {
        val all = container.conversations.all()
        val savedId = container.settings.settings.first().selectedConversation
        val selectedId = savedId.ifBlank { container.selectedConversation.value }
        val current = all.firstOrNull { it.id == selectedId }
            ?: all.firstOrNull()
            ?: container.conversations.create().also {
                container.selectedConversation.value = it.id
            }
        container.selectedConversation.value = current.id
        container.settings.update { it.copy(selectedConversation = current.id) }
        _state.update {
            it.copy(
                conversations = container.conversations.all(),
                current = current,
                busy = false,
            )
        }
    }
}
