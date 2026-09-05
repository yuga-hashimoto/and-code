package com.yugahashimoto.andcode.runtime.local

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** What is persisted: the user's own presets, plus which one is active. */
@Serializable
private data class SystemPromptState(
    @SerialName("customPresets") val customPresets: List<SystemPromptPreset> = emptyList(),
    @SerialName("selectedPresetId") val selectedPresetId: String? = null,
)

/**
 * The system-prompt presets and the current selection, shared by every agent that can carry one.
 *
 * One store rather than one per agent: the presets are the user's own writing, and having "Debug"
 * exist under Claude Code but not under OpenCode would make them re-enter it. How a preset reaches
 * the model is what differs per agent - Claude Code passes it as `--append-system-prompt` per
 * session, OpenCode reads it from an instructions file shared by every session on the runtime.
 */
class SystemPromptStore(
    private val file: File,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = true
        }

    private val state =
        runCatching { json.decodeFromString<SystemPromptState>(file.readText()) }
            .getOrDefault(SystemPromptState())

    /** Built-in presets first, then whatever the user has saved, in the order they were added. */
    private val mutablePresets = MutableStateFlow(ClaudeSystemPrompts.BUILT_IN + state.customPresets)
    val presets: StateFlow<List<SystemPromptPreset>> = mutablePresets.asStateFlow()

    private val mutableSelectedId = MutableStateFlow(state.selectedPresetId)
    val selectedId: StateFlow<String?> = mutableSelectedId.asStateFlow()

    fun byId(id: String?): SystemPromptPreset? = id?.let { target -> mutablePresets.value.firstOrNull { it.id == target } }

    /** The prompt text the selected preset carries, or null when no preset is selected. */
    fun selectedPrompt(): String? = byId(mutableSelectedId.value)?.prompt

    fun select(presetId: String?) {
        mutableSelectedId.value = presetId
        persist()
    }

    /** Creates a new custom preset, or updates one already saved when [id] names an existing one. */
    fun save(
        name: String,
        prompt: String,
        id: String? = null,
    ): SystemPromptPreset {
        val presetId = id?.takeIf { byId(it)?.builtIn == false } ?: UUID.randomUUID().toString()
        val preset = SystemPromptPreset(id = presetId, name = name, prompt = prompt)
        mutablePresets.value = mutablePresets.value.filterNot { it.id == preset.id } + preset
        persist()
        return preset
    }

    /**
     * Removes a custom preset, clearing the selection when it pointed at that preset. Built-in
     * presets are not removable, so this is a no-op for them.
     *
     * Left dangling, the selected id would keep naming a preset that no longer exists and the
     * picker would show no radio button selected at all instead of "None".
     */
    fun delete(id: String): Boolean {
        if (byId(id)?.builtIn != false) return false
        mutablePresets.value = mutablePresets.value.filterNot { it.id == id }
        if (mutableSelectedId.value == id) mutableSelectedId.value = null
        persist()
        return true
    }

    private fun persist() {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(
                json.encodeToString(
                    SystemPromptState(
                        customPresets = mutablePresets.value.filterNot(SystemPromptPreset::builtIn),
                        selectedPresetId = mutableSelectedId.value,
                    ),
                ),
            )
        }
    }
}
