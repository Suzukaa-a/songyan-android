package app.songyan.ime.candidate

import app.songyan.ime.keyboard.engine.Candidate

/**
 * Composition identity: the version of the real engine state. It deliberately excludes the preedit,
 * which changes with the highlighted candidate while the pages stay valid.
 *
 * [stableId] exists for diagnostics and for binding a loading sentinel to a specific composition.
 */
data class CandidateGeneration(
    val revision: Int,
    val schemaID: String,
    val layout: String,
    val rawInput: String,
    val pageSize: Int,
    val isComposing: Boolean,
    val isPrediction: Boolean = false,
) {
    val hasCandidateMenu: Boolean get() = isComposing || isPrediction

    /**
     * Unambiguous identity string: string fields carry a UTF-8 length prefix, booleans are 0/1.
     * Plain concatenation cannot work here, because `schemaID="s|l", layout="x"` and
     * `schemaID="s", layout="l|x"` would collide under any single-character separator.
     */
    val stableId: String
        get() =
            "cg2|" + revision + "|" + pageSize + "|" + flag(isComposing) + "|" + flag(isPrediction) + "|" +
                lengthPrefixed(schemaID) + "|" + lengthPrefixed(layout) + "|" + lengthPrefixed(rawInput)

    private fun flag(value: Boolean): String = if (value) "1" else "0"

    private fun lengthPrefixed(value: String): String = value.encodeToByteArray().size.toString() + ":" + value
}

/**
 * Structured identity of the tail read-only loading sentinel: composition identity + frontier +
 * which view asked. Kept structured (not string-concatenated) so the fields cannot collide.
 */
data class CandidateSentinelIdentity(
    val generation: CandidateGeneration?,
    val frontier: Int?,
    val kind: Kind,
) {
    enum class Kind {
        /** Collapsed strip tail sentinel. */
        COLLAPSED_TAIL,

        /** Expanded list tail sentinel. */
        EXPANDED_TAIL,
    }
}

/** One accumulated real candidate, carrying its engine page ownership and in-page index. */
data class BrowserCandidate(
    val pageIndex: Int,
    val pageLocalIndex: Int,
    val candidate: Candidate,
) {
    /** Stable identity: page number + in-page index (candidate text repeats, so text cannot be the id). */
    val id: String get() = pageIndex.toString() + ":" + pageLocalIndex

    val text: String get() = candidate.text

    val comment: String get() = candidate.comment

    val index: Int get() = candidate.index

    val selectKey: String? get() = candidate.selectKey

    val selectLabel: String? get() = candidate.selectLabel

    /**
     * Engine-authored visible slot marker: a declared display label wins, otherwise the engine's
     * select key is shown. Neither is synthesized from the comment or the absolute index.
     */
    val selectionBadge: String?
        get() {
            val label = candidate.selectLabel?.trim()
            if (!label.isNullOrEmpty()) return label
            val key = candidate.selectKey?.trim()
            return if (key.isNullOrEmpty()) null else key
        }

    companion object {
        /**
         * Cross-page absolute index = page number x page size + in-page index. Null when the engine
         * does not page or a coordinate is negative: callers must degrade, never guess.
         */
        fun absoluteIndex(
            pageIndex: Int,
            pageLocalIndex: Int,
            pageSize: Int,
        ): Int? {
            if (pageSize <= 0 || pageIndex < 0 || pageLocalIndex < 0) return null
            return pageIndex * pageSize + pageLocalIndex
        }
    }

    /** See [absoluteIndex]. */
    fun absoluteIndex(pageSize: Int): Int? = absoluteIndex(pageIndex, pageLocalIndex, pageSize)
}

/**
 * Cross-page candidate accumulator. Renderers only read it; the controller owns the mutations.
 *
 * The engine returns only `pageSize` candidates per page, so the collapsed strip's continuous
 * scrolling and the expanded area's "load more" need an accumulator. Every page is read **read-only**
 * by offset and recorded by real page number plus in-page index; selection goes through the host with
 * an absolute index, never with a concatenated-array position.
 *
 * Rules:
 *  * Browsing never calls nextPage/PageDown, so marked text and the space-confirm target stay.
 *  * The engine's current page is always the anchor; both earlier and later pages are filled
 *    read-only and entries stay sorted by absolute index.
 *  * EOF holds only for a **successful short read on the real current page or later**; a short or
 *    empty read before the anchor and an unavailable read are incomplete, never sealed and kept
 *    retryable.
 *
 * Mutable by design, exactly as the iOS value type is mutated through its owning controller: the IME
 * updates one instance per key press and no per-keystroke copy is allocated on the input path.
 */
class CandidateBrowser {
    var generation: CandidateGeneration? = null
        private set

    /** Accumulated candidates, ascending by (pageIndex, pageLocalIndex); no placeholders, no fake candidates. */
    var entries: List<BrowserCandidate> = emptyList()
        private set

    /** Page numbers fetched read-only so far. */
    var loadedPages: Set<Int> = emptySet()
        private set

    /** The real engine current page (the reset anchor). */
    var enginePageIndex: Int = 0
        private set

    /** Next page to read read-only; null = all reachable pages covered or not browsable. */
    var nextPageToLoad: Int? = null
        private set

    var hasMore: Boolean = false
        private set

    /** Whether the end page has been confirmed (a short read past the anchor or no forward page). */
    private var forwardExhausted: Boolean = false

    /** Pages known to be incompletely read; not in [loadedPages], so the frontier stays retryable. */
    var incompletePages: Set<Int> = emptySet()
        private set

    val hasNoEntries: Boolean get() = entries.isEmpty()

    val count: Int get() = entries.size

    /** Whether the next read fills a page before the current one. */
    val needsEarlier: Boolean
        get() {
            val next = nextPageToLoad ?: return false
            return next < enginePageIndex
        }

    val hasIncompletePage: Boolean get() = incompletePages.isNotEmpty()

    /** Whether the frontier stopped on a page that is before the anchor and incompletely read. */
    val hasIncompleteEarlierPage: Boolean
        get() {
            val next = nextPageToLoad ?: return false
            return next in incompletePages && next < enginePageIndex
        }

    private val maxKnownPage: Int get() = maxOf(enginePageIndex, loadedPages.maxOrNull() ?: enginePageIndex)

    /**
     * Applies a real engine state (called on every refresh):
     *  * no candidate menu, or English without candidates: clear everything;
     *  * identity changed: rebuild anchored on the real current page;
     *  * identity unchanged and the current candidates are empty: an empty result is authoritative,
     *    so old entries and the frontier are cleared first;
     *  * identity unchanged, non-empty, but browsing was cleared: rebuild the anchor instead of
     *    staying a permanent no-op;
     *  * otherwise: a display-only refresh, kept as-is.
     */
    fun reset(state: CandidatePresentationState) {
        val nextGeneration =
            if (state.hasCandidateMenu && (!state.isEnglishMode || state.candidates.isNotEmpty())) {
                CandidateGeneration(
                    revision = state.revision,
                    schemaID = state.schemaID,
                    layout = state.layout,
                    rawInput = state.rawInput,
                    pageSize = state.pageSize,
                    isComposing = state.isComposing,
                    isPrediction = state.isPrediction,
                )
            } else {
                null
            }

        if (nextGeneration == null) {
            generation = null
            clearBrowsing()
            enginePageIndex = state.pageIndex
            return
        }

        if (generation != nextGeneration || state.candidates.isEmpty() || entries.isEmpty()) {
            generation = nextGeneration
            if (state.candidates.isEmpty()) {
                // An empty snapshot has nothing to anchor: browsing ends and old pages never carry over.
                clearBrowsing()
                enginePageIndex = state.pageIndex
                loadedPages = setOf(state.pageIndex)
                forwardExhausted = !state.canPageForward
                return
            }
            clearBrowsing()
            enginePageIndex = state.pageIndex
            entries =
                state.candidates.map {
                    BrowserCandidate(pageIndex = state.pageIndex, pageLocalIndex = it.index, candidate = it)
                }
            loadedPages = setOf(state.pageIndex)
            forwardExhausted = !state.canPageForward
            recomputeFrontier(pageSize = state.pageSize)
        }
        // else: an ordinary non-empty display-only refresh keeps the cross-page browsing.
    }

    /**
     * The engine moved within the list [reset] last anchored (a page key, a highlight move): the
     * anchor follows the engine's real current page, and that page is recorded as read when it was
     * not yet, so the accumulation keeps every page browsed so far. Returns false, changing nothing,
     * when there is no list to follow or what was read for that page disagrees with what the engine
     * now shows: the accumulation is stale and the caller must start a new generation.
     */
    fun follow(state: CandidatePresentationState): Boolean {
        val current = generation ?: return false
        if (state.candidates.isEmpty() || state.pageSize != current.pageSize || state.pageIndex < 0) return false
        val page = state.pageIndex
        // The engine has no page after this one, yet pages beyond it were read: the list shrank.
        if (!state.canPageForward && loadedPages.any { it > page }) return false
        if (page in loadedPages) {
            val read = entries.filter { it.pageIndex == page }
            if (read.size != state.candidates.size) return false
            for (i in read.indices) {
                val shown = state.candidates[i]
                if (read[i].pageLocalIndex != shown.index || read[i].text != shown.text) return false
            }
        } else {
            replaceEntries(page, state.candidates)
            loadedPages = loadedPages + page
            incompletePages = incompletePages - page
        }
        enginePageIndex = page
        if (!state.canPageForward) forwardExhausted = true
        recomputeFrontier(pageSize = state.pageSize)
        return true
    }

    private fun clearBrowsing() {
        entries = emptyList()
        loadedPages = emptySet()
        incompletePages = emptySet()
        forwardExhausted = false
        nextPageToLoad = null
        hasMore = false
    }

    /** Whether the page can still be read read-only (checked by the host before starting a read). */
    fun canAppend(pageIndex: Int): Boolean =
        generation?.hasCandidateMenu == true && pageIndex >= 0 && pageIndex !in loadedPages

    /** Appends one page read-only and reports how the read concluded. */
    fun append(
        pageIndex: Int,
        result: CandidatePageReadResult,
        pageSize: Int,
    ): BrowserAppendResult {
        if (generation?.hasCandidateMenu != true || pageIndex < 0 || pageSize <= 0) {
            return BrowserAppendResult.REJECTED
        }
        if (pageIndex in loadedPages) return BrowserAppendResult.REJECTED

        return when (result) {
            CandidatePageReadResult.Unavailable -> {
                // Failed or unsupported: retryable, never mistaken for EOF, never sealed.
                incompletePages = incompletePages + pageIndex
                recomputeFrontier(pageSize = pageSize)
                BrowserAppendResult.INCOMPLETE
            }

            is CandidatePageReadResult.Page -> {
                val candidates = result.candidates
                if (candidates.size >= pageSize) {
                    loadedPages = loadedPages + pageIndex
                    incompletePages = incompletePages - pageIndex
                    replaceEntries(pageIndex, candidates)
                    recomputeFrontier(pageSize = pageSize)
                    return BrowserAppendResult.APPENDED
                }
                if (pageIndex >= enginePageIndex) {
                    // A successful short read at the tail is the real EOF.
                    loadedPages = loadedPages + pageIndex
                    incompletePages = incompletePages - pageIndex
                    replaceEntries(pageIndex, candidates)
                    forwardExhausted = true
                    recomputeFrontier(pageSize = pageSize)
                    return BrowserAppendResult.APPENDED
                }
                // A short page before the anchor is incomplete: kept retryable, no permanent hole.
                // Its real candidates still belong in the accumulator (iOS replaces the entry list
                // in this branch too), otherwise the page is re-read and re-dropped forever.
                incompletePages = incompletePages + pageIndex
                replaceEntries(pageIndex, candidates)
                recomputeFrontier(pageSize = pageSize)
                BrowserAppendResult.INCOMPLETE
            }
        }
    }

    /** Convenience overload for a successful read of [candidates]; false = not sealed. */
    fun append(
        pageIndex: Int,
        candidates: List<Candidate>,
        pageSize: Int,
    ): Boolean = append(pageIndex, CandidatePageReadResult.Page(candidates), pageSize) == BrowserAppendResult.APPENDED

    /**
     * Replaces a page's entries with the latest read result, so a retry never accumulates the same
     * page twice. Entries stay sorted by absolute index.
     */
    private fun replaceEntries(
        pageIndex: Int,
        candidates: List<Candidate>,
    ) {
        val kept = entries.filterNot { it.pageIndex == pageIndex }
        if (candidates.isEmpty()) {
            entries = kept
            return
        }
        entries =
            (
                kept +
                    candidates.map {
                        BrowserCandidate(pageIndex = pageIndex, pageLocalIndex = it.index, candidate = it)
                    }
            ).sortedWith(compareBy<BrowserCandidate> { it.pageIndex }.thenBy { it.pageLocalIndex })
    }

    /**
     * Recomputes the frontier: first fill the earliest unloaded page within `0..maxKnownPage`, then
     * consider the page after it unless the end has been confirmed.
     */
    private fun recomputeFrontier(pageSize: Int) {
        if (generation == null || pageSize <= 0) {
            nextPageToLoad = null
            hasMore = false
            return
        }
        val top = maxKnownPage
        for (page in 0..top) {
            if (page !in loadedPages) {
                nextPageToLoad = page
                hasMore = true
                return
            }
        }
        if (!forwardExhausted) {
            nextPageToLoad = top + 1
            hasMore = true
            return
        }
        nextPageToLoad = null
        hasMore = false
    }

    /** The real identity to commit when an accumulated candidate is selected. */
    fun selection(entry: BrowserCandidate): Pair<Int, Int> = entry.pageIndex to entry.pageLocalIndex

    /** Result of one read-only page read. */
    sealed interface CandidatePageReadResult {
        /** A successful read, possibly shorter than `pageSize`. */
        data class Page(
            val candidates: List<Candidate>,
        ) : CandidatePageReadResult

        /** Unsupported or failed: never EOF, never sealed, so the page stays retryable. */
        data object Unavailable : CandidatePageReadResult

        companion object {
            /**
             * Maps a possibly throwing read to a read result: a throw is [Unavailable] (retryable), a
             * successful return is [Page] and the append decides EOF.
             */
            fun reading(read: () -> List<Candidate>): CandidatePageReadResult =
                try {
                    Page(read())
                } catch (error: Exception) {
                    Unavailable
                }
        }
    }

    /** How an [append] concluded. */
    enum class BrowserAppendResult {
        /** A complete page or a real tail EOF was sealed. */
        APPENDED,

        /** Incomplete read or temporarily unavailable: not sealed, the frontier stays on that page. */
        INCOMPLETE,

        /** No active composition, already loaded, or invalid arguments. */
        REJECTED,
    }

    companion object {
        /** Builds an accumulation directly, without the engine (tests and previews). */
        fun ofEntries(entries: List<BrowserCandidate>): CandidateBrowser =
            CandidateBrowser().apply {
                entries.forEach {
                    this.entries = this.entries + it
                    loadedPages = loadedPages + it.pageIndex
                }
                enginePageIndex = entries.maxOfOrNull { it.pageIndex } ?: 0
            }
    }
}
