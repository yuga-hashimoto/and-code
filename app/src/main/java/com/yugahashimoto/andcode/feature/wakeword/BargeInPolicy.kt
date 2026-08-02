package com.yugahashimoto.andcode.feature.wakeword

/** What saying the wake word should do, given what the assistant is currently doing. */
internal enum class WakeWordOutcome {
    START_SESSION,
    INTERRUPT_SPEECH,
    IGNORE,
}

/**
 * Whether the wake word may cut into a reply that is being read out.
 *
 * A long answer used to have to be sat through: the wake-word service stops listening for the
 * whole session, so the only way to stop it was to reach for the screen. Barge-in keeps detection
 * running for exactly the part of a session where the microphone is otherwise free - while the
 * assistant is speaking - and treats a hit there as "stop talking" rather than as a new request.
 */
internal object BargeInPolicy {
    fun outcomeFor(
        sessionActive: Boolean,
        speaking: Boolean,
        bargeInEnabled: Boolean,
    ): WakeWordOutcome =
        when {
            !sessionActive -> WakeWordOutcome.START_SESSION
            speaking && bargeInEnabled -> WakeWordOutcome.INTERRUPT_SPEECH
            else -> WakeWordOutcome.IGNORE
        }

    /**
     * The session's own recogniser holds the microphone whenever it is listening, so detection may
     * only resume for the speaking part of a session, and only when barge-in is wanted at all.
     */
    fun shouldListenDuringSession(
        speaking: Boolean,
        bargeInEnabled: Boolean,
    ): Boolean = speaking && bargeInEnabled
}
