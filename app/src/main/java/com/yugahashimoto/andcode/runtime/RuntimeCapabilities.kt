package com.yugahashimoto.andcode.runtime

/** Capabilities exposed by a runtime so the UI never guesses from an implementation class. */
data class RuntimeCapabilities(
    val permissions: Boolean = false,
    val questions: Boolean = false,
    val toolEvents: Boolean = false,
    val providerModelList: Boolean = false,
    val resume: Boolean = false,
    /**
     * True when sending a message while a turn is running must always queue behind it rather than
     * interrupt it, regardless of the user's send-behavior setting.
     *
     * Some runtimes (Antigravity) run each turn as a brand-new one-shot process; "interrupting" it
     * means killing that process outright, which surfaces as a crash rather than a cancellation. See
     * [com.yugahashimoto.andcode.runtime.local.AntigravityRuntime.send] for the failure this avoids.
     */
    val forcesQueue: Boolean = false,
    /**
     * True when interrupting a running turn means aborting it before the new prompt is sent, because
     * the runtime silently drops a prompt that arrives while a turn is still in flight.
     *
     * OpenCode's `prompt_async` stores the message and then asks its session runner to run; when the
     * runner is already running it attaches to the run in flight instead of starting one for the new
     * message. If that run is wedged (a tool that never returns) or is already unwinding, the stored
     * prompt is never picked up: the server answers `204 No Content` and the chat waits forever.
     * Aborting first puts the runner back to idle so the prompt starts a run of its own.
     */
    val abortsBeforeInterrupt: Boolean = false,
)
