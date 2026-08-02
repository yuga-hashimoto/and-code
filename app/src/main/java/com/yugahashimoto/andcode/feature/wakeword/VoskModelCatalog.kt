package com.yugahashimoto.andcode.feature.wakeword

import java.util.Locale

enum class VoskModelLanguage(val id: String) {
    ENGLISH("en"),
    JAPANESE("ja"),
    ;

    companion object {
        fun fromId(id: String?): VoskModelLanguage? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A speech model that can be fetched on demand.
 *
 * @param directoryName the single directory the archive unpacks into, which is also what Vosk is
 *   handed as the model path.
 * @param approximateBytes only for the "this will download about N MB" warning; the real size is
 *   whatever the server reports.
 */
data class VoskModelSpec(
    val language: VoskModelLanguage,
    val directoryName: String,
    val downloadUrl: String,
    val approximateBytes: Long,
)

/**
 * The models the wake word can be recognised with.
 *
 * They are downloaded rather than packaged: the smallest usable pair is about 90 MB together
 * against an APK that is currently 37 MB, and nobody who leaves the wake word switched off should
 * pay for them. Only the "small" models are offered - the large ones are gigabytes and are built
 * for transcription accuracy, which is not what spotting one phrase needs.
 */
object VoskModelCatalog {
    val ENGLISH =
        VoskModelSpec(
            language = VoskModelLanguage.ENGLISH,
            directoryName = "vosk-model-small-en-us-0.15",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            approximateBytes = 40L * 1024 * 1024,
        )

    val JAPANESE =
        VoskModelSpec(
            language = VoskModelLanguage.JAPANESE,
            directoryName = "vosk-model-small-ja-0.22",
            downloadUrl = "https://alphacephei.com/vosk/models/vosk-model-small-ja-0.22.zip",
            approximateBytes = 48L * 1024 * 1024,
        )

    val all: List<VoskModelSpec> = listOf(ENGLISH, JAPANESE)

    fun forLanguage(language: VoskModelLanguage): VoskModelSpec = all.first { it.language == language }

    /** The stored choice, falling back to English for an unset or unrecognised value. */
    fun forLanguageId(id: String?): VoskModelSpec = VoskModelLanguage.fromId(id)?.let(::forLanguage) ?: ENGLISH

    /** What to preselect the first time, before anything has been chosen. */
    fun defaultLanguageFor(locale: Locale): VoskModelLanguage =
        if (locale.language == VoskModelLanguage.JAPANESE.id) VoskModelLanguage.JAPANESE else VoskModelLanguage.ENGLISH
}
