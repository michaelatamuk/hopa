package com.hopa.app.di

import com.hopa.app.backend.RemoteRunRequest
import com.hopa.core.loop.TurnContext
import com.hopa.core.loop.TurnResult
import com.hopa.core.observation.ScreenSourceDevice

/**
 * The turn a delivered task runs. The context is the one the chat builds: what
 * this phone can open, what HOPA remembers, the skills it learned and the typed
 * functions of the foreground app. History is empty because a remote goal
 * starts a fresh turn; the phone is the surface that answered.
 */
object RemoteRuns {
    suspend fun execute(container: AppContainer, request: RemoteRunRequest): TurnResult {
        val settings = request.settings
        container.policy.setTrustedApps(settings.trustedApps.toList())
        val facts = runCatching { container.memory.promptText() }.getOrDefault("")
        val character = "Character: ${settings.tone} tone, ${settings.chattiness} length."
        val memory = listOf(facts, character).filter { it.isNotBlank() }
            .joinToString("\n\n")
        val skills = runCatching { container.skills.promptText() }.getOrDefault("")
        val installed = runCatching { container.packages.installed() }.getOrDefault(emptyList())
        return container.loopFor(request.device).run(
            runId = request.runId,
            traceId = request.traceId,
            context = TurnContext(
                goal = request.goal,
                memory = memory,
                skills = skills,
                installedApps = installed,
                appFunctions = typedFunctions(container, request.device),
                vision = settings.vision,
                platform = "android",
                phoneAccess = request.device.sourceName != "fallback",
            ),
            isCancelled = request.isCancelled,
        )
    }

    /**
     * Ask the helper what the foreground app publishes, so the model can choose a
     * typed call over a series of taps. Empty when no shell is available.
     */
    private suspend fun typedFunctions(
        container: AppContainer,
        device: ScreenSourceDevice,
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
}
