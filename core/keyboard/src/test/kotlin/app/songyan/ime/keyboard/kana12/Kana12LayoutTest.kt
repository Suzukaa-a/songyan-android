package app.songyan.ime.keyboard.kana12

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the reference grid and its cycles: 4x3 kana keys, flick directions exact, tap cycles,
 * and the 小゛゜ variant chain per row.
 *
 * Ported from KeyboardCoreTests.Kana12LayoutTests.
 */
class Kana12LayoutTest {
    private val center = Kana12FlickDirection.Center
    private val left = Kana12FlickDirection.Left
    private val up = Kana12FlickDirection.Up
    private val right = Kana12FlickDirection.Right
    private val down = Kana12FlickDirection.Down

    private fun kana(value: String): Kana12KeyOutput = Kana12KeyOutput.Kana(value)

    private fun literal(value: String): Kana12KeyOutput = Kana12KeyOutput.Literal(value)

    private val expectedOutputs: Map<String, Map<Kana12FlickDirection, Kana12KeyOutput>> =
        mapOf(
            "a" to
                mapOf(
                    center to kana("あ"),
                    left to kana("い"),
                    up to kana("う"),
                    right to kana("え"),
                    down to kana("お"),
                ),
            "ka" to
                mapOf(
                    center to kana("か"),
                    left to kana("き"),
                    up to kana("く"),
                    right to kana("け"),
                    down to kana("こ"),
                ),
            "sa" to
                mapOf(
                    center to kana("さ"),
                    left to kana("し"),
                    up to kana("す"),
                    right to kana("せ"),
                    down to kana("そ"),
                ),
            "ta" to
                mapOf(
                    center to kana("た"),
                    left to kana("ち"),
                    up to kana("つ"),
                    right to kana("て"),
                    down to kana("と"),
                ),
            "na" to
                mapOf(
                    center to kana("な"),
                    left to kana("に"),
                    up to kana("ぬ"),
                    right to kana("ね"),
                    down to kana("の"),
                ),
            "ha" to
                mapOf(
                    center to kana("は"),
                    left to kana("ひ"),
                    up to kana("ふ"),
                    right to kana("へ"),
                    down to kana("ほ"),
                ),
            "ma" to
                mapOf(
                    center to kana("ま"),
                    left to kana("み"),
                    up to kana("む"),
                    right to kana("め"),
                    down to kana("も"),
                ),
            "ya" to
                mapOf(
                    center to kana("や"),
                    left to literal("（"),
                    up to kana("ゆ"),
                    right to literal("）"),
                    down to kana("よ"),
                ),
            "ra" to
                mapOf(
                    center to kana("ら"),
                    left to kana("り"),
                    up to kana("る"),
                    right to kana("れ"),
                    down to kana("ろ"),
                ),
            // The reference layout gives わ行 only tap/left/up/right; down has no verified glyph, so it stays
            // empty instead of inventing ～ or ゎ on a flick.
            "wa" to
                mapOf(
                    center to kana("わ"),
                    left to kana("を"),
                    up to kana("ん"),
                    right to kana("ー"),
                ),
            "punctuation" to
                mapOf(
                    center to literal("、"),
                    left to literal("。"),
                    up to literal("？"),
                    right to literal("！"),
                    down to literal("…"),
                ),
        )

    private val expectedToggleCycles: Map<String, List<String>> =
        mapOf(
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
            "punctuation" to emptyList(),
        )

    /** The 小゛゜ chain for every row; only these kana react to the modifier. */
    private val expectedVariantCycles: List<List<String>> =
        listOf(
            listOf("あ", "ぁ"),
            listOf("い", "ぃ"),
            listOf("う", "ぅ"),
            listOf("え", "ぇ"),
            listOf("お", "ぉ"),
            listOf("か", "が"),
            listOf("き", "ぎ"),
            listOf("く", "ぐ"),
            listOf("け", "げ"),
            listOf("こ", "ご"),
            listOf("さ", "ざ"),
            listOf("し", "じ"),
            listOf("す", "ず"),
            listOf("せ", "ぜ"),
            listOf("そ", "ぞ"),
            listOf("た", "だ"),
            listOf("ち", "ぢ"),
            listOf("つ", "っ", "づ"),
            listOf("て", "で"),
            listOf("と", "ど"),
            listOf("は", "ば", "ぱ"),
            listOf("ひ", "び", "ぴ"),
            listOf("ふ", "ぶ", "ぷ"),
            listOf("へ", "べ", "ぺ"),
            listOf("ほ", "ぼ", "ぽ"),
            listOf("や", "ゃ"),
            listOf("ゆ", "ゅ"),
            listOf("よ", "ょ"),
            listOf("わ", "ゎ"),
        )

    @Test
    fun rowIdsMatchTheUiContract() {
        assertEquals(
            listOf("a", "ka", "sa", "ta", "na", "ha", "ma", "ya", "ra", "wa", "punctuation"),
            Kana12Layout.rowIds,
        )
    }

    @Test
    fun flickGridMatchesTheReferenceLayoutExactly() {
        for (rowId in Kana12Layout.rowIds) {
            for (direction in Kana12FlickDirection.entries) {
                assertEquals(
                    "$rowId $direction",
                    expectedOutputs[rowId]?.get(direction),
                    Kana12Layout.output(rowId, direction),
                )
            }
        }
    }

    @Test
    fun unknownRowsAndDirectionsHaveNoOutput() {
        assertNull(Kana12Layout.output("modifier", center))
        assertNull(Kana12Layout.output("unknown", left))
    }

    @Test
    fun kanaAndLiteralHelpersSplitTheGridOutput() {
        assertNull(Kana12Layout.kana("ya", left))
        assertEquals("（", Kana12Layout.literal("ya", left))
        assertEquals("）", Kana12Layout.literal("ya", right))
        assertEquals("ー", Kana12Layout.kana("wa", right))
        assertNull(Kana12Layout.kana("wa", down))
        assertNull(Kana12Layout.output("modifier", center))
    }

    @Test
    fun tapCyclesMatchTheReferenceRows() {
        for (rowId in Kana12Layout.rowIds) {
            assertEquals(rowId, expectedToggleCycles[rowId], Kana12Layout.toggleCycle(rowId))
        }
        for ((rowId, cycle) in expectedToggleCycles) {
            for (kana in cycle) {
                assertNotNull("$rowId: $kana", Kana12RomajiTable.romaji(kana))
            }
        }
    }

    @Test
    fun variantChainsMatchTheVerifiedCycles() {
        for (cycle in expectedVariantCycles) {
            assertEquals(cycle, followVariantChain(cycle.first()))
            for (kana in cycle) {
                assertNotNull(kana, Kana12RomajiTable.romaji(kana))
            }
        }
    }

    @Test
    fun kanaWithoutVariantsDoNotReactToTheModifier() {
        val variantFree =
            listOf(
                "な",
                "に",
                "ぬ",
                "ね",
                "の",
                "ま",
                "み",
                "む",
                "め",
                "も",
                "ら",
                "り",
                "る",
                "れ",
                "ろ",
                "を",
                "ん",
                "ー",
            )
        for (kana in variantFree) {
            assertNull(kana, Kana12Layout.nextVariant(kana))
        }
        for (
        (kana, next) in
        listOf(
            "つ" to "っ",
            "っ" to "づ",
            "づ" to "つ",
            "は" to "ば",
            "ば" to "ぱ",
            "ぱ" to "は",
            "あ" to "ぁ",
            "ぁ" to "あ",
            "わ" to "ゎ",
            "ゎ" to "わ",
        )
        ) {
            assertEquals(kana, next, Kana12Layout.nextVariant(kana))
        }
        assertNull(Kana12Layout.nextVariant("ゔ"))
    }

    private fun followVariantChain(start: String): List<String> {
        val chain = mutableListOf(start)
        var current = start
        while (true) {
            val next = Kana12Layout.nextVariant(current) ?: break
            if (next == start || next in chain) break
            chain.add(next)
            current = next
        }
        return chain
    }
}
