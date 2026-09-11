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
 * Longest prompt a preset may hold.
 *
 * Claude Code takes the prompt as a single `--append-system-prompt` argv entry, and Linux caps one
 * argument at `MAX_ARG_STRLEN` (128 KiB). A preset pasted past that would not merely fail to apply:
 * it is persisted, so every later Claude process start would fail too until the user found and
 * deleted it. 8000 characters is a long system prompt and, even as four-byte UTF-8, a quarter of
 * the limit. The editor stops at the same number, so this is the backstop rather than the control.
 */
const val MAX_SYSTEM_PROMPT_LENGTH = 8_000

/**
 * A preset held for a chat that has no session yet.
 *
 * Wrapped rather than passed as a bare id so that a staged "None" - the user clearing the preset
 * for this chat - is distinguishable from nothing having been staged at all.
 */
@JvmInline
value class StagedSystemPrompt(
    val id: String?,
)

/**
 * Which preset [sessionId] carries: its own snapshot when it has a record, and the default new
 * chats inherit when it does not.
 *
 * A session snapshots the default when it is created and keeps it from then on, so an older chat's
 * preset is not the current default - and a chat that predates presets entirely carries none rather
 * than falling back to whatever is selected now. A chat with no session yet shows what it has
 * [staged], if anything, since that is what its first turn will be created with.
 *
 * Both the runtime ([ClaudeCodeTarget]) and the UI state ([ClaudeCodeUiState]) answer this
 * question, so the rule lives here once instead of twice.
 */
internal fun resolveSystemPromptId(
    sessionId: String?,
    sessionPromptIds: Map<String, String?>,
    default: String?,
    staged: StagedSystemPrompt? = null,
): String? =
    when {
        sessionId != null && sessionId in sessionPromptIds -> sessionPromptIds[sessionId]
        staged != null -> staged.id
        else -> default
    }

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

    /**
     * Creates a new custom preset, or updates one already saved when [id] names an existing one.
     *
     * An edit replaces the preset where it already sits rather than moving it to the end: there is
     * no reorder action in the picker, so renaming the first of several presets must not shuffle
     * the list under the user.
     *
     * The prompt is clamped to [MAX_SYSTEM_PROMPT_LENGTH] - see there for why a longer one would
     * break Claude Code process starts rather than just this preset.
     */
    fun save(
        name: String,
        prompt: String,
        id: String? = null,
    ): SystemPromptPreset {
        val presetId = id?.takeIf { byId(it)?.builtIn == false } ?: UUID.randomUUID().toString()
        val preset =
            SystemPromptPreset(
                id = presetId,
                name = name,
                prompt = prompt.take(MAX_SYSTEM_PROMPT_LENGTH),
            )
        val existing = mutablePresets.value.indexOfFirst { it.id == preset.id }
        mutablePresets.value =
            if (existing >= 0) {
                mutablePresets.value.toMutableList().apply { set(existing, preset) }
            } else {
                mutablePresets.value + preset
            }
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
