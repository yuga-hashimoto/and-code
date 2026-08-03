package com.yugahashimoto.andcode.feature.wakeword

import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

class VoskWakeWordDetectorTest {
    /**
     * The Vosk native library cannot load on the JVM test runtime, so `org.vosk.LibVosk` fails in
     * its static initializer. The first attempt reports that as an UnsatisfiedLinkError; every
     * later one reports NoClassDefFoundError, because the class stays marked as failed. A minified
     * build reaches the same second state from the first attempt, since the JNA fields the native
     * code looks up by name are renamed and the failure is already recorded against the class.
     *
     * Neither of those is an Exception, so both have to be contained here: the service switches the
     * wake word back off when initialize() reports failure, but an error escaping this call takes
     * the whole process down instead.
     */
    @Test
    fun `initialize reports failure instead of throwing when the native library cannot load`() {
        val detector =
            VoskWakeWordDetector(
                modelDirectory = File("/nonexistent/vosk-model-en-us"),
                phrase = "hey andcode",
                sensitivity = 0.5f,
            )

        assertFalse(detector.initialize())
        assertFalse(detector.initialize())
    }
}
