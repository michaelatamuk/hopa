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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
 * One deadline covers the resolve and the turn: the whole attempt stays inside
 * the foreground service's short-service window, and a timed-out turn still
 * sends its answer. [gate] keeps two remote deliveries from running at once.
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
    private val surfaceTimeoutMs: Long = DEFAULT_SURFACE_TIMEOUT_MS,
    private val now: () -> Long = System::currentTimeMillis,
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
        var claimed = false
        try {
            cancel.value = false
            val startedAt = now()
            // A refused foreground start leaves the run in this coroutine; the
            // deadline below still reports a budget error if it cannot finish.
            runCatching { startService() }
            taskStarted()
            val current = settings()
            // `auto` may start the hidden helper, wait on it and connect again
            // before the screen answers at all. Without a cap that resolve holds
            // the gate, the busy claim and the wake bracket forever, so every
            // later task would be delivered to a stuck runner.
            val step = try {
                withTimeoutOrNull(surfaceTimeoutMs) { resolve(current) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                live.send(BackendReplies.error(task, deviceId(), "E_DEVICE", NO_SCREEN, true))
                return
            }
            if (step == null) {
                // Nothing answered in time. The id was never claimed here, but
                // releasing it matches the other retryable failures so the
                // resend the message asks for is never blocked by a claim.
                live.send(BackendReplies.error(task, deviceId(), "E_DEVICE", SURFACE_TIMEOUT, true))
                processed.release(id)
                return
            }
            val surface = step.surface
            val plan = step.plan
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
                claimed = true
                // Only a task that will actually run asks for consent: an
                // approval is a dialog on HOPA's own screen, and a refusal has
                // nothing to approve. When HOPA is not visible, the
                // notification is the only way to find that dialog again.
                if (!foreground()) {
                    postApproval()
                }
                val budget = RunBudget.remaining(runTimeoutMs, now() - startedAt)
                val failed = try {
                    runTurn(live, task, id, goal, surface, current, budget)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    true
                }
                if (failed) {
                    // The sender may resend this id after a retryable failure.
                    processed.release(id)
                    claimed = false
                }
            }
        } catch (cancelled: CancellationException) {
            // A cancelled run would otherwise keep the claim, and the resend of
            // its id would be skipped as a duplicate.
            if (claimed) {
                withContext(NonCancellable) { processed.release(id) }
            }
            throw cancelled
        } finally {
            stopService()
            releaseSurface()
            taskFinished()
            busy.value = false
        }
    }

    /**
     * Resolves the surface for one delivery and plans against what the phone
     * reports. Runs inside the surface timeout, so every suspension here is
     * bounded by the caller's deadline.
     */
    private suspend fun resolve(current: AppSettings): SurfaceStep {
        val surface = surfaces(current)
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
            accessibilityBound = state.accessibilityBound,
            lastError = surface.lastError,
        )
        return SurfaceStep(surface, plan)
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
        budgetMs: Long,
    ): Boolean {
        live.send(BackendReplies.progress(task, deviceId(), "Reading the screen"))
        val result = attempt(task, id, goal, surface, current, budgetMs)
        return sendResult(live, task, result)
    }

    private suspend fun attempt(
        task: BackendEnvelope,
        id: String,
        goal: String,
        surface: ScreenSourceDevice,
        current: AppSettings,
        budgetMs: Long,
    ): TurnResult? {
        if (budgetMs <= 0) {
            return null
        }
        return try {
            withTimeoutOrNull(budgetMs) {
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
            val (code, retryable) = failureFor(failure.code)
            val message = result.answer.ifBlank { failure.message }.take(200)
            live.send(BackendReplies.error(task, device, code, message, retryable))
            return true
        }
        // A refused approval leaves the loop running with no error, so the
        // records are the only evidence that a step was not done.
        if (result.records.any { it.approved == false }) {
            live.send(BackendReplies.error(task, device, "E_POLICY", APPROVAL_REFUSED, false))
            return true
        }
        live.send(BackendReplies.result(task, device, result.answer))
        return false
    }

    /** The contract's code and retryability for a failure the loop returned. */
    private fun failureFor(code: String): Pair<String, Boolean> = when (code) {
        ErrorCodes.MODEL -> "E_MODEL" to true
        ErrorCodes.DEVICE -> "E_DEVICE" to true
        ErrorCodes.TIMEOUT -> "E_TIMEOUT" to true
        ErrorCodes.SCHEMA -> "E_SCHEMA" to false
        ErrorCodes.BLOCKED -> "E_BLOCKED" to false
        ErrorCodes.POLICY -> "E_POLICY" to false
        "E_ACTION" -> "E_ACTION" to false
        "E_CAPABILITY" -> "E_CAPABILITY" to false
        "E_BUDGET" -> "E_BUDGET" to false
        ErrorCodes.REFUSED -> "E_POLICY" to false
        ErrorCodes.UNSUPPORTED -> "E_CAPABILITY" to false
        else -> "E_AGENT" to false
    }

    private companion object {
        /**
         * Resolve plus run share this deadline, keeping the whole attempt inside
         * the 180 s short-service cap with room to send the reply.
         */
        const val DEFAULT_TIMEOUT_MS = 170_000L

        /**
         * How long resolving the surface may take. The `auto` path starts the
         * hidden helper and reconnects before the screen is even reached, so a
         * cold helper needs room; 25 s covers that while leaving 145 s to run.
         */
        const val DEFAULT_SURFACE_TIMEOUT_MS = 25_000L

        const val INVALID = "the task needs an id and a goal"
        const val TIMEOUT = "the run did not finish in time"
        const val SURFACE_TIMEOUT = "the screen did not answer in time; send it again"
        const val APPROVAL_REFUSED = "a step needed your approval and was refused"
        const val NO_SCREEN =
            "I cannot reach your apps: open Running apps, turn on screen access, " +
                "start the hidden screen, or pair your computer."
    }

    /** The surface that will run, with the plan the planner made for it. */
    private class SurfaceStep(
        val surface: ScreenSourceDevice,
        val plan: RemoteRunPlan,
    )
}
