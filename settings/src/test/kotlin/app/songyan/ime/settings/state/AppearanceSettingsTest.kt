package app.songyan.ime.settings.state

import app.songyan.ime.settings.ui.theme.InMemoryThemePreferences
import app.songyan.ime.settings.ui.theme.ThemeMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppearanceSettingsTest {
    @Test
    fun theInUseAppearanceIsLookedUpById() {
        val state =
            AppearanceSettingsState(
                appearances = listOf(AppearanceOption("builtin-songyan"), AppearanceOption("builtin-bamboo")),
                inUseId = "builtin-bamboo",
            )
        assertEquals("builtin-bamboo", state.inUse?.id)
    }

    @Test
    fun anUnknownInUseIdReadsAsNothingSelected() {
        val state = AppearanceSettingsState(appearances = listOf(AppearanceOption("builtin-songyan")), inUseId = "gone")
        assertNull(state.inUse)
    }

    @Test
    fun exactlyOneColourEntryIsChosenAtATime() {
        val options = listOf(AppearanceOption("builtin-songyan"), AppearanceOption("builtin-bamboo"))
        val following =
            AppearanceSettingsState(
                appearances = options,
                inUseId = "builtin-bamboo",
                dynamicColor = true,
                dynamicColorAvailable = true,
            )
        assertEquals(true, following.wallpaperSelected)
        assertEquals(emptyList<String>(), options.filter { following.isColourSelected(it.id) }.map { it.id })

        val picked = following.copy(dynamicColor = false)
        assertEquals(false, picked.wallpaperSelected)
        assertEquals(listOf("builtin-bamboo"), options.filter { picked.isColourSelected(it.id) }.map { it.id })
    }

    @Test
    fun aStoredWallpaperChoiceDoesNotCountWhereTheDeviceCannotPaintIt() {
        val state =
            AppearanceSettingsState(
                appearances = listOf(AppearanceOption("builtin-songyan")),
                inUseId = "builtin-songyan",
                dynamicColor = true,
                dynamicColorAvailable = false,
            )
        assertEquals(false, state.wallpaperSelected)
        assertEquals(true, state.isColourSelected("builtin-songyan"))
    }

    @Test
    fun themePreferencesStartFromTheSystemChoice() =
        runTest {
            val preferences = InMemoryThemePreferences()
            assertEquals(ThemeMode.SYSTEM, preferences.mode.first())
            assertEquals(true, preferences.dynamicColor.first())
        }

    @Test
    fun themePreferencesKeepWhatIsStored() =
        runTest {
            val preferences = InMemoryThemePreferences()
            preferences.setMode(ThemeMode.DARK)
            preferences.setDynamicColor(false)
            assertEquals(ThemeMode.DARK, preferences.mode.first())
            assertEquals(false, preferences.dynamicColor.first())
        }
}
