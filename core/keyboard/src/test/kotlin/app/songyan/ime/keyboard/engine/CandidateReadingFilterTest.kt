package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.display.ReadingInputCoverage
import app.songyan.ime.keyboard.layout.CompactFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from KeyboardCoreTests.CandidateReadingFilterTests (the batch reader is not ported). */
class CandidateReadingFilterTest {
    private val t9 = SpellingRailChoicesTest.nineKeyMap()

    private fun candidate(
        index: Int,
        text: String,
        vararg paths: List<String>,
        complete: Boolean = true,
    ) = ReadingCandidate.of(
        index,
        text,
        readingPaths = if (paths.isEmpty() || !complete) null else paths.toList(),
        readingsComplete = complete,
    )

    private fun filter(
        revision: Int = 1,
        coverage: ReadingInputCoverage? = null,
        vararg page: ReadingCandidate,
        requested: Int = 8,
    ): CandidateReadingFilter {
        val state = CandidateReadingFilter()
        state.reset(revision)
        state.setCoverage(coverage)
        state.append(page.toList(), requested, revision)
        return state
    }

    @Test
    fun mergedCandidateMatchesAnyWholePathWithoutMixingSyllables() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.append(
            listOf(candidate(0, "重重", listOf("chong", "chong"), listOf("zhong", "zhong"))),
            requested = 2,
            revision = 1,
        )
        assertEquals(listOf("zhong", "chong"), state.options)
        state.select("zhong")
        assertEquals(listOf("zhong"), state.options)
        state.select("chong")
        assertEquals(listOf("zhong"), state.prefix)
        state.select("zhong")
        assertEquals(listOf(0), state.matches.map { it.index })
        state.clearSelection()
        state.select("chong")
        state.select("chong")
        assertEquals(listOf(0), state.matches.map { it.index })
        state.reset(2)
        state.append(listOf(candidate(0, "部分可信", listOf("ni"), complete = false)), requested = 2, revision = 2)
        assertEquals(1, state.unclassifiedCount)
    }

    @Test
    fun wholeCommonSyllablesComeBeforeLessCommonAndBegunOnes() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.setCoverage(ReadingInputCoverage.of("64", t9))
        state.append(
            listOf(
                candidate(0, "米", listOf("mi")),
                candidate(1, "你好", listOf("ni", "hao")),
                candidate(2, "你", listOf("ni")),
                candidate(3, "蘑菇", listOf("mo", "gu")),
                candidate(4, "某", listOf("mou")),
            ),
            requested = 8,
            revision = 1,
        )
        assertEquals(listOf("ni", "mi", "mo", "mou"), state.options)
        state.select("ni")
        assertEquals(emptyList<String>(), state.options)
        state.clearSelection()
        state.reset(2)
        // The last key only begins 好/会: its letters follow every whole syllable.
        state.setCoverage(ReadingInputCoverage.of("644", t9))
        state.append(
            listOf(
                candidate(0, "你好", listOf("ni", "hao")),
                candidate(1, "你会", listOf("ni", "hui")),
                candidate(2, "你给", listOf("ni", "gei")),
            ),
            requested = 8,
            revision = 2,
        )
        state.select("ni")
        assertEquals(listOf("h", "g"), state.options)
    }

    @Test
    fun nonMandarinReadingsKeepEngineOrder() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.append(
            listOf(candidate(0, "光", listOf("gwong")), candidate(1, "是", listOf("si"))),
            requested = 8,
            revision = 1,
        )
        assertEquals(listOf("gwong", "si"), state.options)
    }

    @Test
    fun spellingOptionsTypedLetterForLetterComeFirst() {
        val ordered =
            SyllableOrder.spellingOptions(
                listOf("chang", "cheng", "chong", "gang", "hang"),
                typedKeys = "4264",
                keyForLetter = t9,
            )
        assertEquals(listOf("gang", "hang"), ordered.take(2))
        assertEquals(setOf("chang", "cheng", "chong", "gang", "hang"), ordered.toSet())
        assertEquals(
            listOf("b", "a"),
            SyllableOrder.spellingOptions(listOf("b", "a"), typedKeys = "2", keyForLetter = emptyMap()),
        )
    }

    @Test
    fun untrustedKeyMapStillPutsLiteralReadingsFirst() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.setCoverage(ReadingInputCoverage.of("4264", t9))
        state.append(
            listOf(
                candidate(0, "成", listOf("cheng")),
                candidate(1, "长", listOf("chang")),
                candidate(2, "行", listOf("hang")),
                candidate(3, "哈尼", listOf("ha", "ni")),
                candidate(4, "刚", listOf("gang")),
                candidate(5, "冲", listOf("chong")),
            ),
            requested = 8,
            revision = 1,
        )
        assertEquals(listOf("gang", "hang", "ha"), state.options.take(3))
        assertEquals(setOf("cheng", "chang", "chong"), state.options.drop(3).toSet())
    }

    @Test
    fun incompleteReadingPathsNeverBecomeSelectableMatches() {
        val state = CandidateReadingFilter()
        state.reset(3)
        state.append(
            listOf(candidate(0, "部分可信", listOf("mi"), complete = false), candidate(1, "你", listOf("ni"))),
            requested = 2,
            revision = 3,
        )
        assertEquals(listOf("ni"), state.options)
        state.select("mi")
        assertTrue(state.prefix.isEmpty())
        state.select("ni")
        assertEquals(listOf(1), state.matches.map { it.index })
        state.clearSelection()
        assertEquals(listOf(0, 1), state.matches.map { it.index })
    }

    @Test
    fun continuousReadingSelectionKeepsAbsoluteOrderAndUnknownAccessibleOnCancel() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.append(
            listOf(
                candidate(0, "你", listOf("ni")),
                candidate(1, "你好", listOf("ni", "hao")),
                candidate(2, "米", listOf("mi")),
                candidate(3, "外来候选"),
            ),
            requested = 4,
            revision = 1,
        )
        assertEquals(listOf("ni", "mi"), state.options)
        state.select("ni")
        assertEquals(listOf(0, 1), state.matches.map { it.index })
        assertEquals(listOf("hao"), state.options)
        state.select("hao")
        assertEquals(listOf(0, 1), state.matches.map { it.index })
        assertEquals(4, state.nextIndex)
        state.undoSelection()
        assertEquals(listOf("ni"), state.prefix)
        state.clearSelection()
        assertEquals(listOf(0, 1, 2, 3), state.matches.map { it.index })
    }

    @Test
    fun nonmatchingPagesDoNotTruncateFutureMatchesAndFailuresRemainRetryable() {
        val state = CandidateReadingFilter()
        state.reset(8)
        state.append(listOf(candidate(0, "甲", listOf("jia"))), requested = 1, revision = 8)
        state.select("jia")
        state.append(listOf(candidate(1, "乙", listOf("yi"))), requested = 1, revision = 8)
        assertTrue(state.hasMore)
        state.failed()
        assertEquals(2, state.nextIndex)
        state.append(listOf(candidate(2, "家庭", listOf("jia", "ting"))), requested = 1, revision = 8)
        assertEquals(listOf(0, 2), state.matches.map { it.index })
        assertFalse(state.readFailed)
        state.append(emptyList(), requested = 1, revision = 8)
        assertFalse(state.hasMore)
        state.reset(9)
        state.append(listOf(candidate(0, "过期", listOf("old"))), requested = 1, revision = 8)
        assertTrue(state.entries.isEmpty())
        assertTrue(state.prefix.isEmpty())
    }

    @Test
    fun onlyRealLeftSideReadingOptionsCanBeSelected() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.append(
            listOf(
                candidate(0, "你", listOf("ni")),
                candidate(1, "米", listOf("mi")),
                candidate(2, "你好", listOf("ni", "hao")),
                candidate(3, "你号", listOf("ni", "hao2")),
            ),
            requested = 4,
            revision = 1,
        )
        assertEquals(listOf("ni", "mi"), state.options)
        state.select("ni")
        assertEquals(listOf("ni"), state.prefix)
        assertEquals(listOf("hao", "hao2"), state.options)
        state.select("hao2")
        assertEquals(listOf("ni", "hao2"), state.prefix)
        assertEquals(listOf(0, 3), state.matches.map { it.index })
        state.select("chong")
        state.select("hao")
        assertEquals(listOf("ni", "hao2"), state.prefix)
        state.undoSelection()
        assertEquals(listOf("ni"), state.prefix)
        state.clearSelection()
        assertEquals(emptyList<String>(), state.prefix)

        val empty = CandidateReadingFilter()
        empty.reset(1)
        empty.append(listOf(candidate(0, "外来")), requested = 1, revision = 1)
        assertTrue(empty.options.isEmpty())
        empty.select("ni")
        assertTrue(empty.prefix.isEmpty())
    }

    @Test
    fun completionSyllablesBeyondTypedKeysAreNotOffered() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.setCoverage(ReadingInputCoverage.of("64426", t9))
        state.append(
            listOf(
                candidate(0, "你好", listOf("ni", "hao")),
                candidate(1, "你好吗", listOf("ni", "hao", "ma")),
                candidate(2, "你", listOf("ni")),
            ),
            requested = 8,
            revision = 1,
        )
        assertEquals(listOf("ni"), state.options)
        state.select("ni")
        assertEquals(listOf("hao"), state.options)
        state.select("hao")
        assertEquals(emptyList<String>(), state.options)
        assertEquals(listOf("你好", "你好吗", "你"), state.matches.map { it.text })
    }

    @Test
    fun chosenSyllablesThatSpellEveryKeyOfferNothingMore() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.setCoverage(ReadingInputCoverage.of("64'4", t9))
        state.append(
            listOf(
                candidate(0, "你好", listOf("ni", "hao")),
                candidate(1, "你好坏", listOf("ni", "hao", "huai")),
                candidate(2, "你好高", listOf("ni", "hao", "gao")),
                candidate(3, "你会", listOf("ni", "hui")),
            ),
            requested = 8,
            revision = 1,
        )
        state.select("ni")
        assertEquals(listOf("h"), state.options)
        state.select("h")
        assertEquals(emptyList<String>(), state.options)
    }

    @Test
    fun syllableStillBeingTypedOffersTheTypedLetters() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.setCoverage(ReadingInputCoverage.of("644", t9))
        state.append(
            listOf(
                candidate(0, "你好", listOf("ni", "hao")),
                candidate(1, "你会", listOf("ni", "hui")),
                candidate(2, "你给", listOf("ni", "gei")),
                candidate(3, "你", listOf("ni")),
            ),
            requested = 8,
            revision = 1,
        )
        state.select("ni")
        assertEquals(listOf("h", "g"), state.options)
        state.select("h")
        assertEquals(emptyList<String>(), state.options)
        assertEquals(listOf("你好", "你会", "你"), state.matches.map { it.text })
        assertTrue(state.startsWithPrefix(listOf("ni", "hao")))
        assertFalse(state.startsWithPrefix(listOf("ni", "gei")))
        state.undoSelection()
        assertEquals(listOf("h", "g"), state.options)

        val whole = CandidateReadingFilter()
        whole.reset(1)
        whole.setCoverage(ReadingInputCoverage.of("64426", t9))
        whole.append(listOf(candidate(0, "你好", listOf("ni", "hao"))), requested = 8, revision = 1)
        whole.select("ni")
        assertEquals(listOf("hao"), whole.options)
    }

    @Test
    fun chosenReadingAlsoMatchesWordsForItsLeadingSyllables() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.append(
            listOf(
                candidate(0, "可成形", listOf("ke", "cheng", "xing")),
                candidate(1, "课程", listOf("ke", "cheng")),
                candidate(2, "客船", listOf("ke", "chuan")),
                candidate(3, "可", listOf("ke")),
            ),
            requested = 8,
            revision = 1,
        )
        state.select("ke")
        state.select("cheng")
        state.select("xing")
        assertEquals(listOf("可成形", "课程", "可"), state.matches.map { it.text })
        assertTrue(CandidateReadingFilter.continues(listOf("ke"), listOf("ke", "cheng")))
        assertFalse(CandidateReadingFilter.continues(emptyList(), listOf("ke")))
        assertFalse(CandidateReadingFilter.continues(listOf("ke", "chuan"), listOf("ke", "cheng")))
    }

    @Test
    fun coverageAcceptsAbbreviationsSeparatorsAndLockedLetters() {
        val coverage = ReadingInputCoverage.of("64'4", t9)!!
        assertTrue(coverage.fits(listOf("ni", "hao"), consumingAll = true))
        assertFalse(coverage.fits(listOf("ni", "hao", "ma"), consumingAll = false))
        assertFalse(coverage.fits(listOf("nih"), consumingAll = true))
        assertTrue(ReadingInputCoverage.of("6442", t9)!!.fits(listOf("ni", "hao"), consumingAll = true))
        assertFalse(ReadingInputCoverage.of("64426", t9)!!.fits(listOf("ni", "hao", "ma"), consumingAll = false))
        val locked = ReadingInputCoverage.of("ni426", t9)!!
        assertTrue(locked.fits(listOf("ni", "hao"), consumingAll = true))
        assertFalse(locked.fits(listOf("mi", "hao"), consumingAll = false))
    }

    @Test
    fun unprovableKeyMapKeepsEveryEngineReading() {
        val state = CandidateReadingFilter()
        state.reset(1)
        state.setCoverage(ReadingInputCoverage.of("nihc", mapOf('n' to 'n')))
        state.append(
            listOf(
                candidate(0, "你好", listOf("ni", "hao")),
                candidate(1, "你好吗", listOf("ni", "hao", "ma")),
            ),
            requested = 8,
            revision = 1,
        )
        state.select("ni")
        state.select("hao")
        assertEquals(listOf("ma"), state.options)
    }

    @Test
    fun typedSpellingShowsOnlyWhatWasTyped() {
        val three = ReadingInputCoverage.of("284", t9)!!
        assertEquals(listOf("bu", "g"), three.typedSpelling(listOf("bu", "guo")))
        assertEquals(listOf("n"), ReadingInputCoverage.of("6", t9)!!.typedSpelling(listOf("na")))
        assertEquals(
            listOf("bu", "hao"),
            ReadingInputCoverage.of("28426", t9)!!.typedSpelling(listOf("bu", "hao")),
        )
        assertNull(three.typedSpelling(listOf("bu")))
    }

    @Test
    fun exactTokenCoversItsLetterOnly() {
        val fold = CompactFold.MERGED14
        val token = fold.exactTokens['d']
        assertNotNull(token)
        assertNull(fold.exactTokens['f'])
        assertFalse(token!! in 'a'..'z')
        val keys = fold.map
        val exact = fold.exactTokens.entries.associate { (letter, key) -> key to letter }
        val picked = ReadingInputCoverage.of("${token}u", keys, exact)!!
        assertTrue(picked.fits(listOf("du"), consumingAll = true))
        assertFalse(picked.fits(listOf("fu"), consumingAll = true))
        assertEquals(listOf("du"), picked.typedSpelling(listOf("du")))
        val tapped = ReadingInputCoverage.of("du", keys, exact)!!
        assertTrue(tapped.fits(listOf("du"), consumingAll = true))
        assertTrue(tapped.fits(listOf("fu"), consumingAll = true))
        assertEquals("wrisfjxb".toSet(), CompactFold.MERGED18.exactTokens.keys)
        assertTrue(CompactFold.PHONE9.exactTokens.isEmpty())
    }
}
