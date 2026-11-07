package app.songyan.ime.keyboard.kana12

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The repeated-tap cycle and the 小゛゜ replacement through the public model API. Effects are the
 * front end contract: append kana, replace the trailing kana with exactly one BackSpace, or
 * insert a literal directly. The model never talks to an engine; ownership is only kept while
 * the front end observes the composition this model predicted.
 *
 * Ported from KeyboardCoreTests.Kana12InputModelTests.
 */
class Kana12InputModelTest {
    private val center = Kana12FlickDirection.Center
    private val left = Kana12FlickDirection.Left
    private val up = Kana12FlickDirection.Up
    private val right = Kana12FlickDirection.Right
    private val down = Kana12FlickDirection.Down

    private fun romaji(kana: String): String =
        requireNotNull(Kana12RomajiTable.romaji(kana)) { "No compiled romaji for $kana" }

    private fun atEnd(composition: String): Kana12Observation =
        Kana12Observation(composition = composition.ifEmpty { null }, caret = composition.length)

    private fun append(
        kana: String,
        romaji: String,
    ): Kana12Effect = Kana12Effect.Append(kana, romaji)

    private fun replace(
        previousKana: String,
        kana: String,
        romaji: String,
    ): Kana12Effect = Kana12Effect.Replace(previousKana, kana, romaji)

    private fun literal(text: String): Kana12Effect = Kana12Effect.Literal(text)

    /**
     * Drives the model the way the keyboard does: read the engine composition, press, then apply
     * the effect so the next press observes the post-write text.
     */
    private class Simulator(
        flickOnly: Boolean = false,
    ) {
        val model = Kana12InputModel(flickOnly)
        var composition = ""
            private set
        private var now = 0.0

        val observed: Kana12Observation
            get() = Kana12Observation(composition.ifEmpty { null }, composition.length)

        fun press(
            rowId: String,
            direction: Kana12FlickDirection = Kana12FlickDirection.Center,
        ): Kana12Effect {
            now += 0.1
            return apply(model.press(rowId, direction, now, observed))
        }

        fun modify(): Kana12Effect {
            now += 0.1
            return apply(model.pressModifier(now, observed))
        }

        private fun apply(effect: Kana12Effect): Kana12Effect {
            when (effect) {
                is Kana12Effect.Append -> composition += effect.kana
                is Kana12Effect.Replace -> composition = composition.dropLast(1) + effect.kana
                is Kana12Effect.Literal -> composition = ""
                Kana12Effect.None -> Unit
            }
            return effect
        }
    }

    private data class AppendCase(
        val rowId: String,
        val direction: Kana12FlickDirection,
        val kana: String,
        val romaji: String,
    )

    private data class EffectCase(
        val rowId: String,
        val direction: Kana12FlickDirection,
        val effect: Kana12Effect,
    )

    @Test
    fun toggleIntervalIsEightTenthsAndFlickOnlyDefaultsOff() {
        assertEquals(0.8, Kana12InputModel.TOGGLE_INTERVAL, 0.0)
        assertFalse(Kana12InputModel().flickOnly)
        assertTrue(Kana12InputModel(flickOnly = true).flickOnly)
    }

    @Test
    fun firstPressAppendsTheTapKanaWithItsValidatedRomaji() {
        val cases =
            listOf(
                AppendCase("a", center, "あ", "a"),
                AppendCase("ka", center, "か", "ka"),
                AppendCase("ha", center, "は", "ha"),
                AppendCase("ya", center, "や", "ya"),
                AppendCase("ta", down, "と", "to"),
                AppendCase("wa", up, "ん", "nn"),
                AppendCase("wa", right, "ー", "-"),
            )
        for (case in cases) {
            val model = Kana12InputModel()
            assertEquals(
                "${case.rowId} ${case.direction}",
                append(case.kana, case.romaji),
                model.press(case.rowId, case.direction, 0.0, null),
            )
        }
    }

    @Test
    fun flickDirectionsProduceTheReferenceGlyphs() {
        val cases =
            listOf(
                EffectCase("a", left, append("い", "i")),
                EffectCase("a", up, append("う", "u")),
                EffectCase("a", right, append("え", "e")),
                EffectCase("a", down, append("お", "o")),
                EffectCase("ya", up, append("ゆ", "yu")),
                EffectCase("ya", down, append("よ", "yo")),
                EffectCase("ya", left, literal("（")),
                EffectCase("ya", right, literal("）")),
                EffectCase("wa", left, append("を", "wo")),
                EffectCase("wa", down, Kana12Effect.None),
                EffectCase("punctuation", center, literal("、")),
                EffectCase("punctuation", left, literal("。")),
                EffectCase("punctuation", up, literal("？")),
                EffectCase("punctuation", right, literal("！")),
                EffectCase("punctuation", down, literal("…")),
            )
        for (case in cases) {
            val model = Kana12InputModel()
            assertEquals(
                "${case.rowId} ${case.direction}",
                case.effect,
                model.press(case.rowId, case.direction, 0.0, null),
            )
        }
    }

    @Test
    fun punctuationAndBracketFlicksAreLiteralsNotRimeKeyTokens() {
        for (
        (rowId, direction) in
        listOf(
            "ya" to left,
            "ya" to right,
            "punctuation" to center,
            "punctuation" to down,
        )
        ) {
            val model = Kana12InputModel()
            val effect = model.press(rowId, direction, 0.0, null)
            assertTrue("$rowId $direction must be a literal effect, got $effect", effect is Kana12Effect.Literal)
            assertEquals(0, effect.backspaces)
            assertNull(effect.romaji)
        }
    }

    @Test
    fun repeatedTapsCycleEveryKanaRowAndWrap() {
        val cycles =
            listOf(
                "a" to listOf("あ", "い", "う", "え", "お", "ぁ", "ぃ", "ぅ", "ぇ", "ぉ"),
                "ka" to listOf("か", "き", "く", "け", "こ", "が", "ぎ", "ぐ", "げ", "ご"),
                "sa" to listOf("さ", "し", "す", "せ", "そ", "ざ", "じ", "ず", "ぜ", "ぞ"),
                "ta" to listOf("た", "ち", "つ", "て", "と", "だ", "ぢ", "づ", "で", "ど"),
                "na" to listOf("な", "に", "ぬ", "ね", "の"),
                "ha" to
                    listOf(
                        "は",
                        "ひ",
                        "ふ",
                        "へ",
                        "ほ",
                        "ば",
                        "び",
                        "ぶ",
                        "べ",
                        "ぼ",
                        "ぱ",
                        "ぴ",
                        "ぷ",
                        "ぺ",
                        "ぽ",
                    ),
                "ma" to listOf("ま", "み", "む", "め", "も"),
                "ya" to listOf("や", "ゆ", "よ", "ゃ", "ゅ", "ょ"),
                "ra" to listOf("ら", "り", "る", "れ", "ろ"),
                "wa" to listOf("わ", "を", "ん", "ー", "ゎ"),
            )
        for ((rowId, cycle) in cycles) {
            val simulator = Simulator()
            for (index in 0..cycle.size) {
                val expectedKana = cycle[index % cycle.size]
                val effect = simulator.press(rowId)
                if (index == 0) {
                    assertEquals(rowId, append(expectedKana, romaji(expectedKana)), effect)
                } else {
                    val previous = cycle[(index - 1) % cycle.size]
                    assertEquals(
                        "$rowId step $index",
                        replace(previous, expectedKana, romaji(expectedKana)),
                        effect,
                    )
                    assertEquals(1, effect.backspaces)
                }
                assertEquals(expectedKana, simulator.composition)
            }
        }
    }

    @Test
    fun toggleStopsAtTheEightTenthsBoundary() {
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.5, observed = Kana12Observation()),
        )
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 1.3, observed = atEnd("あ")),
        )
    }

    @Test
    fun toggleContinuesJustInsideTheBoundary() {
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.5, observed = Kana12Observation()),
        )
        assertEquals(
            replace("あ", "い", "i"),
            model.press("a", at = 1.29, observed = atEnd("あ")),
        )
    }

    @Test
    fun nonMonotonicTimestampStartsAFreshCycle() {
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 1.0, observed = Kana12Observation()),
        )
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.5, observed = atEnd("あ")),
        )
    }

    @Test
    fun anotherKeyEndsTheTapCycle() {
        val simulator = Simulator()
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals(append("か", "ka"), simulator.press("ka"))
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals("あかあ", simulator.composition)
    }

    @Test
    fun flickOnlySuppressesTheTapCycleButNotTheModifier() {
        var simulator = Simulator(flickOnly = true)
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals("ああ", simulator.composition)
        simulator = Simulator(flickOnly = true)
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals(replace("あ", "ぁ", "la"), simulator.modify())
    }

    @Test
    fun tapCycleRestartsAfterAFlick() {
        val simulator = Simulator()
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals(append("い", "i"), simulator.press("a", left))
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals("あいあ", simulator.composition)
    }

    @Test
    fun punctuationKeyDoesNotTapCycle() {
        val simulator = Simulator()
        repeat(3) {
            assertEquals(literal("、"), simulator.press("punctuation"))
            assertEquals("", simulator.composition)
        }
    }

    @Test
    fun modifierCyclesTsuThroughSmallTsuAndDzu() {
        val simulator = Simulator()
        assertEquals(append("つ", "tu"), simulator.press("ta", up))
        assertEquals(replace("つ", "っ", "ltu"), simulator.modify())
        assertEquals(replace("っ", "づ", "du"), simulator.modify())
        assertEquals(replace("づ", "つ", "tu"), simulator.modify())
        assertEquals("つ", simulator.composition)
    }

    @Test
    fun modifierCyclesHaRowDakutenAndHandakuten() {
        val simulator = Simulator()
        assertEquals(append("は", "ha"), simulator.press("ha"))
        assertEquals(replace("は", "ば", "ba"), simulator.modify())
        assertEquals(replace("ば", "ぱ", "pa"), simulator.modify())
        assertEquals(replace("ぱ", "は", "ha"), simulator.modify())
    }

    @Test
    fun modifierCyclesSmallVowels() {
        val simulator = Simulator()
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals(replace("あ", "ぁ", "la"), simulator.modify())
        assertEquals(replace("ぁ", "あ", "a"), simulator.modify())
    }

    @Test
    fun modifierCyclesYoonAndWa() {
        val simulator = Simulator()
        assertEquals(append("や", "ya"), simulator.press("ya"))
        assertEquals(replace("や", "ゃ", "lya"), simulator.modify())
        assertEquals(replace("ゃ", "や", "ya"), simulator.modify())
        val wa = Simulator()
        assertEquals(append("わ", "wa"), wa.press("wa"))
        assertEquals(replace("わ", "ゎ", "lwa"), wa.modify())
        assertEquals(replace("ゎ", "わ", "wa"), wa.modify())
    }

    @Test
    fun modifierAlsoReversesDakuten() {
        val simulator = Simulator()
        assertEquals(append("か", "ka"), simulator.press("ka"))
        assertEquals(replace("か", "が", "ga"), simulator.modify())
        assertEquals(replace("が", "か", "ka"), simulator.modify())
    }

    @Test
    fun modifierDoesNothingForKanaWithoutVariants() {
        val simulator = Simulator()
        assertEquals(append("ん", "nn"), simulator.press("wa", up))
        assertEquals(Kana12Effect.None, simulator.modify())
        assertEquals(Kana12Effect.None, simulator.modify())
        assertEquals("ん", simulator.composition)
        val choonpu = Simulator()
        assertEquals(append("ー", "-"), choonpu.press("wa", right))
        assertEquals(Kana12Effect.None, choonpu.modify())
    }

    @Test
    fun modifierReplacesOnlyTheLastKanaOfTheComposition() {
        val simulator = Simulator()
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals(append("か", "ka"), simulator.press("ka"))
        assertEquals(replace("か", "が", "ga"), simulator.modify())
        assertEquals("あが", simulator.composition)
    }

    @Test
    fun modifierRequiresTheModelsOwnTrailingKana() {
        val model = Kana12InputModel()
        assertEquals(
            Kana12Effect.None,
            model.pressModifier(at = 0.0, observed = Kana12Observation(composition = "か", caret = 1)),
        )
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 1.0, observed = Kana12Observation()),
        )
        assertEquals(
            Kana12Effect.None,
            model.pressModifier(at = 2.0, observed = Kana12Observation(composition = "あか", caret = 2)),
        )
    }

    @Test
    fun modifierRequiresTheSameCaretAndNoSelection() {
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.0, observed = Kana12Observation()),
        )
        assertEquals(
            Kana12Effect.None,
            model.pressModifier(at = 1.0, observed = Kana12Observation(composition = "あ", caret = 0)),
        )
        val selected = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            selected.press("a", at = 0.0, observed = Kana12Observation()),
        )
        assertEquals(
            Kana12Effect.None,
            selected.pressModifier(
                at = 1.0,
                observed = Kana12Observation(composition = "あ", caret = 1, hasSelection = true),
            ),
        )
    }

    /** A composition this model did not produce is foreign: it is never replaced, then or later. */
    @Test
    fun foreignCompositionInvalidatesOwnership() {
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.0, observed = Kana12Observation()),
        )
        // Same length, caret at the end, but the text is not the kana this model appended.
        assertEquals(
            Kana12Effect.None,
            model.pressModifier(at = 1.0, observed = Kana12Observation(composition = "か", caret = 1)),
        )
        // Ownership is gone, so even the predicted composition no longer arms a replacement.
        assertEquals(
            Kana12Effect.None,
            model.pressModifier(at = 2.0, observed = atEnd("あ")),
        )
    }

    @Test
    fun commitOrClearInvalidatesTheModifier() {
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.0, observed = Kana12Observation()),
        )
        assertEquals(Kana12Effect.None, model.pressModifier(at = 1.0, observed = null))
    }

    @Test
    fun synchronizeDropsOwnershipAfterACursorMoveEvenIfTheCaretReturns() {
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.0, observed = Kana12Observation()),
        )
        val end = atEnd("あ")
        model.synchronize(end)
        assertEquals(
            replace("あ", "ぁ", "la"),
            model.pressModifier(at = 1.0, observed = end),
        )
        model.synchronize(Kana12Observation(composition = "ぁ", caret = 0))
        assertEquals(
            Kana12Effect.None,
            model.pressModifier(at = 2.0, observed = Kana12Observation(composition = "ぁ", caret = 1)),
        )
    }

    @Test
    fun synchronizeWithNoCompositionDropsOwnership() {
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.0, observed = Kana12Observation()),
        )
        model.synchronize(null)
        assertEquals(
            Kana12Effect.None,
            model.pressModifier(at = 1.0, observed = Kana12Observation(composition = "あ", caret = 1)),
        )
    }

    @Test
    fun literalEffectEndsCompositionOwnership() {
        val simulator = Simulator()
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals(literal("、"), simulator.press("punctuation"))
        assertEquals(Kana12Effect.None, simulator.modify())
    }

    @Test
    fun unknownObservationNeverReplaces() {
        val model = Kana12InputModel()
        assertEquals(append("あ", "a"), model.press("a", at = 0.0, observed = null))
        assertEquals(append("あ", "a"), model.press("a", at = 0.1, observed = null))
        assertEquals(Kana12Effect.None, model.pressModifier(at = 0.2, observed = null))
    }

    @Test
    fun midCompositionCaretDoesNotArmReplacement() {
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.0, observed = Kana12Observation()),
        )
        val mid = Kana12Observation(composition = "あ", caret = 0)
        assertEquals(append("か", "ka"), model.press("ka", at = 1.0, observed = mid))
        assertEquals(
            Kana12Effect.None,
            model.pressModifier(at = 2.0, observed = Kana12Observation(composition = "かあ", caret = 1)),
        )
    }

    @Test
    fun resetDropsCycleAndOwnership() {
        val simulator = Simulator()
        assertEquals(append("あ", "a"), simulator.press("a"))
        simulator.model.reset()
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals("ああ", simulator.composition)
        val model = Kana12InputModel()
        assertEquals(
            append("あ", "a"),
            model.press("a", at = 0.0, observed = Kana12Observation()),
        )
        model.reset()
        assertEquals(
            Kana12Effect.None,
            model.pressModifier(at = 1.0, observed = Kana12Observation(composition = "あ", caret = 1)),
        )
    }

    @Test
    fun predictionSurvivesReplacementAndKeepsTheLastKana() {
        val simulator = Simulator()
        assertEquals(append("あ", "a"), simulator.press("a"))
        assertEquals(replace("あ", "い", "i"), simulator.press("a"))
        assertEquals(append("か", "ka"), simulator.press("ka"))
        assertEquals(replace("か", "が", "ga"), simulator.modify())
        assertEquals("いが", simulator.composition)
    }

    /**
     * One BackSpace always replaces one kana, whatever the romaji lengths: kagiroi's speller
     * converts inside the composition, so つ (tu) -> っ (ltu) and や (ya) -> ゃ (lya) are one
     * BackSpace each, never one per romaji character.
     */
    @Test
    fun everyReplacementUsesExactlyOneBackspaceAndValidatedRomaji() {
        val replacements = mutableListOf<Kana12Effect>()
        var toggles = 0
        for (rowId in Kana12Layout.rowIds) {
            val cycle = Kana12Layout.toggleCycle(rowId)
            if (cycle.isEmpty()) continue
            val simulator = Simulator()
            repeat(cycle.size + 1) {
                val effect = simulator.press(rowId)
                if (effect is Kana12Effect.Replace) {
                    replacements.add(effect)
                    toggles++
                }
            }
        }
        var modifiers = 0
        for (rowId in Kana12Layout.rowIds) {
            for (direction in Kana12FlickDirection.entries) {
                val kana = Kana12Layout.kana(rowId, direction) ?: continue
                val simulator = Simulator()
                assertEquals(append(kana, romaji(kana)), simulator.press(rowId, direction))
                var previous = kana
                while (true) {
                    val next = Kana12Layout.nextVariant(previous) ?: break
                    val effect = simulator.modify()
                    assertEquals("$previous -> $next", replace(previous, next, romaji(next)), effect)
                    assertEquals(1, effect.backspaces)
                    replacements.add(effect)
                    modifiers++
                    previous = next
                    if (previous == kana) break
                }
            }
        }
        assertEquals(81, toggles)
        assertEquals(64, modifiers)
        assertEquals(145, replacements.size)
        for (effect in replacements) {
            assertEquals(1, effect.backspaces)
            assertTrue(effect is Kana12Effect.Replace)
            val kana = requireNotNull(effect.kana)
            val code = requireNotNull(effect.romaji)
            assertEquals(Kana12RomajiTable.romaji(kana), code)
        }
    }

    @Test
    fun effectAccessorsDescribeTheFrontEndSteps() {
        val append = Kana12Effect.Append(kana = "あ", romaji = "a")
        assertEquals("あ", append.kana)
        assertEquals("a", append.romaji)
        assertEquals(0, append.backspaces)
        val replace = Kana12Effect.Replace(previousKana = "つ", kana = "っ", romaji = "ltu")
        assertEquals("っ", replace.kana)
        assertEquals("ltu", replace.romaji)
        assertEquals(1, replace.backspaces)
        assertNull(Kana12Effect.Literal("、").kana)
        assertNull(Kana12Effect.Literal("、").romaji)
        assertEquals(0, Kana12Effect.None.backspaces)
    }
}
