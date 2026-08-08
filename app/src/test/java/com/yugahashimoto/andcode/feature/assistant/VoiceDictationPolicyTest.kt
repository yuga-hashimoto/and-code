package com.yugahashimoto.andcode.feature.assistant

import android.speech.SpeechRecognizer
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceDictationPolicyTest {
    @Test
    fun `a first silent segment starts another one instead of failing the dictation`() {
        assertEquals(
            VoiceDictationOutcome.RESTART,
            VoiceDictationPolicy.outcomeFor(
                code = SpeechRecognizer.ERROR_NO_MATCH,
                hasTranscript = false,
                consecutiveFailures = 1,
            ),
        )
    }

    @Test
    fun `a partial result followed by silence finishes without an error`() {
        // The voice session submits the partial transcript when the final callback is empty, so a
        // red "could not recognise" banner would be both wrong and alarming.
        assertEquals(
            VoiceDictationOutcome.FINISH,
            VoiceDictationPolicy.outcomeFor(
                code = SpeechRecognizer.ERROR_NO_MATCH,
                hasTranscript = true,
                consecutiveFailures = 1,
            ),
        )
    }

    @Test
    fun `silence that never ends is eventually reported`() {
        assertEquals(
            VoiceDictationOutcome.REPORT,
            VoiceDictationPolicy.outcomeFor(
                code = SpeechRecognizer.ERROR_NO_MATCH,
                hasTranscript = false,
                consecutiveFailures = VoiceDictationPolicy.MAX_SILENT_SEGMENTS,
            ),
        )
    }

    @Test
    fun `a speech timeout is treated as silence`() {
        assertEquals(
            VoiceDictationOutcome.RESTART,
            VoiceDictationPolicy.outcomeFor(
                code = SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                hasTranscript = false,
                consecutiveFailures = 1,
            ),
        )
    }

    @Test
    fun `a busy recogniser is retried, as the message the user sees promises`() {
        assertEquals(
            VoiceDictationOutcome.RESTART,
            VoiceDictationPolicy.outcomeFor(
                code = SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                hasTranscript = false,
                consecutiveFailures = 1,
            ),
        )
    }

    @Test
    fun `errors the user has to act on are reported straight away`() {
        listOf(
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
            SpeechRecognizer.ERROR_NETWORK,
            SpeechRecognizer.ERROR_AUDIO,
            SpeechRecognizer.ERROR_CLIENT,
            SpeechRecognizer.ERROR_SERVER,
        ).forEach { code ->
            assertEquals(
                "code $code",
                VoiceDictationOutcome.REPORT,
                VoiceDictationPolicy.outcomeFor(code = code, hasTranscript = false, consecutiveFailures = 1),
            )
        }
    }

    @Test
    fun `an error with no code at all is reported`() {
        // Failing to start the recogniser carries no SpeechRecognizer code; retrying it in a loop
        // would spin silently forever.
        assertEquals(
            VoiceDictationOutcome.REPORT,
            VoiceDictationPolicy.outcomeFor(code = null, hasTranscript = false, consecutiveFailures = 1),
        )
    }
}
