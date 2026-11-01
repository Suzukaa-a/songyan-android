package app.songyan.ime.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control-row policy subset the page geometry needs: slot layout, key exchange per page, the
 * utility position and the width budget. Titles and accessibility labels are host string resources,
 * so they are not part of this type.
 */
class KeyboardControlPolicyTest {
    private fun policy(
        page: KeyboardControlPolicy.Page,
        hasGlobe: Boolean = true,
        isPhoneLayout: Boolean = false,
        isEnglishMode: Boolean = false,
        usesAsciiPunctuation: Boolean = false,
        includesBottomEnter: Boolean = true,
        usesCategorySymbolPage: Boolean = true,
        numericPageReachesSymbols: Boolean = true,
    ): KeyboardControlPolicy =
        KeyboardControlPolicy(
            page = page,
            isEnglishMode = isEnglishMode,
            hasGlobe = hasGlobe,
            isPhoneLayout = isPhoneLayout,
            usesAsciiPunctuation = usesAsciiPunctuation,
            includesBottomEnter = includesBottomEnter,
            usesCategorySymbolPage = usesCategorySymbolPage,
            numericPageReachesSymbols = numericPageReachesSymbols,
        )

    @Test
    fun slotsStayStableAndOnlyGlobeAndEnterDisappear() {
        val full = policy(KeyboardControlPolicy.Page.LETTERS)
        assertEquals(
            listOf(
                KeyboardControlPolicy.Slot.GLOBE,
                KeyboardControlPolicy.Slot.PAGE,
                KeyboardControlPolicy.Slot.LANGUAGE,
                KeyboardControlPolicy.Slot.SPACE,
                KeyboardControlPolicy.Slot.UTILITY,
                KeyboardControlPolicy.Slot.ENTER,
            ),
            full.slots,
        )
        assertEquals(5, policy(KeyboardControlPolicy.Page.LETTERS, hasGlobe = false).slots.size)
        assertEquals(5, policy(KeyboardControlPolicy.Page.NUMERIC, includesBottomEnter = false).slots.size)
        assertEquals(
            listOf(
                KeyboardControlPolicy.Slot.PAGE,
                KeyboardControlPolicy.Slot.LANGUAGE,
                KeyboardControlPolicy.Slot.SPACE,
                KeyboardControlPolicy.Slot.UTILITY,
            ),
            policy(
                KeyboardControlPolicy.Page.NUMERIC,
                hasGlobe = false,
                includesBottomEnter = false,
            ).slots,
        )
        // Every page keeps the same slot list for one surface; the symbol page here is the
        // categorized grid (the default).
        for (page in KeyboardControlPolicy.Page.entries) {
            assertEquals(full.slots, policy(page).slots)
            assertEquals(page == KeyboardControlPolicy.Page.NUMERIC, policy(page).isRowPage)
        }
    }

    @Test
    fun pageAndLanguageKeysExchangePerPage() {
        // A letter page types the comma, except where 123 cannot reach symbols (phone pad, authored
        // 123 without a symbol switch): there the slot opens the symbol page.
        val letters = policy(KeyboardControlPolicy.Page.LETTERS)
        assertEquals(KeyboardControlPolicy.Slot.UTILITY, letters.keyAt(KeyboardControlPolicy.Slot.UTILITY))
        assertEquals(
            KeyboardControlPolicy.Slot.SYMBOL_PAGE,
            policy(KeyboardControlPolicy.Page.LETTERS, numericPageReachesSymbols = false)
                .keyAt(KeyboardControlPolicy.Slot.UTILITY),
        )
        assertEquals(
            KeyboardControlPolicy.Slot.SYMBOL_PAGE,
            policy(KeyboardControlPolicy.Page.LETTERS, isPhoneLayout = true).keyAt(KeyboardControlPolicy.Slot.UTILITY),
        )
        assertEquals(KeyboardControlPolicy.Slot.PAGE, letters.keyAt(KeyboardControlPolicy.Slot.PAGE))

        val numeric = policy(KeyboardControlPolicy.Page.NUMERIC)
        assertEquals(
            KeyboardControlPolicy.Slot.RETURN_TO_LETTERS,
            numeric.keyAt(KeyboardControlPolicy.Slot.PAGE),
        )
        // The row page types the comma where the letter page does: its third row holds the switch.
        assertEquals(KeyboardControlPolicy.Slot.UTILITY, numeric.keyAt(KeyboardControlPolicy.Slot.UTILITY))

        val categorized =
            policy(KeyboardControlPolicy.Page.SYMBOLS, isPhoneLayout = true, includesBottomEnter = false)
        assertEquals(
            KeyboardControlPolicy.Slot.LANGUAGE,
            categorized.keyAt(KeyboardControlPolicy.Slot.PAGE),
        )
        assertEquals(
            KeyboardControlPolicy.Slot.PAGE,
            categorized.keyAt(KeyboardControlPolicy.Slot.LANGUAGE),
        )
        assertEquals(
            KeyboardControlPolicy.Slot.SYMBOL_LOCK,
            categorized.keyAt(KeyboardControlPolicy.Slot.UTILITY),
        )

        val rowsSymbols =
            policy(KeyboardControlPolicy.Page.SYMBOLS, usesCategorySymbolPage = false)
        assertEquals(
            KeyboardControlPolicy.Slot.RETURN_TO_LETTERS,
            rowsSymbols.keyAt(KeyboardControlPolicy.Slot.PAGE),
        )
        assertEquals(KeyboardControlPolicy.Slot.UTILITY, rowsSymbols.keyAt(KeyboardControlPolicy.Slot.UTILITY))

        val phonePad =
            policy(KeyboardControlPolicy.Page.NUMERIC, isPhoneLayout = true, includesBottomEnter = false)
        assertTrue(phonePad.isBundledPhonePad)
        assertEquals(
            KeyboardControlPolicy.Slot.UTILITY,
            phonePad.keyAt(KeyboardControlPolicy.Slot.UTILITY),
        )
    }

    @Test
    fun pageTargetAndLanguageActionFollowThePage() {
        assertEquals(
            KeyboardControlPolicy.Page.SYMBOLS,
            policy(KeyboardControlPolicy.Page.NUMERIC).pageTarget,
        )
        assertEquals(
            KeyboardControlPolicy.Page.NUMERIC,
            policy(KeyboardControlPolicy.Page.LETTERS).pageTarget,
        )
        assertEquals(
            KeyboardControlPolicy.LanguageAction.TOGGLE_LANGUAGE,
            policy(KeyboardControlPolicy.Page.LETTERS).languageAction,
        )
        assertEquals(
            KeyboardControlPolicy.LanguageAction.TOGGLE_SYMBOLS,
            policy(KeyboardControlPolicy.Page.NUMERIC).languageAction,
        )
        assertEquals(
            KeyboardControlPolicy.LanguageAction.RETURN_TO_LETTERS,
            policy(KeyboardControlPolicy.Page.SYMBOLS).languageAction,
        )
        assertEquals(
            KeyboardControlPolicy.LanguageAction.TOGGLE_SYMBOLS,
            policy(KeyboardControlPolicy.Page.SYMBOLS, usesCategorySymbolPage = false).languageAction,
        )
        // An authored behavior overrides the page rule.
        assertEquals(
            KeyboardControlPolicy.LanguageAction.RETURN_TO_LETTERS,
            policy(KeyboardControlPolicy.Page.LETTERS).languageAction(LayoutLanguageSwitchBehavior.LETTERS),
        )
        assertEquals(
            KeyboardControlPolicy.LanguageAction.TOGGLE_SYMBOLS,
            policy(KeyboardControlPolicy.Page.SYMBOLS).languageAction(LayoutLanguageSwitchBehavior.SYMBOLS),
        )
    }

    @Test
    fun utilityTypesTheCommaOnLetterAndRowPagesAndZeroOnThePhoneDigitPad() {
        assertEquals(
            KeyboardControlPolicy.Utility.Digit("0"),
            policy(KeyboardControlPolicy.Page.NUMERIC, isPhoneLayout = true).utility,
        )
        for (ascii in listOf(false, true)) {
            for (page in KeyboardControlPolicy.Page.entries) {
                val policy = policy(page, usesAsciiPunctuation = ascii, usesCategorySymbolPage = false)
                assertEquals(KeyboardControlPolicy.Slot.UTILITY, policy.keyAt(KeyboardControlPolicy.Slot.UTILITY))
                assertEquals(KeyboardControlPolicy.Utility.Punctuation(if (ascii) "," else "，"), policy.utility)
                assertEquals(
                    if (ascii) listOf(".", "?", "!", "'", "\"") else listOf("。", "？", "！", "、", "…"),
                    policy.utilityAlternates,
                )
            }
        }
        // The mark follows the symbol branch, never the input language.
        assertEquals(
            KeyboardControlPolicy.Utility.Punctuation("，"),
            policy(KeyboardControlPolicy.Page.NUMERIC, isEnglishMode = true).utility,
        )
    }

    @Test
    fun budgetFillsTheWidthAndProtectsTheTargets() {
        for (width in listOf(320.0, 382.0, 466.0, 834.0)) {
            for (hasGlobe in listOf(false, true)) {
                val policy = policy(KeyboardControlPolicy.Page.LETTERS, hasGlobe = hasGlobe)
                val metrics = policy.metrics(width)
                val total =
                    policy.slots.sumOf { metrics[it] } + metrics.spacing * (policy.slots.size - 1)
                assertEquals("", width, total, 0.001)
                assertTrue(metrics[KeyboardControlPolicy.Slot.SPACE] > 0.0)
                if (hasGlobe) {
                    assertEquals("", 44.0, metrics[KeyboardControlPolicy.Slot.GLOBE], 0.001)
                } else {
                    assertEquals("", 0.0, metrics[KeyboardControlPolicy.Slot.GLOBE], 0.001)
                }
                assertTrue(metrics[KeyboardControlPolicy.Slot.ENTER] >= 59.999)
            }
        }
    }

    @Test
    fun phoneColumnsPlaceTheFirstKeyUnderTheRailAndFallBackWhenTooNarrow() {
        val aligned =
            policy(
                KeyboardControlPolicy.Page.NUMERIC,
                hasGlobe = false,
                isPhoneLayout = true,
                includesBottomEnter = false,
            ).alignedTo(KeyboardControlPolicy.PhoneColumns(rail = 88.0, digit = 64.0))
        val metrics = aligned.metrics(402.0)
        assertEquals("", 88.0, metrics[KeyboardControlPolicy.Slot.PAGE], 0.001)
        assertEquals("", 88.0, metrics[KeyboardControlPolicy.Slot.RETURN_TO_LETTERS], 0.001)
        assertEquals("", 0.0, metrics[KeyboardControlPolicy.Slot.GLOBE], 0.001)
        val total = aligned.slots.sumOf { metrics[it] } + metrics.spacing * (aligned.slots.size - 1)
        assertEquals("", 402.0, total, 0.001)

        // A rail that does not fit falls back to the generic budget instead of squeezing keys.
        val fallback = aligned.metrics(120.0)
        assertEquals("", 0.0, fallback[KeyboardControlPolicy.Slot.GLOBE], 0.001)
        assertTrue(fallback[KeyboardControlPolicy.Slot.PAGE] > 10.0)
    }

    @Test
    fun equalitiesStayValueBased() {
        assertEquals(policy(KeyboardControlPolicy.Page.LETTERS), policy(KeyboardControlPolicy.Page.LETTERS))
        assertNotEquals(
            policy(KeyboardControlPolicy.Page.LETTERS),
            policy(KeyboardControlPolicy.Page.NUMERIC),
        )
        val aligned =
            policy(KeyboardControlPolicy.Page.NUMERIC).alignedTo(
                KeyboardControlPolicy.PhoneColumns(rail = 1.0, digit = 2.0),
            )
        assertNull(policy(KeyboardControlPolicy.Page.NUMERIC).phoneColumns)
        assertEquals(1.0, aligned.phoneColumns?.rail ?: 0.0, 0.001)
        assertFalse(aligned == policy(KeyboardControlPolicy.Page.NUMERIC))
    }
}
