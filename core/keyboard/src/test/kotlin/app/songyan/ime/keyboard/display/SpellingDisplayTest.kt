package app.songyan.ime.keyboard.display

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.CompositionDisplayMode
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.TextSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Ported from KeyboardCoreTests.SpellingDisplayTests: only engine evidence may be displayed. */
class SpellingDisplayTest {
    private fun rewrite(
        preedit: String,
        start: Int,
        end: Int,
        caret: Int,
        spelling: String,
    ): Pair<String, Int>? = SpellingDisplay.rewrite(preedit, start, end, caret, spelling)

    @Test
    fun replacesOnlyTheEvidencedSegment() {
        assertEquals("ni", rewrite("bu", 0, 2, 2, "ni")?.first)
        assertEquals("ni", rewrite("64", 0, 2, 2, "ni")?.first)
        assertEquals("bu gou cheng", rewrite("bu g c", 0, 6, 6, "bu gou cheng")?.first)
    }

    @Test
    fun tailCodeAfterTheHighlightedSegmentIsPreserved() {
        val result = rewrite("bu gc", 0, 2, 2, "ni")
        assertEquals("ni gc", result?.first)
        assertEquals("ni".length, result?.second)
    }

    @Test
    fun confirmedTextAndPunctuationBeforeTheSegmentArePreserved() {
        val preedit = "你，gc"
        val start = "你，".toByteArray(Charsets.UTF_8).size
        val end = preedit.toByteArray(Charsets.UTF_8).size
        val result = rewrite(preedit, start, end, end, "hao")
        assertEquals("你，hao", result?.first)
        assertEquals("你，hao".length, result?.second)
    }

    @Test
    fun segmentedConfirmedPrefixIsPreserved() {
        assertEquals("你hao", rewrite("你hao", 3, 6, 6, "hao")?.first)
        assertEquals("你好shi jian", rewrite("你好shij", 6, 10, 10, "shi jian")?.first)
    }

    @Test
    fun caretBeforeTheSegmentIsUnchanged() {
        assertEquals(0, rewrite("bu gc", 0, 2, 0, "ni")?.second)
    }

    @Test
    fun caretInTheTailShiftsByTheDelta() {
        val result = rewrite("bu gc", 0, 2, 4, "ni hao")
        assertEquals("ni hao gc", result?.first)
        assertEquals(8, result?.second)
    }

    @Test
    fun caretInsideTheSegmentMovesToItsEnd() {
        assertEquals(6, rewrite("bu gc", 0, 2, 1, "ni hao")?.second)
    }

    @Test
    fun multibyteCaretMapsThroughUtf16() {
        val preedit = "你好x"
        val start = "你好".toByteArray(Charsets.UTF_8).size
        val end = preedit.toByteArray(Charsets.UTF_8).size
        val result = rewrite(preedit, start, end, end, "hao")
        assertEquals("你好hao", result?.first)
        assertEquals("你好hao".length, result?.second)
    }

    @Test
    fun invalidRangesReturnNullAndFallBackToRawPreedit() {
        assertNull(rewrite("bu", 2, 1, 2, "ni"))
        assertNull(rewrite("bu", 0, 9, 2, "ni"))
        assertNull(rewrite("bu", -1, 2, 2, "ni"))
        assertNull(rewrite("bu", 0, 2, 9, "ni"))
        // A byte offset inside a multi-byte scalar is not a character boundary.
        assertNull(rewrite("你好", 1, 6, 6, "ni"))
    }

    @Test
    fun markedCompositionFallsBackWhenNoTrustedSpelling() {
        val snapshot =
            EngineSnapshot(
                candidates = listOf(Candidate(0, "你", comment = "［ni］")),
                preedit = "bu",
                rawInput = "bu",
                isComposing = true,
            ).copy(
                highlightedSpelling = "",
                preeditSelStart = 0,
                preeditSelEnd = 2,
                preeditCaretByte = 2,
                preeditSelection = TextSelection(2, 0),
            )
        val marked = MarkedComposition(snapshot, spellingPreedit = true)
        assertEquals("bu", marked.text)
        assertEquals(TextSelection(2, 0), marked.selection)

        // A broken comment must never be parsed as pinyin.
        val polluted = snapshot.copy(candidates = listOf(Candidate(0, "你", comment = "英语 hello 你好")))
        assertEquals("bu", MarkedComposition(polluted, spellingPreedit = true).text)
    }

    @Test
    fun fourteenAndNineKeyMarkedTextFollowTheCandidate() {
        val base =
            EngineSnapshot(
                candidates = listOf(Candidate(0, "你")),
                preedit = "bu",
                rawInput = "bu",
                isComposing = true,
            ).copy(
                highlightedSpelling = "ni",
                preeditSelStart = 0,
                preeditSelEnd = 2,
                preeditCaretByte = 2,
                preeditSelection = TextSelection(2, 0),
            )
        assertEquals("ni", MarkedComposition(base, spellingPreedit = true).text)

        val nineKey = base.copy(preedit = "64", rawInput = "64", preeditSelEnd = 2)
        assertEquals("ni", MarkedComposition(nineKey, spellingPreedit = true).text)
        // Raw engine state is untouched by presentation.
        assertEquals("64", nineKey.rawInput)
        assertEquals("64", nineKey.preedit)
    }

    @Test
    fun twentySixFullPinyinDoesNotUseTheSpellingPath() {
        val snapshot =
            EngineSnapshot(
                candidates = listOf(Candidate(0, "你好")),
                preedit = "ni hao",
                rawInput = "nihao",
                isComposing = true,
            ).copy(
                highlightedSpelling = "ni hao",
                preeditSelStart = 0,
                preeditSelEnd = 6,
                preeditCaretByte = 6,
                preeditSelection = TextSelection(6, 0),
            )
        assertEquals("ni hao", MarkedComposition(snapshot, spellingPreedit = false).text)
    }

    @Test
    fun authorizedCanonicalCapabilityNeverEchoesNumericTransport() {
        // 9-key: preedit "64 426", the highlighted span is the first code, the tail is raw digits.
        val snapshot =
            EngineSnapshot(
                candidates = listOf(Candidate(0, "你好")),
                preedit = "64 426",
                rawInput = "64426",
                isComposing = true,
            ).copy(
                hidesNumericTransportCode = true,
                highlightedDisplay = "ni",
                displayMode = CompositionDisplayMode.CANONICAL,
                preeditSelStart = 0,
                preeditSelEnd = 2,
                preeditCaretByte = 6,
            )
        // Without spelled keys the remaining raw tail still shows every press, as keys.
        val marked = MarkedComposition(snapshot)
        assertEquals("ni'426", marked.text)
        assertEquals(marked.text.length, marked.selection.start)
    }

    @Test
    fun aTypedApostropheSurvivesTheSpelling() {
        // librime keeps a typed ' as typed and adds a space only where it split syllables itself.
        assertEquals("ni'", SpellingDisplay.withTypedSeparators("bi'", "ni"))
        assertEquals("ni'hao", SpellingDisplay.withTypedSeparators("bi'hax", "ni hao"))
        assertEquals("ni hao", SpellingDisplay.withTypedSeparators("bi hax", "ni hao"))
        assertEquals("ni hao'", SpellingDisplay.withTypedSeparators("bi hax'", "ni hao"))
        // Syllables that do not line up keep the spelling's own joins; a trailing ' still shows.
        assertEquals("nihao'", SpellingDisplay.withTypedSeparators("bi'hax'", "nihao"))
    }

    @Test
    fun aHighlightEndingInATypedApostropheStillShowsIt() {
        val snapshot =
            EngineSnapshot(
                preedit = "bi'h",
                rawInput = "bi'h",
                isComposing = true,
                preeditSelStart = 0,
                preeditSelEnd = 3,
                preeditCaretByte = 4,
            )
        val marked = MarkedComposition(snapshot, readingFallback = "ni")
        assertEquals("ni'h", marked.text)
        assertEquals(4, marked.selection.start)
    }

    @Test
    fun typedTextMarksTheBoundariesTheUserTyped() {
        val keys = ('a'..'z').associateWith { it }
        val coverage = ReadingInputCoverage.of("ni'hao'", keys)!!
        assertEquals("ni'hao'", coverage.typedText(listOf("ni", "hao"), " "))
        assertEquals("ni h", ReadingInputCoverage.of("nih", keys)!!.typedText(listOf("ni", "hao"), " "))
    }
}
