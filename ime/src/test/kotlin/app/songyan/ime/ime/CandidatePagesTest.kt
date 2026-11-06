package app.songyan.ime.ime

import app.songyan.ime.candidate.CandidateBrowser.CandidatePageReadResult
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the strip and the grid list, and when they ask the engine for another page. */
class CandidatePagesTest {
    private fun words(
        prefix: String,
        count: Int = 5,
    ) = List(count) { Candidate(it, "$prefix$it") }

    private fun snapshot(
        raw: String = "ji",
        page: Int = 0,
        candidates: List<Candidate> = words("p$page"),
        highlighted: Int = 0,
        last: Boolean = false,
        segmentStart: Int = 0,
        prediction: Boolean = false,
    ) = EngineSnapshot(
        schemaID = "s",
        rawInput = raw,
        isComposing = !prediction,
        isPrediction = prediction,
        candidates = candidates,
        highlightedIndex = highlighted,
        pageIndex = page,
        pageSize = 5,
        isLastPage = last,
        preeditSelStart = segmentStart,
    )

    private fun CandidatePages.read(page: Int) = append(revision, page, CandidatePageReadResult.Page(words("p$page")))

    @Test
    fun aNewCodeListsTheEnginePageAndFillsTheRowWithOneRead() {
        val pages = CandidatePages()
        assertTrue(pages.update(snapshot(), browsable = true))
        assertEquals(5, pages.entries.size)
        assertTrue(pages.hasMore)
        // The row is not full yet: the next page is asked for at once, and only one at a time.
        assertEquals(1, pages.nextRead(nearEnd = true))
        assertNull("one read in flight", pages.nextRead(nearEnd = true))
        assertTrue(pages.read(1))
        assertEquals(10, pages.entries.size)
        assertNull("not near the end: no read", pages.nextRead(nearEnd = false))
        assertEquals(2, pages.nextRead(nearEnd = true))
    }

    @Test
    fun anAutomaticFillStopsAtTheVisibleEndWhileBrowsingReadsAhead() {
        // Collapsed strip on a 411 dp screen: 363 dp of viewport beside the 48 dp expand control, and
        // the lead is two 48 dp rows. A one-character candidate stays on the 44 dp minimum chip, so
        // each engine page of five adds 220 dp of strip: 224, 444 and 664 dp after one, two and three
        // pages (the 2 dp insets included).
        val lookAhead = 96f

        fun due(
            content: Float,
            browsing: Boolean,
            visibleEnd: Float = 363f,
        ) = loadMoreDue(visibleEnd, content, lookAhead, browsing)

        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        // A strip that ends before the viewport is filled by the view's own refresh; one that ends
        // exactly at its end is not (the fill rule is strict).
        assertTrue("row not full: fill it", due(content = 224f, browsing = false))
        assertFalse(due(content = 363f, browsing = false))
        assertEquals(1, pages.nextRead(due(content = 224f, browsing = false)))
        pages.read(1)
        // Two pages run past the viewport but still end inside the lead: the view's own refresh reads
        // nothing off screen, while browsing (a page read ahead of the finger) asks for the next one.
        assertFalse("row full: nothing to fill", due(content = 444f, browsing = false))
        assertNull(pages.nextRead(due(content = 444f, browsing = false)))
        assertTrue(due(content = 444f, browsing = true))
        assertEquals(2, pages.nextRead(due(content = 444f, browsing = true)))
        pages.read(2)
        // Three pages end beyond the lead: browsing asks for nothing until it has scrolled into reach,
        // and the lead's own edge counts.
        assertFalse("out of reach", due(content = 664f, browsing = true))
        assertNull(pages.nextRead(due(content = 664f, browsing = true)))
        assertFalse(due(content = 664f, browsing = true, visibleEnd = 567f))
        assertTrue("scrolled into the lead", due(content = 664f, browsing = true, visibleEnd = 568f))
        assertEquals(3, pages.nextRead(due(content = 664f, browsing = true, visibleEnd = 568f)))
    }

    @Test
    fun laterCandidatesKeepTheirAbsoluteIndex() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        pages.nextRead(nearEnd = true)
        pages.read(1)
        assertTrue(pages.paged)
        assertEquals((0 until 10).toList(), pages.entries.map(pages::absoluteIndex))
        assertEquals("p11", pages.entries[6].text)
    }

    @Test
    fun aReadForAReplacedListIsDropped() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        val stale = pages.revision
        assertEquals(1, pages.nextRead(nearEnd = true))
        assertTrue(pages.update(snapshot(raw = "jia", candidates = words("jia")), browsable = true))
        assertNotEquals(stale, pages.revision)
        assertFalse(pages.append(stale, 1, CandidatePageReadResult.Page(words("old"))))
        assertEquals(5, pages.entries.size)
        // The new list asks for its own next page.
        assertEquals(1, pages.nextRead(nearEnd = true))
    }

    @Test
    fun theEnginePagingWithinTheListKeepsWhatWasRead() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        pages.nextRead(nearEnd = true)
        pages.read(1)
        val revision = pages.revision
        // A page key handled by the engine: same code, page 1, already read.
        assertFalse(pages.update(snapshot(page = 1, highlighted = 2), browsable = true))
        assertEquals(revision, pages.revision)
        assertEquals(10, pages.entries.size)
        // Another page key reaches page 2, never read: it joins the list without replacing it.
        assertFalse(pages.update(snapshot(page = 2), browsable = true))
        assertEquals(15, pages.entries.size)
        assertEquals(revision, pages.revision)
    }

    @Test
    fun aMovedHighlightIsNotANewList() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        assertFalse(pages.update(snapshot(highlighted = 3), browsable = true))
    }

    @Test
    fun aPartialSelectionWithTheSameCodeIsANewList() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        pages.nextRead(nearEnd = true)
        pages.read(1)
        // The engine confirmed the first syllable: same raw code, the unconfirmed segment moved.
        assertTrue(pages.update(snapshot(segmentStart = 3, candidates = words("rest")), browsable = true))
        assertEquals(5, pages.entries.size)
    }

    @Test
    fun otherWordsUnderTheSameCodeReplaceTheList() {
        val pages = CandidatePages()
        pages.update(snapshot(prediction = true, raw = "", candidates = words("after-you")), browsable = true)
        pages.nextRead(nearEnd = true)
        pages.append(pages.revision, 1, CandidatePageReadResult.Page(words("more")))
        // The next prediction has the same identity fields, but other words: what was read is stale.
        assertTrue(
            pages.update(snapshot(prediction = true, raw = "", candidates = words("after-good")), browsable = true),
        )
        assertEquals(words("after-good").map { it.text }, pages.entries.map { it.text })
    }

    @Test
    fun theLastPageEndsReading() {
        val pages = CandidatePages()
        pages.update(snapshot(last = true), browsable = true)
        assertFalse(pages.hasMore)
        assertNull(pages.nextRead(nearEnd = true))
        val more = CandidatePages()
        more.update(snapshot(), browsable = true)
        more.nextRead(nearEnd = true)
        // A short read is the end of the list.
        more.append(more.revision, 1, CandidatePageReadResult.Page(words("tail", 2)))
        assertFalse(more.hasMore)
    }

    @Test
    fun aFailedReadWaitsForTheNextScrollOrChange() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        assertEquals(1, pages.nextRead(nearEnd = true))
        pages.failed(pages.revision)
        assertNull("no retry on every frame", pages.nextRead(nearEnd = true))
        pages.resume()
        assertEquals(1, pages.nextRead(nearEnd = true))
        pages.append(pages.revision, 1, CandidatePageReadResult.Unavailable)
        assertNull(pages.nextRead(nearEnd = true))
        // The engine moved its highlight: the state changed, so one more try is allowed.
        pages.update(snapshot(highlighted = 1), browsable = true)
        assertEquals(1, pages.nextRead(nearEnd = true))
    }

    @Test
    fun aReadThatIsNeverAnsweredCanBeAskedForAgain() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        assertEquals(1, pages.nextRead(nearEnd = true))
        assertTrue(pages.loading)
        assertNull("still waiting", pages.nextRead(nearEnd = true))
        // The operation behind the read was cancelled with its session.
        pages.abandonRead()
        assertFalse(pages.loading)
        assertEquals(1, pages.nextRead(nearEnd = true))
    }

    @Test
    fun suggestionsAndFilteredMatchesAreShownAsTheyAreAndNeverRead() {
        val pages = CandidatePages()
        val filtered = snapshot(candidates = listOf(Candidate(7, "七"), Candidate(12, "十二")))
        assertTrue(pages.update(filtered, browsable = false))
        assertFalse(pages.paged)
        assertFalse(pages.hasMore)
        assertNull(pages.nextRead(nearEnd = true))
        assertEquals(listOf("七", "十二"), pages.entries.map { it.text })
        assertFalse("the same row again is not a new list", pages.update(filtered, browsable = false))
    }

    @Test
    fun readingStopsAtTheCap() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        var page = 1
        while (true) {
            val next = pages.nextRead(nearEnd = true) ?: break
            assertEquals(page, next)
            pages.read(next)
            page++
        }
        // Reads are whole pages, so the last one may go past the cap by less than a page.
        assertTrue(pages.entries.size in CandidatePages.MAX_CANDIDATES until CandidatePages.MAX_CANDIDATES + 5)
        assertFalse(pages.hasMore)
    }

    @Test
    fun aDeletedWordOnALaterPageStartsTheListOver() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        pages.nextRead(nearEnd = true)
        pages.read(1)
        pages.nextRead(nearEnd = true)
        pages.read(2)
        val revision = pages.revision
        assertEquals(15, pages.entries.size)
        // The engine stays on page 0 with the same words after the delete: only the later pages shifted.
        pages.invalidate()
        assertNotEquals("taps and deletes made for the old list are dropped", revision, pages.revision)
        assertEquals(5, pages.entries.size)
        assertFalse("the same page 0 again is the same list", pages.update(snapshot(), browsable = true))
        assertEquals(1, pages.nextRead(nearEnd = true))
    }

    @Test
    fun aListThatEndsEarlierThanWhatWasReadIsReplaced() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        pages.nextRead(nearEnd = true)
        pages.read(1)
        // Same code and page, but the engine now says there is no page after this one.
        assertTrue(pages.update(snapshot(last = true), browsable = true))
        assertEquals(5, pages.entries.size)
    }

    @Test
    fun trimKeepsOnlyTheEnginePage() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        pages.nextRead(nearEnd = true)
        pages.read(1)
        val revision = pages.revision
        pages.trim()
        assertEquals(5, pages.entries.size)
        assertNotEquals("anything in flight for the longer list is dropped", revision, pages.revision)
        assertEquals(1, pages.nextRead(nearEnd = true))
    }

    @Test
    fun noCandidatesNoList() {
        val pages = CandidatePages()
        pages.update(snapshot(), browsable = true)
        assertTrue(pages.update(EngineSnapshot(), browsable = true))
        assertTrue(pages.entries.isEmpty())
        assertFalse(pages.hasMore)
    }
}
