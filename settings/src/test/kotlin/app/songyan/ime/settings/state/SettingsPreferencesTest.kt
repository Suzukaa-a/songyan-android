package app.songyan.ime.settings.state

import app.songyan.ime.candidate.ToolbarItem
import app.songyan.ime.candidate.ToolbarLayout
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
    fun `the key flick defaults to up and can be turned down or off`() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals("up", preferences.keyFlickDirection.first())
            preferences.setKeyFlickDirection("down")
            assertEquals("down", preferences.keyFlickDirection.first())
            preferences.setKeyFlickDirection("off")
            assertEquals("off", preferences.keyFlickDirection.first())
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
    fun theSplitWidthAndSideMarginHaveTheirDefaultsAndStoreTheChoice() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals(600, preferences.splitWidthDp.first())
            assertEquals(0, preferences.sideMarginDp.first())
            preferences.setSplitWidthDp(840)
            preferences.setSideMarginDp(24)
            assertEquals(840, preferences.splitWidthDp.first())
            assertEquals(24, preferences.sideMarginDp.first())
        }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsASplitWidthOffTheStep() =
        runTest {
            InMemorySettingsPreferences().setSplitWidthDp(610)
        }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsASideMarginOutOfRange() =
        runTest {
            InMemorySettingsPreferences().setSideMarginDp(100)
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

    @Test
    fun `the custom wide position is kept while another choice is in use and snaps to 0 to 100`() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals(50, preferences.widePositionCustom.first())
            preferences.setWidePositionCustom(25)
            preferences.setWidePosition("custom")
            preferences.setWidePosition("left")
            assertEquals(25, preferences.widePositionCustom.first())
            preferences.setWidePositionCustom(130)
            assertEquals(100, preferences.widePositionCustom.first())
            preferences.setWidePositionCustom(-4)
            assertEquals(0, preferences.widePositionCustom.first())
        }

    @Test
    fun `a stored wide choice maps to its percent so old stores need no migration`() {
        assertEquals(0, SettingsPreferences.widePositionPercent("left", 70))
        assertEquals(50, SettingsPreferences.widePositionPercent("center", 70))
        assertEquals(100, SettingsPreferences.widePositionPercent("right", 70))
        assertEquals(70, SettingsPreferences.widePositionPercent("custom", 70))
        assertEquals(50, SettingsPreferences.widePositionPercent("custom", null))
        assertEquals(100, SettingsPreferences.widePositionPercent("custom", 900))
        assertEquals(50, SettingsPreferences.widePositionPercent(null, 70))
        assertEquals(50, SettingsPreferences.widePositionPercent("top", 70))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsAnUnknownSplitMode() =
        runTest {
            InMemorySettingsPreferences().setSplitMode("always")
        }

    @Test
    fun `the toolbar choice defaults to nothing stored and a stored choice round-trips`() =
        runTest {
            val preferences = InMemorySettingsPreferences()
            assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.parse(preferences.toolbarLayout.first()))
            val chosen = ToolbarLayout.DEFAULT.withShown(ToolbarItem.EMOJI, false).moveEarlier(ToolbarItem.EDIT)
            preferences.setToolbarLayout(chosen.toWire())
            assertEquals(chosen, ToolbarLayout.parse(preferences.toolbarLayout.first()))
            preferences.setToolbarLayout(null)
            assertEquals(ToolbarLayout.DEFAULT, ToolbarLayout.parse(preferences.toolbarLayout.first()))
        }
}
