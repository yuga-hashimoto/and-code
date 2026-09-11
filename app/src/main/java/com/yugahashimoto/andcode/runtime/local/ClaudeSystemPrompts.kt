package com.yugahashimoto.andcode.runtime.local

import kotlinx.serialization.Serializable

/**
 * One system-prompt preset for Claude Code.
 *
 * Sent as `--append-system-prompt`, which layers onto Claude's own system prompt rather than
 * replacing it, so a preset only needs to say what makes this mode different - not restate
 * everything Claude already knows how to do.
 *
 * [builtIn] presets ship with the app: they are never persisted or deletable, so wording can be
 * improved in a later release without stranding a user's own saved presets. Only their [id] is
 * ever written to disk, as the current selection.
 */
@Serializable
data class SystemPromptPreset(
    val id: String,
    val name: String,
    val prompt: String,
    val builtIn: Boolean = false,
)

/**
 * The presets Claude Code offers out of the box - the per-purpose prompts requested in issue #294
 * (Coding, Debug, Research, Creative), alongside whatever custom ones the user has saved.
 */
object ClaudeSystemPrompts {
    /**
     * Ids are the contract: they are what a selection persists as, and what the UI maps to a
     * translated name. [SystemPromptPreset.name] on a built-in is only the untranslated fallback.
     */
    const val CODING = "coding"
    const val DEBUG = "debug"
    const val RESEARCH = "research"
    const val CREATIVE = "creative"

    val BUILT_IN =
        listOf(
            SystemPromptPreset(
                id = CODING,
                name = "Coding",
                prompt =
                    "Focus on shipping correct, minimal changes. Follow the existing code's " +
                        "conventions and style, prefer the smallest diff that solves the problem, and " +
                        "add or update tests for anything you change.",
                builtIn = true,
            ),
            SystemPromptPreset(
                id = DEBUG,
                name = "Debug",
                prompt =
                    "Focus on root-causing the reported problem before changing anything. Reproduce " +
                        "the issue, explain what is actually happening and why, and only then propose " +
                        "the smallest fix that addresses the root cause rather than the symptom.",
                builtIn = true,
            ),
            SystemPromptPreset(
                id = RESEARCH,
                name = "Research",
                prompt =
                    "Focus on gathering and summarizing accurate information rather than writing " +
                        "code. Read broadly before concluding, note where sources disagree, and cite the " +
                        "files, docs, or commands you relied on.",
                builtIn = true,
            ),
            SystemPromptPreset(
                id = CREATIVE,
                name = "Creative",
                prompt =
                    "Feel free to explore multiple approaches and offer original, opinionated ideas " +
                        "rather than defaulting to the single safest option. Explain the trade-offs " +
                        "between the approaches you considered.",
                builtIn = true,
            ),
        )
}
