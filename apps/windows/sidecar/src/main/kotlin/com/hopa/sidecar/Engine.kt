package com.hopa.sidecar

import com.hopa.capabilities.bridge.AdbDevice
import com.hopa.capabilities.bridge.BridgeClient
import com.hopa.capabilities.llm.ModelFactory
import com.hopa.core.gate.ActionExecutor
import com.hopa.core.gate.ApprovalPolicy
import com.hopa.core.loop.AgentLoop
import com.hopa.core.loop.TurnContext
import com.hopa.core.model.ModelMessage
import com.hopa.core.model.ModelPort
import com.hopa.core.ports.DevicePort
import com.hopa.core.records.InMemoryRunRecorder
import com.hopa.core.records.RunChain
import com.hopa.core.records.RunRecordFactory
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** One turn at a time; the loop and the gate are the real ones. */
class Engine(
    private val io: Stdio,
    private val hostDevice: HostDevice,
    private val approval: HostApproval,
    private val store: ChatStore,
    private val recordStore: RecordStore,
    private val config: RuntimeConfig,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = SystemClock()

    @Volatile
    private var cancelled = false

    fun cancel() {
        cancelled = true
        io.log("turn cancelled by the user")
    }

    fun startTurn(id: String, goal: String, conversationId: String?) {
        scope.launch {
            cancelled = false
            val conversation = conversationId?.let { store.get(it) } ?: store.create()
            val history = conversation.messages.map { ModelMessage(it.role, it.text) }
            store.append(conversation.id, "user", goal)
            io.log("turn $id in ${conversation.id}: $goal")

            val device: DevicePort = if (config.hasBridge) {
                val bridge = BridgeClient(
                    baseUrl = config.bridgeUrl,
                    token = config.bridgeToken,
                    refresh = {
                        val refresh = config.bridgeRefresh
                        if (refresh.isBlank()) {
                            null
                        } else {
                            runCatching { BridgeClient(config.bridgeUrl, "").refreshSession(refresh) }
                                .getOrNull()
                                ?.let { session ->
                                    config.setSession(session.token, session.refresh)
                                    io.log("bridge token refreshed")
                                    io.send(Protocol.session(session.token, session.refresh))
                                    session.token
                                }
                        }
                    },
                )
                AdbDevice(bridge, clock)
            } else {
                hostDevice
            }
            val model: ModelPort = if (config.hasModel) {
                ModelFactory().create(
                    provider = config.modelProvider,
                    endpoint = config.modelBaseUrl,
                    apiKey = config.modelKey,
                    modelName = config.modelName,
                )
            } else {
                SpikeModel()
            }

            val recorder = InMemoryRunRecorder()
            val executor = ActionExecutor(
                device = device,
                browser = null,
                system = null,
                sandbox = null,
                policy = ApprovalPolicy(),
                approval = approval,
                recorder = recorder,
                factory = RunRecordFactory(clock),
                clock = clock,
            )
            val loop = AgentLoop(
                model = model,
                device = device,
                executor = executor,
                recorder = recorder,
                clock = clock,
                webAvailable = false,
            )
            val outcome = runCatching {
                loop.run(
                    runId = "run-$id",
                    traceId = UUID.randomUUID().toString(),
                    context = TurnContext(goal = goal, history = history),
                    isCancelled = { cancelled },
                )
            }

            val answer = outcome.getOrNull()?.answer
                ?: "The sidecar failed: ${outcome.exceptionOrNull()?.message ?: "unknown error"}"
            val records = outcome.getOrNull()?.records.orEmpty()
            recordStore.append(conversation.id, "run-$id", records)
            val chainOk = records.isEmpty() || RunChain.verify(records)
            store.append(conversation.id, "assistant", answer)
            val updated = store.get(conversation.id)
            io.send(
                Protocol.turnEnd(
                    id = id,
                    conversationId = conversation.id,
                    title = updated?.title ?: "New chat",
                    answer = answer,
                    steps = records,
                    chainOk = chainOk,
                ),
            )
        }
    }
}
