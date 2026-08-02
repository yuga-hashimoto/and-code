package com.yugahashimoto.andcode.feature.assistant

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/** What the voice settings preview button is showing. */
enum class TtsPreviewState {
    IDLE,
    PREPARING,
    SPEAKING,
    FAILED,
    ;

    val isRunning: Boolean get() = this == PREPARING || this == SPEAKING

    fun pressAction(): TtsPreviewAction = if (isRunning) TtsPreviewAction.STOP else TtsPreviewAction.SPEAK

    companion object {
        fun from(state: TTSState): TtsPreviewState =
            when (state) {
                TTSState.Preparing -> PREPARING
                TTSState.Speaking -> SPEAKING
                TTSState.Done -> IDLE
                is TTSState.Error -> FAILED
            }
    }
}

enum class TtsPreviewAction {
    SPEAK,
    STOP,
}

/**
 * Speaks a sample line with the settings currently on screen.
 *
 * Rate and pitch are the kind of thing nobody can pick from a number, so the preview reads the
 * settings afresh on every press: it is there to be heard immediately after moving a slider, and
 * a provider captured at construction time would play back the previous choice.
 */
internal class TtsPreview(
    context: Context,
    private val scope: CoroutineScope,
) {
    private val manager = TTSManager(context.applicationContext)
    private val mutableState = MutableStateFlow(TtsPreviewState.IDLE)
    val state: StateFlow<TtsPreviewState> = mutableState.asStateFlow()
    private var job: Job? = null

    fun press(
        settings: TtsSettings,
        sampleText: String,
    ) {
        when (mutableState.value.pressAction()) {
            TtsPreviewAction.STOP -> stop()
            TtsPreviewAction.SPEAK -> speak(settings, sampleText)
        }
    }

    private fun speak(
        settings: TtsSettings,
        sampleText: String,
    ) {
        job?.cancel()
        manager.configure(TtsConfiguration.from(settings))
        mutableState.value = TtsPreviewState.PREPARING
        job =
            scope.launch {
                manager.speakWithProgress(sampleText).collectLatest { progress ->
                    mutableState.value = TtsPreviewState.from(progress)
                }
            }
    }

    fun stop() {
        job?.cancel()
        job = null
        manager.stop()
        mutableState.value = TtsPreviewState.IDLE
    }

    fun release() {
        job?.cancel()
        job = null
        manager.shutdown()
    }
}
