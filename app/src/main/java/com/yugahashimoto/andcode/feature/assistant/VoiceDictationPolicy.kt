package com.yugahashimoto.andcode.feature.assistant

import android.speech.SpeechRecognizer

/** What chat dictation should do once a recognition segment has ended in an error. */
internal enum class VoiceDictationOutcome {
    /** Open another segment: the recogniser heard nothing, which is not a failure yet. */
    RESTART,

    /** Stop listening quietly, keeping what has been dictated so far. */
    FINISH,

    /** Stop listening and put the error in front of the user. */
    REPORT,
}

/**
 * Dictation runs as a chain of recognition segments, and a segment that heard nothing ends in an
 * error rather than an empty result. Reporting every one of those stopped dictation at the first
 * pause and showed "could not recognise" for what was really just a silent moment, so silence is
 * only worth saying out loud once nothing at all has been heard for several segments in a row.
 */
internal object VoiceDictationPolicy {
    /** How many silent segments in a row before silence is reported rather than retried. */
    const val MAX_SILENT_SEGMENTS = 3

    fun outcomeFor(
        code: Int?,
        hasTranscript: Boolean,
        consecutiveFailures: Int,
    ): VoiceDictationOutcome =
        when {
            !isTransient(code) -> VoiceDictationOutcome.REPORT
            hasTranscript -> VoiceDictationOutcome.FINISH
            consecutiveFailures < MAX_SILENT_SEGMENTS -> VoiceDictationOutcome.RESTART
            else -> VoiceDictationOutcome.REPORT
        }

    /**
     * Errors that say "nothing was heard this time" rather than "something is wrong". A missing
     * code means the recogniser never started, which retrying cannot fix.
     */
    private fun isTransient(code: Int?): Boolean =
        code == SpeechRecognizer.ERROR_NO_MATCH ||
            code == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
            code == SpeechRecognizer.ERROR_RECOGNIZER_BUSY
}
