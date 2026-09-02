package com.yugahashimoto.andcode.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

val LocalThemeColors = staticCompositionLocalOf { DarkTheme }

enum class AppTheme(val key: String) {
    DARK("dark"),
    LIGHT("light"),
    ZINC("zinc"),
    MIDNIGHT("midnight"),
    CLAUDE("claude"),
    GHOSTTY("ghostty"),
    AUTO("auto"),
    ;

    companion object {
        fun fromKey(key: String?): AppTheme = entries.firstOrNull { it.key == key } ?: DARK
    }
}

fun themeColorsFor(
    theme: AppTheme,
    systemDark: Boolean,
): ThemeColors =
    when (theme) {
        AppTheme.DARK -> DarkTheme
        AppTheme.LIGHT -> LightTheme
        AppTheme.ZINC -> ZincTheme
        AppTheme.MIDNIGHT -> MidnightTheme
        AppTheme.CLAUDE -> ClaudeTheme
        AppTheme.GHOSTTY -> GhosttyTheme
        AppTheme.AUTO -> if (systemDark) DarkTheme else LightTheme
    }

private fun buildColorScheme(
    tc: ThemeColors,
    isDark: Boolean,
) = if (isDark) {
    darkColorScheme(
        primary = tc.accent,
        onPrimary = tc.accentForeground,
        secondary = tc.secondary,
        onSecondary = tc.surface0,
        background = tc.surface0,
        onBackground = tc.foreground,
        surface = tc.surface1,
        onSurface = tc.foreground,
        surfaceVariant = tc.surface2,
        onSurfaceVariant = tc.foregroundMuted,
        outline = tc.border,
        error = tc.destructive,
        onError = tc.surface0,
        primaryContainer = tc.accent.copy(alpha = 0.14f),
        onPrimaryContainer = tc.accent,
        secondaryContainer = tc.secondary.copy(alpha = 0.14f),
        onSecondaryContainer = tc.secondary,
        // The palettes have no dedicated third hue; leaving these undefined let the Material
        // baseline purple leak through components like the queued chip in every theme.
        tertiary = tc.secondary,
        onTertiary = tc.surface0,
        tertiaryContainer = tc.secondary.copy(alpha = 0.14f),
        onTertiaryContainer = tc.secondary,
        errorContainer = tc.destructive.copy(alpha = 0.14f),
        onErrorContainer = tc.destructive,
        surfaceContainerLowest = tc.surface0,
        surfaceContainerLow = tc.surface1,
        surfaceContainer = tc.surface1,
        surfaceContainerHigh = tc.surface2,
        surfaceContainerHighest = tc.surface3,
        surfaceBright = tc.surface2,
        surfaceDim = tc.surface0,
        outlineVariant = tc.border,
        inverseSurface = tc.surface4,
        inverseOnSurface = tc.foreground,
        inversePrimary = tc.accentBright,
        scrim = tc.surface0.copy(alpha = 0.6f),
    )
} else {
    lightColorScheme(
        primary = tc.accent,
        onPrimary = tc.accentForeground,
        secondary = tc.secondary,
        onSecondary = tc.surface0,
        background = tc.surface0,
        onBackground = tc.foreground,
        surface = tc.surface1,
        onSurface = tc.foreground,
        surfaceVariant = tc.surface2,
        onSurfaceVariant = tc.foregroundMuted,
        outline = tc.border,
        error = tc.destructive,
        onError = tc.surface0,
        primaryContainer = tc.accent.copy(alpha = 0.14f),
        onPrimaryContainer = tc.accent,
        secondaryContainer = tc.secondary.copy(alpha = 0.14f),
        onSecondaryContainer = tc.secondary,
        tertiary = tc.secondary,
        onTertiary = tc.surface0,
        tertiaryContainer = tc.secondary.copy(alpha = 0.14f),
        onTertiaryContainer = tc.secondary,
        errorContainer = tc.destructive.copy(alpha = 0.14f),
        onErrorContainer = tc.destructive,
        surfaceContainerLowest = tc.surface0,
        surfaceContainerLow = tc.surface1,
        surfaceContainer = tc.surface1,
        surfaceContainerHigh = tc.surface2,
        surfaceContainerHighest = tc.surface3,
        surfaceBright = tc.surface1,
        surfaceDim = tc.surface4,
        outlineVariant = tc.border,
        // A light theme inverts against its dark foreground, not against another light surface;
        // tooltips and overlays otherwise rendered as washed-out light gray on light gray.
        inverseSurface = tc.foreground,
        inverseOnSurface = tc.surface0,
        inversePrimary = tc.accent,
        scrim = tc.surface0.copy(alpha = 0.6f),
    )
}

fun buildTypography(
    uiFontSize: Int = 16,
    codeFontSize: Int = 12,
): Typography {
    val scale = uiFontSize / 16f
    return Typography(
        displayLarge = Typography().displayLarge.copy(fontSize = (57 * scale).sp),
        displayMedium = Typography().displayMedium.copy(fontSize = (45 * scale).sp),
        displaySmall = Typography().displaySmall.copy(fontSize = (36 * scale).sp),
        headlineLarge = Typography().headlineLarge.copy(fontSize = (32 * scale).sp),
        headlineMedium = Typography().headlineMedium.copy(fontSize = (28 * scale).sp),
        headlineSmall = Typography().headlineSmall.copy(fontSize = (24 * scale).sp),
        titleLarge = Typography().titleLarge.copy(fontSize = (22 * scale).sp),
        titleMedium = Typography().titleMedium.copy(fontSize = (16 * scale).sp),
        titleSmall = Typography().titleSmall.copy(fontSize = (14 * scale).sp),
        bodyLarge = Typography().bodyLarge.copy(fontSize = (16 * scale).sp),
        bodyMedium = Typography().bodyMedium.copy(fontSize = (14 * scale).sp),
        bodySmall = Typography().bodySmall.copy(fontSize = (12 * scale).sp),
        labelLarge = Typography().labelLarge.copy(fontSize = (14 * scale).sp),
        labelMedium = Typography().labelMedium.copy(fontSize = (12 * scale).sp),
        labelSmall = Typography().labelSmall.copy(fontSize = (11 * scale).sp),
    )
}

@Composable
fun AndCodeTheme(
    appTheme: AppTheme = AppTheme.DARK,
    uiFontSize: Int = 16,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val tc = themeColorsFor(appTheme, systemDark)
    val isDark = appTheme != AppTheme.LIGHT && (appTheme != AppTheme.AUTO || systemDark)
    val colorScheme = buildColorScheme(tc, isDark)
    val typography = buildTypography(uiFontSize)

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            window.statusBarColor = tc.surface0.toArgb()
            window.navigationBarColor = tc.surface0.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDark
                isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    CompositionLocalProvider(
        LocalThemeColors provides tc,
        LocalContentColor provides colorScheme.onSurface,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = typography,
            content = content,
        )
    }
}

@Composable
fun AndCodeAssistantTheme(content: @Composable () -> Unit) = AndCodeTheme(content = content)
