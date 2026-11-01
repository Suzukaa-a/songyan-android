package app.songyan.ime.settings.state

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsPreferencesTest {
    @Test
    fun `defaults match the keyboard defaults`() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals(SettingsPreferences.DEFAULT_SCHEMA_ID, preferences.activeSchemaId.first())
            assertEquals(SettingsPreferences.DEFAULT_LAYOUT_ID, preferences.layoutId.first())
        }

    @Test
    fun `writes are visible to later readers`() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            preferences.setActiveSchemaId("songyan_flypy")
            preferences.setLayoutId("phone9")
            assertEquals("songyan_flypy", preferences.activeSchemaId.first())
            assertEquals("phone9", preferences.layoutId.first())
        }

    @Test
    fun `holder exposes an installed implementation`() =
        runTest {
            val custom = InMemorySettingsPreferences(layoutId = "merged18")
            SettingsPreferencesHolder.install(custom)
            assertEquals("merged18", SettingsPreferencesHolder.current.layoutId.first())
        }

    @Test
    fun `the wide screen choices default to centred and layout controlled`() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals("center", preferences.widePosition.first())
            assertEquals("layout", preferences.splitMode.first())
            preferences.setWidePosition("right")
            preferences.setSplitMode("on")
            assertEquals("right", preferences.widePosition.first())
            assertEquals("on", preferences.splitMode.first())
        }

    @Test
    fun `readings default to their own row and the inline placement can be chosen`() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals("row", preferences.readingPlacement.first())
            preferences.setReadingPlacement("inline")
            assertEquals("inline", preferences.readingPlacement.first())
        }

    @Test
    fun `the key flick defaults to on and can be switched off`() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals(true, preferences.keyFlick.first())
            preferences.setKeyFlick(false)
            assertEquals(false, preferences.keyFlick.first())
        }

    @Test
    fun `the bottom gap defaults to none and the long press delay to the system`() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals(0, preferences.bottomGapDp.first())
            assertEquals(null, preferences.longPressDelayMs.first())
            preferences.setBottomGapDp(16)
            preferences.setLongPressDelayMs(300)
            assertEquals(16, preferences.bottomGapDp.first())
            assertEquals(300, preferences.longPressDelayMs.first())
            preferences.setLongPressDelayMs(null)
            assertEquals(null, preferences.longPressDelayMs.first())
        }

    @Test
    fun theSwitchKeyIsOffUntilTheUserTurnsItOn() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals(false, preferences.showSwitchKey.first())
            preferences.setShowSwitchKey(true)
            assertEquals(true, preferences.showSwitchKey.first())
        }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsABottomGapOffTheStep() =
        runTest {
            InMemorySettingsPreferences().setBottomGapDp(10)
        }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsALongPressDelayOutOfRange() =
        runTest {
            InMemorySettingsPreferences().setLongPressDelayMs(100)
        }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnUnknownReadingPlacement() =
        runTest {
            InMemorySettingsPreferences().setReadingPlacement("floating")
        }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnUnknownWidePosition() =
        runTest {
            InMemorySettingsPreferences().setWidePosition("top")
        }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnUnknownSplitMode() =
        runTest {
            InMemorySettingsPreferences().setSplitMode("always")
        }
}
