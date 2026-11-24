package com.yugahashimoto.andcode.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class DynamicThemeTest {
    @Test
    fun `the dynamic key resolves to the dynamic theme`() {
        assertEquals(AppTheme.DYNAMIC, AppTheme.fromKey("dynamic"))
    }

    @Test
    fun `without a wallpaper scheme the dynamic theme uses the auto palette`() {
        assertEquals(DarkTheme, themeColorsFor(AppTheme.DYNAMIC, systemDark = true))
        assertEquals(LightTheme, themeColorsFor(AppTheme.DYNAMIC, systemDark = false))
    }

    @Test
    fun `a wallpaper scheme recolors surfaces and accent but keeps the meaningful hues`() {
        val scheme =
            darkColorScheme(
                primary = Color(0xFF112233),
                background = Color(0xFF010203),
                error = Color(0xFF440000),
            )

        val colors = themeColorsFromScheme(scheme, DarkTheme)

        assertEquals(scheme.primary, colors.accent)
        assertEquals(scheme.background, colors.surface0)
        assertEquals(scheme.error, colors.destructive)
        assertEquals(DarkTheme.success, colors.success)
        assertEquals(DarkTheme.warning, colors.warning)
        assertEquals(DarkTheme.diffAddition, colors.diffAddition)
        assertEquals(DarkTheme.diffDeletion, colors.diffDeletion)
        assertEquals(DarkTheme.statusRunning, colors.statusRunning)
        assertEquals(DarkTheme.statusError, colors.statusError)
    }
}
