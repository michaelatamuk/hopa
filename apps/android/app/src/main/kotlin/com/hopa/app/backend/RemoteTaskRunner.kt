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
 * then [busy] is claimed for the whole attempt so chat cannot start a second
 * loop over the same screen. The foreground service and [taskStarted] follow
 * before the surface is resolved, so the wake socket outlives a slow helper.
 * [gate] keeps two remote deliveries from running at once.
 */
class RemoteTaskRunner(
    private val deviceId: () -> String,
    private val link: () -> BackendLink?,
    private val processed: ProcessedTaskLog,
    private val settings: suspend () -> AppSettings,
    private val surfaces: suspend (AppSettings) -> ScreenSourceDevice,
    private val screen: (AppSettings) -> RemoteScreenState,
    private val busy: MutableStateFlow<Boolean>,
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
        if (processed.seen(id)) {
            return
        }
        // A chat turn or an earlier delivery holds the phone: answer now instead
        // of starting a second loop over the same screen.
        if (!busy.compareAndSet(expect = false, update = true)) {
            sendRefusal(live, task, RemoteRunPlanner.busyRefusal())
            return
        }
        try {
            cancel.value = false
            // A refused foreground start leaves the run in this coroutine; the
            // cap below still reports a budget error if it cannot finish.
            runCatching { startService() }
            taskStarted()
            // An approval is a dialog on HOPA's own screen; when the app is not
            // visible, the notification is the only way to find it again.
            if (!foreground()) {
                postApproval()
            }
            val current = settings()
            val surface = try {
                surfaces(current)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                live.send(BackendReplies.error(task, deviceId(), "E_DEVICE", NO_SCREEN, true))
                return
            }
            // The surface is only a candidate until it answers once; that answer
            // is what names the path in the plan and in the reply.
            try {
                surface.observe()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // The surface refused: sourceName stays "fallback" and the
                // planner names the setup step the person has to take.
            }
            val state = screen(current)
            val plan = RemoteRunPlanner.plan(
                surfaceName = surface.sourceName,
                screenPath = current.screenPath,
                screenOn = state.screenOn,
                keyguardLocked = state.keyguardLocked,
                accessibilityOn = state.accessibilityOn,
                // This runner already holds the claim, so the planner's own
                // busy check would only see itself.
                busy = false,
            )
            if (plan is RemoteRunPlan.Refuse) {
                sendRefusal(live, task, plan)
                return
            }
            gate.withLock {
                // Claim only an id that actually runs; a refusal must stay
                // retryable under the id the sender was told to resend.
                if (!processed.claim(id)) {
                    return@withLock
                }
                val failed = try {
                    runTurn(live, task, id, goal, surface, current)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    true
                }
                if (failed) {
                    // The sender may resend this id after E_MODEL or E_BUDGET.
                    processed.release(id)
                }
            }
        } finally {
            stopService()
            releaseSurface()
            taskFinished()
            busy.value = false
        }
    }

    private suspend fun sendRefusal(
        live: BackendLink,
        task: BackendEnvelope,
        refusal: RemoteRunPlan.Refuse,
    ) {
        live.send(
            BackendReplies.error(
                task,
                deviceId(),
                refusal.code,
                refusal.message,
                refusal.retryable,
            ),
        )
    }

    /** Runs the turn and answers; true when the reply was a failure frame. */
    private suspend fun runTurn(
        live: BackendLink,
        task: BackendEnvelope,
        id: String,
        goal: String,
        surface: ScreenSourceDevice,
        current: AppSettings,
    ): Boolean {
        live.send(BackendReplies.progress(task, deviceId(), "Reading the screen"))
        val result = attempt(task, id, goal, surface, current)
        return sendResult(live, task, result)
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

    /** Sends the outcome; true when it was an error frame. */
    private suspend fun sendResult(
        live: BackendLink,
        task: BackendEnvelope,
        result: TurnResult?,
    ): Boolean {
        val device = deviceId()
        if (result == null) {
            live.send(BackendReplies.error(task, device, "E_BUDGET", TIMEOUT, false))
            return true
        }
        val failure = result.error
        if (failure != null) {
            val budget = failure.code == ErrorCodes.TIMEOUT || failure.code == "E_BUDGET"
            val code = if (budget) "E_BUDGET" else "E_MODEL"
            val message = result.answer.ifBlank { failure.message }.take(200)
            live.send(BackendReplies.error(task, device, code, message, code == "E_MODEL"))
            return true
        }
        live.send(BackendReplies.result(task, device, result.answer))
        return false
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
