package com.yugahashimoto.andcode.feature.chat

import androidx.annotation.StringRes
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.core.api.OpenCodeCommand
import com.yugahashimoto.andcode.core.api.OpenCodeSkill

enum class SlashAction { NEW_CHAT, CLEAR, MODEL, AGENT, ATTACH, HELP }

data class SlashCommand(
    val name: String,
    @StringRes val descriptionRes: Int,
    val action: SlashAction,
)

/**
 * One entry in the composer's slash-command popup: either an app-level command or a command/skill
 * the connected backend advertises. All of them end up inserting `/<name> ` into the input; the
 * send path then routes backend commands and skills through the runtime's command handling.
 *
 * App commands carry a string resource so their description follows the app language; backend
 * entries carry the text the backend advertised.
 */
sealed interface SlashSuggestion {
    val name: String
    val description: String

    @get:StringRes
    val descriptionRes: Int?
        get() = null

    /**
     * [description] is empty by design: UI must resolve [descriptionRes] so the text follows the
     * app language. Only backend entries carry literal text.
     */
    data class App(val command: SlashCommand) : SlashSuggestion {
        override val name: String = command.name
        override val description: String = ""
        override val descriptionRes: Int = command.descriptionRes
    }

    data class Backend(
        override val name: String,
        override val description: String,
        val isSkill: Boolean = false,
    ) : SlashSuggestion
}

object SlashCommandRegistry {
    val commands: List<SlashCommand> =
        listOf(
            SlashCommand("/new", R.string.slash_desc_new, SlashAction.NEW_CHAT),
            SlashCommand("/clear", R.string.slash_desc_clear, SlashAction.CLEAR),
            SlashCommand("/model", R.string.slash_desc_model, SlashAction.MODEL),
            SlashCommand("/agent", R.string.slash_desc_agent, SlashAction.AGENT),
            SlashCommand("/attach", R.string.slash_desc_attach, SlashAction.ATTACH),
            SlashCommand("/help", R.string.slash_desc_help, SlashAction.HELP),
        )

    /**
     * Builds the popup list: app commands first, then the backend's commands and skills, filtering
     * everything by what the user has typed so far.
     */
    fun suggestions(
        query: String,
        backendCommands: List<OpenCodeCommand>,
        backendSkills: List<OpenCodeSkill>,
    ): List<SlashSuggestion> {
        val trimmed = query.trim()

        fun matches(name: String): Boolean = trimmed.isEmpty() || name.startsWith(trimmed, ignoreCase = true)

        val app = commands.filter { matches(it.name) }.map(SlashSuggestion::App)
        val backend =
            backendCommands
                .filter { matches("/${it.name}") }
                .map { SlashSuggestion.Backend("/${it.name}", it.description.orEmpty()) } +
                backendSkills
                    .filter { matches("/${it.name}") }
                    .map { SlashSuggestion.Backend("/${it.name}", it.description.orEmpty(), isSkill = true) }
        return app + backend.sortedBy { it.name }
    }
}
