package app.songyan.ime.ime.palette

import app.songyan.ime.appearance.AppearanceDocument
import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.appearance.SkinAppearance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteSelectionTest {
    @Test
    fun withoutAppGraphSettingsItFailsClosedToTheAndroidDefault() {
        val selection = PaletteSelection.of(settings = null, sdkInt = 31, systemDark = false)
        assertEquals(AppearancePresets.androidDefault, selection.document)
        assertFalse(selection.dynamic)
        assertFalse(selection.dark)
    }

    @Test
    fun withoutAppGraphSettingsTheSystemNightModeStillDecides() {
        assertTrue(PaletteSelection.of(settings = null, sdkInt = 30, systemDark = true).dark)
    }

    @Test
    fun dynamicColourNeedsBothTheChoiceAndAndroidTwelve() {
        val settings = KeyboardAppearanceSettings(document = AppearancePresets.androidDefault, dynamicColor = true)
        assertFalse(PaletteSelection.of(settings, sdkInt = 30, systemDark = false).dynamic)
        assertTrue(PaletteSelection.of(settings, sdkInt = 31, systemDark = false).dynamic)
    }

    @Test
    fun dynamicColourStaysOffWhenTheUserTurnedItOff() {
        val settings = KeyboardAppearanceSettings(document = AppearancePresets.androidDefault, dynamicColor = false)
        assertFalse(PaletteSelection.of(settings, sdkInt = 36, systemDark = false).dynamic)
    }

    @Test
    fun aCustomAppearanceKeepsItsOwnLightOrDarkChoice() {
        val dark = AppearancePresets.androidDefault.copy(appearance = SkinAppearance.DARK)
        val light = AppearancePresets.androidDefault.copy(appearance = SkinAppearance.LIGHT)
        assertTrue(PaletteSelection.of(settingsOf(dark), sdkInt = 36, systemDark = false).dark)
        assertFalse(PaletteSelection.of(settingsOf(light), sdkInt = 36, systemDark = true).dark)
    }

    @Test
    fun theSystemDefaultAppearanceFollowsTheKeyboardSurfaceNotAFieldHint() {
        val forcedDark = AppearanceDocument.systemDefault.copy(appearance = SkinAppearance.DARK)
        assertTrue(PaletteSelection.of(settingsOf(forcedDark), sdkInt = 36, systemDark = true).dark)
        assertFalse(PaletteSelection.of(settingsOf(forcedDark), sdkInt = 36, systemDark = false).dark)
    }

    @Test
    fun anExplicitThemeChoiceOverridesTheDocument() {
        val lightDocument = AppearancePresets.androidDefault.copy(appearance = SkinAppearance.LIGHT)
        val forced =
            KeyboardAppearanceSettings(document = lightDocument, dynamicColor = false, forcedDark = true)
        assertTrue(PaletteSelection.of(forced, sdkInt = 36, systemDark = false).dark)
    }

    @Test
    fun aPreviewVariantIgnoresTheThemeChoice() {
        val forced =
            KeyboardAppearanceSettings(
                document = AppearancePresets.androidDefault,
                dynamicColor = false,
                forcedDark = true,
            )
        assertFalse(PaletteSelection.variant(forced, sdkInt = 36, dark = false).dark)
        assertTrue(PaletteSelection.variant(forced, sdkInt = 36, dark = true).dark)
    }

    private fun settingsOf(document: AppearanceDocument): KeyboardAppearanceSettings =
        KeyboardAppearanceSettings(document = document, dynamicColor = false)
}
