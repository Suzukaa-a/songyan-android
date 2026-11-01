package app.songyan.ime.candidate

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot

/**
 * Renderer-agnostic candidate area state derived from an engine snapshot.
 *
 * It deliberately drops engine internals (raw schema JSON, key handling rules): renderers only
 * read this and can never write it back. [revision] is the host's monotonic counter of real
 * engine states, so the cross-page [CandidateBrowser] can tell a genuine recomputation from a
 * display-only refresh.
 */
data class CandidatePresentationState(
    val revision: Int = 0,
    /** Current engine schema id; part of the composition generation. */
    val schemaID: String = "",
    /** Current physical layout identity; part of the composition generation. */
    val layout: String = "",
    /** Preedit text as shown; empty when not composing. */
    val preedit: String = "",
    /** The user's raw input while composing. */
    val rawInput: String = "",
    /** Real candidates of the current page, in engine order. */
    val candidates: List<Candidate> = emptyList(),
    /** In-page index of the highlighted candidate; -1 = none. */
    val highlightedIndex: Int = -1,
    val pageIndex: Int = 0,
    /** Candidates per page; 0 = the engine does not page. */
    val pageSize: Int = 0,
    val canPageBackward: Boolean = false,
    val canPageForward: Boolean = false,
    val isComposing: Boolean = false,
    /** A post-commit menu stays browsable without becoming marked composition. */
    val isPrediction: Boolean = false,
    /** English direct input hides the candidate area entirely. */
    val isEnglishMode: Boolean = false,
) {
    val hasCandidateMenu: Boolean get() = isComposing || isPrediction

    /**
     * Predictions have a candidate menu but no composition; English direct input supplies no menu,
     * while engine-backed English needs actual candidates.
     */
    val showsCandidateBar: Boolean get() = hasCandidateMenu && (!isEnglishMode || candidates.isNotEmpty())

    val showsExpandedArea: Boolean get() = showsCandidateBar && candidates.isNotEmpty()

    /** Whether the composition row is visible under [bar]'s rule (hidden without input by default). */
    fun showsCompositionRow(bar: CandidateBarDefinition): Boolean =
        when (bar.compositionRow.visible) {
            CandidateBarDefinition.CompositionRow.Visibility.HIDDEN -> false
            CandidateBarDefinition.CompositionRow.Visibility.COMPOSING_ONLY -> isComposing && !isEnglishMode
            CandidateBarDefinition.CompositionRow.Visibility.COMPOSING_WITH_IDLE_RAW -> !isEnglishMode
        }

    companion object {
        /** Builds the state from an engine snapshot, optionally overriding the preedit shown. */
        fun of(
            snapshot: EngineSnapshot,
            isEnglishMode: Boolean,
            preeditOverride: String? = null,
            layout: String = "",
            revision: Int = 0,
        ): CandidatePresentationState =
            CandidatePresentationState(
                revision = revision,
                schemaID = snapshot.schemaID,
                layout = layout,
                preedit = preeditOverride ?: snapshot.preedit,
                rawInput = snapshot.rawInput,
                candidates = snapshot.candidates,
                highlightedIndex = snapshot.highlightedIndex,
                pageIndex = snapshot.pageIndex,
                pageSize = snapshot.pageSize,
                canPageBackward = snapshot.canPageBackward,
                canPageForward = snapshot.canPageForward,
                isComposing = snapshot.isComposing,
                isPrediction = snapshot.isPrediction,
                isEnglishMode = isEnglishMode,
            )
    }
}

/**
 * An action a renderer may emit. Renderers cannot reach the session: the host maps each action
 * onto a real engine call, and every index comes from the engine's own candidate metadata.
 */
sealed interface CandidateAreaAction {
    /** Commit the current page's candidate at [index] (a real `Candidate.index`). */
    data class Select(
        val index: Int,
    ) : CandidateAreaAction

    /** Commit a cross-page accumulated candidate by its real page number and in-page index. */
    data class SelectOnPage(
        val page: Int,
        val index: Int,
    ) : CandidateAreaAction

    data object HighlightNext : CandidateAreaAction

    data object HighlightPrevious : CandidateAreaAction

    /** Real engine paging (moves the engine highlight). */
    data object PageForward : CandidateAreaAction

    data object PageBackward : CandidateAreaAction

    /** Read-only request for the next unloaded page; never moves the engine's page or highlight. */
    data object LoadMore : CandidateAreaAction

    data object Expand : CandidateAreaAction

    data object Collapse : CandidateAreaAction
}

/** The action sink a renderer holds; the host owns the handler and forwards to the real session. */
fun interface CandidateAreaActionSink {
    fun perform(action: CandidateAreaAction)
}

/** Typography injected by the skin; only plain values so the dependency direction stays one-way. */
data class CandidateTypography(
    val candidateSize: Double,
    val secondarySize: Double,
) {
    companion object {
        val STANDARD = CandidateTypography(candidateSize = 20.0, secondarySize = 10.0)
    }
}

/**
 * Everything a renderer needs for one draw: state, config, action sink and the skin's typography.
 *
 * When the host has fed [browser] with cross-page reads, [displayEntries] shows them continuously
 * and falls back to the single page [state] holds otherwise. [visibleEntries] is a read-only
 * subset of real candidates (for example a verified-pronunciation filter) and an empty non-null
 * subset must never fall back to the unfiltered page.
 */
data class CandidateAreaContext(
    val state: CandidatePresentationState,
    val bar: CandidateBarDefinition,
    val actions: CandidateAreaActionSink,
    val typography: CandidateTypography,
    val browser: CandidateBrowser = CandidateBrowser(),
    val isExpanded: Boolean = false,
    val visibleEntries: List<BrowserCandidate>? = null,
    val visibleHasMore: Boolean? = null,
    val visibleFrontier: Int? = null,
    /**
     * How far the collapsed strip's content is lifted above the row centre, in dp. Used when a
     * reading row sits above the word row so the two lines stay close; only the drawing position
     * moves, each cell's hit region keeps the full row height.
     */
    val contentLift: Double = 0.0,
) {
    val hasMoreEntries: Boolean get() = visibleHasMore ?: browser.hasMore

    val entryFrontier: Int? get() = visibleFrontier ?: browser.nextPageToLoad

    val displayEntries: List<BrowserCandidate>
        get() {
            visibleEntries?.let { return it }
            if (!browser.hasNoEntries) return browser.entries
            return state.candidates.map {
                BrowserCandidate(pageIndex = state.pageIndex, pageLocalIndex = it.index, candidate = it)
            }
        }

    /** The select action carries the entry's real page ownership. */
    fun selectAction(entry: BrowserCandidate): CandidateAreaAction =
        CandidateAreaAction.SelectOnPage(page = entry.pageIndex, index = entry.pageLocalIndex)

    fun isHighlighted(entry: BrowserCandidate): Boolean =
        entry.pageIndex == state.pageIndex && entry.pageLocalIndex == state.highlightedIndex

    /**
     * Position in [displayEntries] whose cell starts the next read-only page load, or null when
     * nothing is left. Prefetching this far ahead means a fling finds the pages in place.
     */
    fun prefetchIndex(entryCount: Int): Int? {
        if (!hasMoreEntries || entryCount <= 0) return null
        return maxOf(0, entryCount - PREFETCH_DISTANCE)
    }

    /** Identity of the scroll position; a real composition change or a filter toggle resets it. */
    val scrollIdentity: CandidateScrollIdentity
        get() =
            CandidateScrollIdentity(
                generation = browser.generation,
                rawInput = state.rawInput,
                isFiltered = visibleEntries != null,
                filteredLeadingEntryId = visibleEntries?.firstOrNull()?.id,
            )

    companion object {
        /** About two expanded screens or four collapsed ones for most schemes. */
        const val PREFETCH_DISTANCE: Int = 30
    }
}

/**
 * When the candidate scroll position must return to the start.
 *
 * On a real composition change ([CandidateGeneration] changes on every real state recomputation)
 * or when the reading filter toggles, the old offset is meaningless: keeping it would leave the
 * new composition's first candidate off screen. Read-only appends within one composition do not
 * reset, so the user is not pulled back to the start mid-scroll.
 */
data class CandidateScrollIdentity(
    val generation: CandidateGeneration?,
    val rawInput: String,
    val isFiltered: Boolean,
    val filteredLeadingEntryId: String?,
)
