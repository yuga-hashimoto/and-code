package com.yugahashimoto.andcode.feature.assistant

/**
 * Speaking rate and pitch, shared by the settings sliders and the engine configuration.
 *
 * [TTSProviderConfig.Android] rejects a non-positive rate or pitch, so every stored value is
 * clamped here rather than trusted: an unset or corrupt preference would otherwise throw while the
 * voice session is being constructed, where there is nothing left to fall back to.
 */
internal object TtsTuning {
    const val MIN_RATE = 0.5f
    const val MAX_RATE = 2.0f
    const val DEFAULT_RATE = 1.0f

    const val MIN_PITCH = 0.5f
    const val MAX_PITCH = 2.0f
    const val DEFAULT_PITCH = 1.0f

    fun rate(value: Float): Float = value.coerceIn(MIN_RATE, MAX_RATE)

    fun pitch(value: Float): Float = value.coerceIn(MIN_PITCH, MAX_PITCH)

    fun androidConfig(
        enginePackage: String?,
        speechRate: Float,
        pitch: Float,
    ): TTSProviderConfig.Android =
        TTSProviderConfig.Android(
            enginePackage = enginePackage,
            speechRate = rate(speechRate),
            pitch = pitch(pitch),
        )
}
