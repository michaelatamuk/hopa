package com.hopa.app.backend

import com.hopa.capabilities.backend.BackendEnvelope
import com.hopa.capabilities.backend.BackendLink
import com.hopa.capabilities.backend.BackendReplies
import com.hopa.capabilities.backend.ProcessedTaskLog
import com.hopa.core.contracts.ErrorCodes
import com.hopa.core.contracts.HopaError
import com.hopa.core.loop.TurnResult
import com.hopa.core.observation.ScreenSourceDevice
import com.hopa.data.settings.AppSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Runs a task the gateway delivered on the surface the person configured, then
 * answers on the socket it arrived on.
 *
 * Ack comes first, always: even a locked, busy or surfaceless phone has to tell
 * the sender the frame arrived before it says anything else. Dedupe comes next,
 * then one run at a time behind [gate] so two deliveries never fight over the
 * screen. The wake socket is bracketed with [taskStarted]/[taskFinished], so it
 * stays open for the whole run.
 */
class RemoteTaskRunner(
    private val deviceId: () -> String,
    private val link: () -> BackendLink?,
    private val processed: ProcessedTaskLog,
    private val settings: suspend () -> AppSettings,
    private val surfaces: suspend (AppSettings) -> ScreenSourceDevice,
    private val screen: (AppSettings) -> RemoteScreenState,
    private val busy: () -> Boolean,
    private val cancel: MutableStateFlow<Boolean>,
    private val foreground: () -> Boolean,
    private val startService: () -> Unit,
    private val stopService: () -> Unit,
    private val postApproval: () -> Unit,
    private val releaseSurface: () -> Unit,
    private val run: suspend (RemoteRunRequest) -> TurnResult?,
    private val taskStarted: () -> Unit = {},
    private val taskFinished: () -> Unit = {},
    private val runTimeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {
    private val gate = Mutex()

    suspend fun handle(task: BackendEnvelope) {
        val live = link() ?: return
        live.ack(task)
        val id = task.task.orEmpty().trim()
        val goal = runCatching { task.body["goal"]?.jsonPrimitive?.contentOrNull }
            .getOrNull()
            .orEmpty()
            .trim()
        if (id.isBlank() || goal.isBlank()) {
            live.send(BackendReplies.error(task, deviceId(), "E_SCHEMA", INVALID, false))
            return
        }
        if (!processed.claim(id)) {
            return
        }
        gate.withLock { execute(live, task, id, goal) }
    }

    private suspend fun execute(
        live: BackendLink,
        task: BackendEnvelope,
        id: String,
        goal: String,
    ) {
        val current = settings()
        // Checked before resolving: starting a hidden display for a task that
        // will be refused would leave it running under this run's feet.
        if (busy()) {
            val refusal = RemoteRunPlanner.busyRefusal()
            live.send(
                BackendReplies.error(
                    task,
                    deviceId(),
                    refusal.code,
                    refusal.message,
                    refusal.retryable,
                ),
            )
            return
        }
        val surface = try {
            surfaces(current)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            live.send(BackendReplies.error(task, deviceId(), "E_DEVICE", NO_SCREEN, true))
            releaseSurface()
            return
        }
        // The surface is only a candidate until it answers once; that answer is
        // what names the path in the plan and in the reply.
        try {
            surface.observe()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The surface refused: sourceName stays "fallback" and the planner
            // names the setup step the person has to take.
        }
        val state = screen(current)
        val plan = RemoteRunPlanner.plan(
            surfaceName = surface.sourceName,
            screenPath = current.screenPath,
            screenOn = state.screenOn,
            keyguardLocked = state.keyguardLocked,
            accessibilityOn = state.accessibilityOn,
            busy = busy(),
        )
        if (plan is RemoteRunPlan.Refuse) {
            live.send(
                BackendReplies.error(task, deviceId(), plan.code, plan.message, plan.retryable),
            )
            releaseSurface()
            return
        }

        taskStarted()
        try {
            // An approval is a dialog on HOPA's own screen; when the app is not
            // visible, the notification is the only way to find it again.
            if (!foreground()) {
                postApproval()
            }
            // A refused foreground start leaves the run in this coroutine; the
            // cap below still reports a budget error if it cannot finish.
            cancel.value = false
            runCatching { startService() }
            live.send(BackendReplies.progress(task, deviceId(), "Reading the screen"))
            val result = attempt(task, id, goal, surface, current)
            sendResult(live, task, result)
        } finally {
            stopService()
            releaseSurface()
            taskFinished()
        }
    }

    private suspend fun attempt(
        task: BackendEnvelope,
        id: String,
        goal: String,
        surface: ScreenSourceDevice,
        current: AppSettings,
    ): TurnResult? = try {
        withTimeoutOrNull(runTimeoutMs) {
            run(
                RemoteRunRequest(
                    goal = goal,
                    device = surface,
                    settings = current,
                    runId = "run-$id",
                    traceId = task.trace ?: id,
                    isCancelled = { cancel.value },
                ),
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        TurnResult(
            answer = "The run failed.",
            error = HopaError(ErrorCodes.MODEL, "the run failed"),
        )
    }

    private suspend fun sendResult(
        live: BackendLink,
        task: BackendEnvelope,
        result: TurnResult?,
    ) {
        val device = deviceId()
        if (result == null) {
            live.send(BackendReplies.error(task, device, "E_BUDGET", TIMEOUT, false))
            return
        }
        val failure = result.error
        if (failure != null) {
            val budget = failure.code == ErrorCodes.TIMEOUT || failure.code == "E_BUDGET"
            val code = if (budget) "E_BUDGET" else "E_MODEL"
            val message = result.answer.ifBlank { failure.message }.take(200)
            live.send(BackendReplies.error(task, device, code, message, code == "E_MODEL"))
            return
        }
        live.send(BackendReplies.result(task, device, result.answer))
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 170_000L
        const val INVALID = "the task needs an id and a goal"
        const val TIMEOUT = "the run did not finish in time"
        const val NO_SCREEN =
            "I cannot reach your apps: open Running apps, turn on screen access, " +
                "start the hidden screen, or pair your computer."
    }
}
