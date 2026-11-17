package com.hopa.data.settings

data class AppSettings(
    val provider: String = "stub",
    val endpoint: String = "https://api.deepseek.com/v1",
    val model: String = "deepseek-v4-flash",
    val profile: String = "everyday",
    val notifyAnswers: Boolean = true,
    val webEnabled: Boolean = true,
    val selectedConversation: String = "",
    val bridgeUrl: String = "",
    val useBridge: Boolean = true,
    val accessibilityAllowed: Boolean = true,
    val tone: String = "friendly",
    val chattiness: String = "balanced",
    /** auto, background, onscreen or bridge; auto is the only one a user needs. */
    val screenPath: String = "auto",
    /** Show the numbered plan and wait for one approval before acting. */
    val planFirst: Boolean = false,
    /** Repeat a goal that worked before without asking the model. */
    val replaySkills: Boolean = true,
    /** Attach a screenshot to each model call (needs a model that sees images). */
    val vision: Boolean = false,
    /** Packages whose typed calls, messages and closing skip the question. */
    val trustedApps: Set<String> = emptySet(),
    /** Address of the gateway this device is paired with. */
    val backendUrl: String = "",
    /** Identity this device was given when it joined the household. */
    val backendDeviceId: String = "",
    val backendHouseholdId: String = "",
)
