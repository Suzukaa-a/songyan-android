package app.songyan.ime.settings.ui

import app.songyan.ime.keyboard.support.SettingsTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMotionTest {
    @Test
    fun bothPagesSlideThirtyDpTogetherOverThreeHundredMilliseconds() {
        assertEquals(30, SettingsMotion.SLIDE_DP)
        assertEquals(300, SettingsMotion.DURATION_MS)
    }

    @Test
    fun theOldPageIsGoneBeforeTheNewOneFadesIn() {
        // Shared axis: the fades never overlap, so the two pages never show through each other.
        assertEquals(105, SettingsMotion.FADE_OUT_MS)
        assertTrue(SettingsMotion.FADE_OUT_MS < SettingsMotion.DURATION_MS / 2)
    }

    @Test
    fun everyKeyboardRequestHasItsOwnRoute() {
        val routes = SettingsTarget.entries.map { SettingsRoutes.of(it) }
        assertEquals(routes.size, routes.toSet().size)
        assertEquals(SettingsRoutes.HOME, SettingsRoutes.of(SettingsTarget.HOME))
        assertTrue(SettingsRoutes.of(SettingsTarget.ADD_PHRASE).endsWith("=true"))
    }

    @Test
    fun anUnknownTargetOpensHome() {
        assertEquals(SettingsTarget.HOME, SettingsTarget.fromId(null))
        assertEquals(SettingsTarget.HOME, SettingsTarget.fromId("nonsense"))
        assertEquals(SettingsTarget.ADD_PHRASE, SettingsTarget.fromId("add_phrase"))
    }

    @Test
    fun subPagesHighlightTheSectionTheyCameFrom() {
        assertEquals(SettingsRoutes.OPTIONS, SettingsRoutes.topLevelOf(SettingsRoutes.FEATURES))
        assertEquals(SettingsRoutes.ABOUT, SettingsRoutes.topLevelOf(SettingsRoutes.LICENSES))
        assertEquals(SettingsRoutes.ABOUT, SettingsRoutes.topLevelOf(SettingsRoutes.LICENSE_DETAIL))
        assertEquals(SettingsRoutes.APPEARANCE, SettingsRoutes.topLevelOf(SettingsRoutes.APPEARANCE))
        assertEquals(null, SettingsRoutes.topLevelOf(null))
    }
}
