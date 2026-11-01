package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CandidateReleaseTest {
    private val page =
        listOf(
            Candidate(0, "你好"),
            Candidate(1, "你"),
            Candidate(2, "拟好"),
        )

    private fun pressedOf(
        cell: Int = 0,
        revision: Int = 7,
    ) = CandidateRelease.Pressed(
        cell = cell,
        index = page[cell].index,
        text = page[cell].text,
        revision = revision,
    )

    /** The drawn list is [listed]; the engine's current page is [listed] too unless the list moved on. */
    private fun resolve(
        pressed: CandidateRelease.Pressed,
        upCell: Int,
        revision: Int,
        listed: List<Candidate>,
    ) = CandidateRelease.resolve(pressed, upCell, revision, listed::getOrNull, listed)

    @Test
    fun thePressedCandidateCommitsWhileThePageIsUnchanged() {
        val resolved = resolve(pressedOf(cell = 1), 1, 7, page)
        assertEquals(CandidateRelease.Resolved(1, "你", fromList = true), resolved)
    }

    @Test
    fun anUnchangedPageNeverCommitsADifferentCell() {
        // The finger lifted somewhere else: the pressed word must not be committed for it.
        assertNull(resolve(pressedOf(cell = 0), 1, 7, page))
        assertNull(resolve(pressedOf(cell = 0), -1, 7, page))
    }

    @Test
    fun aChangedPageCommitsThePressedWordsNewIndex() {
        val moved = listOf(Candidate(0, "拟好"), Candidate(1, "你好"))
        val resolved = resolve(pressedOf(cell = 0), 0, 8, moved)
        assertEquals(CandidateRelease.Resolved(1, "你好", fromList = false), resolved)
    }

    @Test
    fun aChangedPageNeverCommitsWhicheverWordTookTheCell() {
        val replaced = listOf(Candidate(0, "你好".reversed()), Candidate(1, "你"))
        assertNull(resolve(pressedOf(cell = 0), 0, 8, replaced))
        // The identity moved but the text is gone, so nothing commits.
        assertNull(resolve(pressedOf(cell = 0), 0, 8, emptyList()))
    }

    @Test
    fun theCellIndexAloneIsNeverTrusted() {
        // A different word at the pressed cell with the same page revision cannot happen on screen,
        // and if it did the release must not commit it.
        val differentIdentity = listOf(Candidate(0, "别的"))
        assertNull(resolve(pressedOf(cell = 0), 0, 7, differentIdentity))
    }

    @Test
    fun aLaterPageCandidateIsIdentifiedByItsAbsoluteIndex() {
        // The strip lists page 0 and page 1 of a five-per-page list; cell 6 is absolute index 6.
        val strip = List(10) { Candidate(it, "词$it") }
        val pressed = CandidateRelease.Pressed(cell = 6, index = 6, text = "词6", revision = 3)
        val enginePage = strip.take(5)
        assertEquals(
            CandidateRelease.Resolved(6, "词6", fromList = true),
            CandidateRelease.resolve(pressed, 6, 3, strip::getOrNull, enginePage),
        )
        // The list was replaced and the engine's page does not hold the word: nothing commits.
        assertNull(CandidateRelease.resolve(pressed, 6, 4, strip::getOrNull, enginePage))
    }

    @Test
    fun relocatingPrefersTheSameReadingAndNeverTakesAnotherWord() {
        val engine = listOf(Candidate(0, "行", "xing"), Candidate(1, "行", "hang"), Candidate(2, "走"))
        assertEquals(1, CandidateRelease.relocate("行", "hang", engine)?.index)
        assertNull("two readings and none shown: do not guess", CandidateRelease.relocate("行", "", engine))
        assertNull(CandidateRelease.relocate("跑", "", engine))
    }
}
