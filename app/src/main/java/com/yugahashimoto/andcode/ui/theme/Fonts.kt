package com.yugahashimoto.andcode.ui.theme

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.yugahashimoto.andcode.R

/**
 * Bundled JetBrains Mono (the "NL" cut, without ligatures, so `!=` and `->` read as typed) for code,
 * diffs and the terminal. The platform's monospace font varies by vendor and is often a poor fit for
 * code at small sizes; shipping one font makes code look the same on every device.
 */
val CodeFontFamily =
    FontFamily(
        Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
        Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
    )
