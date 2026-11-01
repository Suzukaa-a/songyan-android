package app.songyan.ime.ime

import app.songyan.ime.candidate.BrowserCandidate
import app.songyan.ime.candidate.CandidateBrowser
import app.songyan.ime.candidate.CandidateBrowser.BrowserAppendResult
import app.songyan.ime.candidate.CandidateBrowser.CandidatePageReadResult
import app.songyan.ime.candidate.CandidatePresentationState
import app.songyan.ime.keyboard.engine.EngineSnapshot

/**
 * What the candidate bar lists: the engine's current page plus the pages read from the engine
 * read-only ([CandidateBrowser]), one list shared by the collapsed strip and the expanded grid.
 *
 * Reading never pages the engine, so the highlight, the marked text and what Space confirms stay
 * where the engine has them. When the engine itself pages or moves the highlight inside the same
 * list (a page key typed through the engine), the list follows it and keeps every page read so far;
 * anything else that changes the candidates (a new code, a partial selection, a reading filter)
 * replaces the list and bumps [revision], which every tap, read and delete is checked against.
 *
 * A list the engine cannot be asked for more of (English suggestions, emoji results, a reading
 * filter's matches, an engine that does not page) is shown as it is and never read further.
 *
 * Pure model, no Android types: the bar owns one instance and the controller performs the reads.
 */
internal class CandidatePages {
    private val browser = CandidateBrowser()

    /** Identity of a candidate list apart from its page: what makes the engine compute another list. */
    private data class ListKey(
        val schemaID: String,
        val rawInput: String,
        val pageSize: Int,
        val isComposing: Boolean,
        val isPrediction: Boolean,
        /** Start of the unconfirmed segment: a partial selection moves it without changing the code. */
        val segmentStart: Int,
    )

    private var key: ListKey? = null
    private var keyRevision = 0
    private var browsing = false
    private var stalled = false

    /** The last snapshot applied, so [trim] can anchor again on the engine's page. */
    private var last = EngineSnapshot()

    /** Revision of the list: bumped whenever it is replaced, never when it only grows or follows a page. */
    var revision = 0
        private set

    /** Candidates in engine order (ascending absolute index); grows as pages are read. */
    var entries: List<BrowserCandidate> = emptyList()
        private set

    /** Candidates per engine page of [entries]; 0 when the list does not page. */
    var pageSize = 0
        private set

    /** A read is in flight: no second one is asked for until it lands or fails. */
    var loading = false
        private set

    /** The list is the engine's paged menu, so a candidate is addressed by its absolute index. */
    val paged: Boolean get() = browsing

    /** Whether the engine may have candidates beyond [entries] that a read can fetch. */
    val hasMore: Boolean
        get() = browsing && browser.hasMore && entries.size < MAX_CANDIDATES

    /**
     * Applies an engine snapshot; true when the list was replaced (the bar then starts over at its
     * first candidate), false when it is the same list, possibly followed to another engine page.
     * [browsable] is false for rows that are not the engine's own candidate menu.
     */
    fun update(
        snapshot: EngineSnapshot,
        browsable: Boolean,
    ): Boolean {
        last = snapshot
        val before = entries
        val wasBrowsing = browsing
        browsing =
            browsable &&
            snapshot.pageSize > 0 &&
            (snapshot.isComposing || snapshot.isPrediction) &&
            snapshot.candidates.isNotEmpty()
        if (!browsing) {
            key = null
            browser.reset(CandidatePresentationState())
            pageSize = snapshot.pageSize
            entries = snapshot.candidates.map { BrowserCandidate(snapshot.pageIndex, it.index, it) }
            val same = !wasBrowsing && sameCandidates(before, entries)
            if (!same) replaced()
            return !same
        }
        val next = keyOf(snapshot)
        if (next != key) {
            key = next
            keyRevision++
        }
        val generation = browser.generation
        browser.reset(stateOf(snapshot))
        var replaced = !wasBrowsing || before.isEmpty() || browser.generation != generation
        if (!replaced && !browser.follow(stateOf(snapshot))) {
            // Same code, other words: what was read before no longer matches the engine.
            keyRevision++
            browser.reset(stateOf(snapshot))
            replaced = true
        }
        pageSize = snapshot.pageSize
        entries = browser.entries
        if (replaced) replaced() else stalled = false
        return replaced
    }

    /**
     * The engine page to read next, or null. Earlier pages (a list first seen on a later page) are
     * read at once; later ones only when [nearEnd] says the user is close to the last one shown.
     * A non-null answer marks a read in flight.
     */
    fun nextRead(nearEnd: Boolean): Int? {
        if (loading || stalled || !hasMore) return null
        val page = browser.nextPageToLoad ?: return null
        if (!browser.canAppend(page)) return null
        if (!browser.needsEarlier && !nearEnd) return null
        loading = true
        return page
    }

    /** A read for [revision] returned [result]; true when the list grew. A stale read is dropped. */
    fun append(
        revision: Int,
        page: Int,
        result: CandidatePageReadResult,
    ): Boolean {
        if (revision != this.revision || !loading || !browsing) return false
        loading = false
        val outcome = browser.append(page, result, pageSize)
        // An incomplete read is kept retryable by the browser, but asking again at once would only
        // fail again: the next engine change or scroll gesture allows another try.
        if (outcome == BrowserAppendResult.INCOMPLETE) stalled = true
        val before = entries.size
        entries = browser.entries
        return entries.size != before
    }

    /** The read for [revision] could not run (the engine moved on, or it threw). */
    fun failed(revision: Int) {
        if (revision != this.revision) return
        loading = false
        stalled = true
    }

    /**
     * A read that was asked for will never be answered (its operation was dropped with the input
     * session, or cancelled): the next scroll or change may ask again. Unlike [failed] this does not
     * hold further reads back, since nothing went wrong with the engine.
     */
    fun abandonRead() {
        loading = false
    }

    /** The user scrolls again: a read that failed may be tried once more. */
    fun resume() {
        stalled = false
    }

    /**
     * Gives back the pages read beyond the engine's current one; the list keeps its place in the
     * engine. Used while the keyboard is hidden, so nothing on screen loses a candidate.
     */
    fun trim() {
        if (!browsing || entries.size <= last.candidates.size) return
        invalidate()
    }

    /**
     * The engine's list changed under the same code (a word was deleted from the user dictionary):
     * every page read so far may have shifted, so the list starts over from the engine's page and
     * anything addressed by the old revision is dropped.
     */
    fun invalidate() {
        if (!browsing) return
        keyRevision++
        browser.reset(stateOf(last))
        entries = browser.entries
        replaced()
    }

    /** The absolute engine index of [entry]; its page-local index when the list does not page. */
    fun absoluteIndex(entry: BrowserCandidate): Int = entry.absoluteIndex(pageSize) ?: entry.pageLocalIndex

    private fun replaced() {
        revision++
        loading = false
        stalled = false
    }

    private fun keyOf(snapshot: EngineSnapshot) =
        ListKey(
            snapshot.schemaID,
            snapshot.rawInput,
            snapshot.pageSize,
            snapshot.isComposing,
            snapshot.isPrediction,
            snapshot.preeditSelStart,
        )

    private fun stateOf(snapshot: EngineSnapshot) =
        CandidatePresentationState.of(snapshot, isEnglishMode = false, revision = keyRevision)

    private fun sameCandidates(
        a: List<BrowserCandidate>,
        b: List<BrowserCandidate>,
    ): Boolean {
        if (a.size != b.size) return false
        for (i in a.indices) {
            if (a[i].pageIndex != b[i].pageIndex || a[i].index != b[i].index || a[i].text != b[i].text) return false
        }
        return true
    }

    companion object {
        /**
         * The most candidates one list accumulates. Reads only happen as the user scrolls toward the
         * end, so this is a safety rail for a list scrolled very far, not a limit anyone meets in
         * normal typing; past it the expanded grid still shows everything read so far.
         */
        const val MAX_CANDIDATES = 512
    }
}
