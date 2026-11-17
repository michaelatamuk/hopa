package com.hopa.app.di

import android.content.Context
import android.os.Build
import androidx.core.content.ContextCompat
import com.google.firebase.FirebaseApp
import com.hopa.app.BuildConfig
import com.hopa.app.backend.BackendSession
import com.hopa.app.backend.ProcessedTaskStore
import com.hopa.app.backend.PushTokenFetch
import com.hopa.app.backend.RemoteRunRequest
import com.hopa.app.backend.RemoteTaskRunner
import com.hopa.app.model.SettingsModelPort
import com.hopa.app.service.AppVisibility
import com.hopa.app.service.Notifier
import com.hopa.app.service.RunService
import com.hopa.capabilities.accessibility.AccessibilityDevice
import com.hopa.capabilities.llm.ModelFactory
import com.hopa.capabilities.llm.ProviderChoiceRules
import com.hopa.capabilities.system.AndroidClock
import com.hopa.capabilities.system.AndroidPackageCatalog
import com.hopa.capabilities.system.AndroidSystemPort
import com.hopa.core.gate.ActionExecutor
import com.hopa.core.gate.ApprovalPolicy
import com.hopa.app.gate.UiApprovalGateway
import com.hopa.core.loop.AgentLoop
import com.hopa.core.observation.ScreenSourceDevice
import com.hopa.data.database.HopaDatabase
import com.hopa.data.records.RoomRunRecorder
import com.hopa.core.records.RunRecordFactory
import com.hopa.data.secrets.KeystoreSecretStore
import com.hopa.data.sessions.ConversationStore
import com.hopa.data.sessions.RoomConversationStore
import com.hopa.data.settings.AppSettings
import com.hopa.data.settings.DataStoreSettingsRepository
import com.hopa.data.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first

class AppContainer(context: Context) {
    val context: Context = context.applicationContext
    val settings: SettingsRepository = DataStoreSettingsRepository(
        context = context,
        secrets = KeystoreSecretStore(context),
    )
    init {
        com.hopa.app.privacy.TurnLog.attach(
            com.hopa.app.privacy.TurnLogFile(context),
        )
    }

    private val database = HopaDatabase.build(context)
    val conversations: ConversationStore = RoomConversationStore(database)
    val selectedConversation = MutableStateFlow<String?>(null)
    val busy = MutableStateFlow(false)
    val cancelRequested = MutableStateFlow(false)
    /** A goal handed to the chat by a profile home (Power's command bar). */
    val pendingGoal = MutableStateFlow<String?>(null)
    /** A file shared into the app, waiting in the composer. */
    val pendingAttachment = MutableStateFlow<com.hopa.data.sessions.Attachment?>(null)
    val pendingNotice = MutableStateFlow<String?>(null)
    /** Work that must outlive an Activity, such as a one-shot launch pairing. */
    val appScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    /** The last launch-pairing outcome, shown on the Backend card. */
    val backendNotice = MutableStateFlow<String?>(null)
    val intake = com.hopa.app.share.AttachmentIntake(context)
    val packages = AndroidPackageCatalog(context)
    val recorder = RoomRunRecorder(database.runRecordDao())
    val runHistory = com.hopa.data.history.RoomRunHistory(database.runHistoryDao())
    val pendingApprovals = com.hopa.data.approvals.RoomPendingApprovalStore(
        database.pendingApprovalDao(),
    )
    val memory = com.hopa.data.memory.RoomMemoryStore(database.memoryDao())
    val skills = com.hopa.data.skills.RoomSkillStore(database.skillDao())
    val clock = AndroidClock()
    val policy = ApprovalPolicy()
    val approval = UiApprovalGateway(pendingApprovals)
    val factory = RunRecordFactory(clock)
    val device = AccessibilityDevice(context)
    val voice = com.hopa.capabilities.voice.VoiceRecorder(context)
    val speaker = com.hopa.capabilities.voice.Speaker(context)
    val executor = ActionExecutor(
        device = device,
        browser = null,
        system = AndroidSystemPort(context),
        sandbox = null,
        policy = policy,
        approval = approval,
        recorder = recorder,
        factory = factory,
        clock = clock,
    )
    /** Goals that run at the same time, each on its own hidden display. */
    val runner by lazy { com.hopa.app.tasks.ParallelRunner(this) }

    /**
     * The app's one gateway link. Pairing and the activity life cycle reach it
     * through here; [remoteRunner] is attached below as its delivery handler.
     */
    val backend: BackendSession by lazy {
        BackendSession(
            settings = settings,
            pairingFactory = { url -> com.hopa.capabilities.backend.BackendPairing(url) },
            linkFactory = { credentials, name, version, pushToken, refresh ->
                com.hopa.capabilities.backend.BackendClient(
                    credentials = { credentials },
                    pushToken = pushToken,
                    refresh = refresh,
                    deviceName = name,
                    appVersion = version,
                )
            },
            deviceName = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
            // Without google-services.json Firebase is never initialized, so
            // there is nothing to ask and no token to wait for.
            pushTokenFetch = {
                FirebaseApp.getApps(context).isNotEmpty() && PushTokenFetch.load()
            },
        )
    }

    init {
        // A delivered task runs on the surface the person configured. The
        // runner answers on the socket the delivery arrived on.
        backend.onTask = { task -> remoteRunner.handle(task) }
    }

    /** The surface stack for the person's screenPath setting, shared by chat and remote runs. */
    val surfaces: suspend (AppSettings) -> ScreenSourceDevice = { Surfaces.resolve(this, it) }

    /** Task ids the gateway delivered, so a redelivery inside a day is skipped. */
    private val processedTasks by lazy { ProcessedTaskStore.open(context) }

    /**
     * A task delivered by the gateway runs on the configured surface and answers
     * on the wake socket. One at a time, so two deliveries never fight over the
     * screen.
     */
    val remoteRunner by lazy {
        RemoteTaskRunner(
            deviceId = { backend.deviceId },
            link = { backend.currentLink() },
            processed = processedTasks,
            settings = { settings.settings.first() },
            surfaces = { current -> surfaces(current) },
            screen = { current -> Surfaces.state(this, current) },
            busy = busy,
            cancel = cancelRequested,
            foreground = { AppVisibility.foreground },
            startService = { startRunService() },
            stopService = { stopRunService() },
            postApproval = { Notifier.postApproval(context) },
            releaseSurface = { Surfaces.release() },
            run = { request: RemoteRunRequest -> RemoteRuns.execute(this, request) },
            taskStarted = { backend.taskStarted() },
            taskFinished = { backend.taskFinished() },
        )
    }

    private fun startRunService() {
        val intent = android.content.Intent(context, RunService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stopRunService() {
        val intent = android.content.Intent(context, RunService::class.java)
        runCatching { context.stopService(intent) }
    }

    /** The hidden background display: one helper process, one socket. */
    val shell = com.hopa.capabilities.display.ShellClient()

    /**
     * Wireless debugging inside the app: pair once with the code Android shows,
     * then adbd gives this app shell and no PC is needed.
     */
    val wireless = com.hopa.capabilities.display.WirelessShell(context)

    /**
     * A device on the background display, or null when no helper can be reached.
     * A paired bridge can start the helper itself, so the common case needs no
     * button press; after a reboot the first background run does it.
     */
    suspend fun backgroundDevice(): com.hopa.core.ports.DevicePort? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            if (!shell.connect()) {
                val startedHere = runCatching { wireless.connectAndStartHelper() }
                    .getOrDefault("")
                    .contains("ready")
                if (!startedHere) {
                    startHelperThroughBridge()
                }
                kotlinx.coroutines.delay(1_500)
                if (!shell.connect()) {
                    return@withContext null
                }
            }
            val metrics = context.resources.displayMetrics
            val device = com.hopa.capabilities.display.ShellDisplayDevice(shell, context)
            if (device.start(metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)) {
                device
            } else {
                null
            }
        }

    /** Pair with this phone's own adbd, then start the helper with that shell. */
    suspend fun pairWireless(address: String, code: String): String =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val host = address.substringBefore(':').trim()
            val port = address.substringAfter(':', "").trim().toIntOrNull()
            if (host.isEmpty() || port == null) {
                return@withContext "Use the address Android shows: ip:port"
            }
            val paired = wireless.pair(host, port, code)
            if (!paired.startsWith("Paired")) {
                return@withContext paired
            }
            val started = wireless.connectAndStartHelper()
            if (started.contains("ready") && shell.connect()) {
                "Wireless shell is ready; the background screen can be used"
            } else {
                started
            }
        }

    /** Ask the paired PC to start the helper on the phone as shell. */
    private suspend fun startHelperThroughBridge(): Boolean {
        val current = settings.settings.first()
        if (current.bridgeUrl.isBlank()) {
            return false
        }
        val token = settings.bridgeToken()
        if (token.isBlank()) {
            return false
        }
        return runCatching {
            com.hopa.capabilities.bridge.BridgeClient(current.bridgeUrl, token).startHelper()
            true
        }.getOrDefault(false)
    }

    val model = SettingsModelPort(settings, ModelFactory(), ProviderChoiceRules())
    /** Shared by the settings screen's "Test the model" button. */
    val modelFactory: ModelFactory = ModelFactory()
    val loop = AgentLoop(
        model = model,
        device = device,
        executor = executor,
        recorder = recorder,
        clock = clock,
        webAvailable = false,
    )

    /** A bridge-backed device when Settings has an address and a token. */
    suspend fun bridgedDevice(): com.hopa.core.ports.DevicePort? {
        val current = settings.settings.first()
        if (!current.useBridge || current.bridgeUrl.isBlank()) {
            return null
        }
        val token = settings.bridgeToken()
        if (token.isBlank()) {
            return null
        }
        val client = com.hopa.capabilities.bridge.BridgeClient(
            baseUrl = current.bridgeUrl,
            token = token,
            refresh = {
                val saved = settings.bridgeRefresh()
                if (saved.isBlank()) {
                    null
                } else {
                    runCatching {
                        com.hopa.capabilities.bridge.BridgeClient(
                            current.bridgeUrl,
                            "",
                        ).refreshSession(saved)
                    }.getOrNull()?.let { session ->
                        settings.setBridgeToken(session.token)
                        settings.setBridgeRefresh(session.refresh)
                        session.token
                    }
                }
            },
        )
        return com.hopa.capabilities.bridge.AdbDevice(client, clock)
    }

    /**
     * Bring the chat back after a run that happened in another app. Android may
     * refuse a background activity start; the caller posts a notification too, so
     * a refusal still leaves a way in.
     */
    fun bringToFront() {
        val intent = android.content.Intent(context, com.hopa.app.MainActivity::class.java)
            .addFlags(
                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                    android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            )
        runCatching { context.startActivity(intent) }
    }

    /** Read a reply aloud: the PC voice when a bridge is configured, else TTS. */
    suspend fun speak(text: String): Boolean {
        val current = settings.settings.first()
        val token = settings.bridgeToken()
        if (current.bridgeUrl.isNotBlank() && token.isNotBlank()) {
            val wav = runCatching {
                com.hopa.capabilities.bridge.BridgeClient(current.bridgeUrl, token).speak(text)
            }.getOrNull()
            if (wav != null) {
                return speaker.play(wav)
            }
        }
        return speaker.speak(text)
    }

    /** Speech to text through the PC bridge; null when no bridge is configured. */
    suspend fun transcribe(wav: ByteArray): String? {
        val current = settings.settings.first()
        if (current.bridgeUrl.isBlank()) {
            return null
        }
        val token = settings.bridgeToken()
        if (token.isBlank()) {
            return null
        }
        return com.hopa.capabilities.bridge.BridgeClient(current.bridgeUrl, token)
            .transcribe(wav)
    }

    /** The conversation half of the bridge, when one is configured. */
    suspend fun chatSync(): com.hopa.app.feature.sync.ChatSync? {
        val current = settings.settings.first()
        if (!current.useBridge || current.bridgeUrl.isBlank()) {
            return null
        }
        val token = settings.bridgeToken()
        if (token.isBlank()) {
            return null
        }
        return com.hopa.app.feature.sync.ChatSync(
            conversations = conversations,
            transport = com.hopa.capabilities.bridge.BridgeChatTransport(
                com.hopa.capabilities.bridge.BridgeClient(current.bridgeUrl, token),
            ),
        )
    }

    /** One executor per device path, sharing the policy, approval and recorder. */
    fun executorFor(device: com.hopa.core.ports.DevicePort): ActionExecutor = ActionExecutor(
        device = device,
        browser = null,
        system = AndroidSystemPort(context),
        sandbox = null,
        policy = policy,
        approval = approval,
        recorder = recorder,
        factory = factory,
        clock = clock,
    )

    /** Recipes that were replayed before; a matching goal runs without the model. */
    val recipes by lazy { com.hopa.app.skills.RecipeStore(java.io.File(context.filesDir, "recipes.txt")) }

    /** One loop per device path, sharing the model, recorder and gate. */
    fun loopFor(device: com.hopa.core.ports.DevicePort): AgentLoop = AgentLoop(
        model = model,
        device = device,
        executor = executorFor(device),
        recorder = recorder,
        clock = clock,
        webAvailable = false,
    )
}
