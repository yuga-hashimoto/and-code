package com.yugahashimoto.andcode.feature.wakeword

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The bundled openWakeWord models declare undefined batch/length dimensions, which used to abort
 * tensor allocation inside the [android.content.Context]-less TFLite interpreter constructor. The
 * wake-word toggle then turned itself straight back off, so the models are loaded for real here
 * rather than mocked.
 */
@RunWith(AndroidJUnit4::class)
class OpenWakeWordDetectorInstrumentedTest {
    @Test
    fun initializesBundledModels() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = OpenWakeWordDetector(context)
        try {
            assertTrue("Detector failed to initialize the bundled models", detector.initialize())
        } finally {
            detector.release()
        }
    }

    @Test
    fun processesSilenceWithoutDetecting() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val detector = OpenWakeWordDetector(context)
        try {
            assertTrue(detector.initialize())
            val silence = ShortArray(1280)
            repeat(30) { detector.processAudio(silence) }
        } finally {
            detector.release()
        }
    }
}
