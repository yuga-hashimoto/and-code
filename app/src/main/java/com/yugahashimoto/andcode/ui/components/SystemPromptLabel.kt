package com.yugahashimoto.andcode.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yugahashimoto.andcode.R
import com.yugahashimoto.andcode.runtime.local.ClaudeSystemPrompts
import com.yugahashimoto.andcode.runtime.local.SystemPromptPreset

/**
 * A preset's name as the user should read it.
 *
 * A built-in preset's [SystemPromptPreset.name] is the English fallback the runtime layer ships;
 * every other user-visible label in the app comes from resources, so the built-ins are translated
 * here rather than being the one English string on the screen. The prompt *text* stays English on
 * purpose - it is an instruction to the model, not UI copy, and the model answers in the user's
 * language either way. A preset the user wrote is shown exactly as they named it.
 */
@Composable
fun systemPromptPresetLabel(preset: SystemPromptPreset): String =
    when {
        !preset.builtIn -> preset.name
        preset.id == ClaudeSystemPrompts.CODING -> stringResource(R.string.system_prompt_builtin_coding)
        preset.id == ClaudeSystemPrompts.DEBUG -> stringResource(R.string.system_prompt_builtin_debug)
        preset.id == ClaudeSystemPrompts.RESEARCH -> stringResource(R.string.system_prompt_builtin_research)
        preset.id == ClaudeSystemPrompts.CREATIVE -> stringResource(R.string.system_prompt_builtin_creative)
        else -> preset.name
    }
