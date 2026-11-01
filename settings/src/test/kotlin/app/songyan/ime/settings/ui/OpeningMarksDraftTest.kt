package app.songyan.ime.settings.ui

import app.songyan.ime.layout.KeyboardNumericMarks
import app.songyan.ime.layout.KeyboardPagePresets
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutKeyTitleMode
import app.songyan.ime.settings.ui.screens.keyWithAsciiText
import app.songyan.ime.settings.ui.screens.keyWithTypedLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page editor lets a copy choose where it opens and set both variants of a mark. */
class OpeningMarksDraftTest {
    @Test
    fun aCopyOfTheAutomaticPagesFollowsTheInputUntilTheUserPicksABranch() {
        val numeric = PageDraft(KeyboardPagePresets.numericAutomatic.duplicated("mine", "我的"))
        assertTrue(numeric.choosesOpeningMarks)
        assertNull(numeric.openingMarks)
        assertEquals(
            KeyboardNumericMarks.ASCII,
            numeric.withOpeningMarks(KeyboardNumericMarks.ASCII).openingMarks,
        )
        assertTrue(numeric.withOpeningMarks(KeyboardNumericMarks.ASCII).hasChanges)
        assertNull(numeric.withOpeningMarks(KeyboardNumericMarks.ASCII).withOpeningMarks(null).openingMarks)
        assertTrue(PageDraft(KeyboardPagePresets.symbolsSystem.duplicated("mine", "我的")).choosesOpeningMarks)
    }

    @Test
    fun theCategorizedAndEnglishPagesHaveNoBranchToChoose() {
        assertFalse(PageDraft(KeyboardPagePresets.symbolsCategorized.duplicated("c", "分类")).choosesOpeningMarks)
        assertFalse(PageDraft(KeyboardPagePresets.englishQwerty.duplicated("e", "英文")).choosesOpeningMarks)
    }

    @Test
    fun settingTheEnglishVariantMakesTheKeyShowWhatItTypes() {
        val plain = LayoutKey("，", action = LayoutAction.literal("，"))
        val both = keyWithAsciiText(plain, ",")
        assertEquals(",", both.action.asciiValue)
        assertEquals("，", both.face(false))
        assertEquals(",", both.face(true))
        // Clearing the variant leaves one text for both branches.
        assertNull(keyWithAsciiText(both, "").action.asciiValue)
    }

    @Test
    fun aTitleTypedByHandWinsOverTheAutomaticOne() {
        val both = keyWithAsciiText(LayoutKey("，", action = LayoutAction.literal("，")), ",")
        val named = keyWithTypedLabel(both, "逗号")
        assertEquals(LayoutKeyTitleMode.CUSTOM, named.titleMode)
        assertEquals("逗号", named.face(true))
        assertEquals("x", keyWithTypedLabel(LayoutKey("a", action = LayoutAction.literal("a")), "x").label)
    }
}
