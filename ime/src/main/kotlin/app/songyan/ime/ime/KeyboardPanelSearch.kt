package app.songyan.ime.ime

import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.SymbolCatalog
import app.songyan.ime.ime.panels.PanelSearchKind
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.keyboard.support.CaretMotion
import kotlinx.coroutines.launch

// Panel search (emoji, symbols, phrases) borrows the keyboard: while it is on, what the user types goes
// into a query instead of the editor, and the idle band lists the matches. The query is typed with the
// letters, the input method and the candidates the user already has, which is what makes searching in
// Chinese work.

/** The query being typed and what it finds: the texts a tap inserts, best match first. */
internal class PanelSearchState(
    val kind: PanelSearchKind,
) {
    var query = ""
    var results: List<String> = emptyList()
}

/** Matches of the symbol and phrase searches, computed from the data the panels already loaded. */
internal object PanelSearchMatching {
    /** Symbols by name, alias (pinyin or English) or the symbol itself; the catalog's own ranking is kept. */
    fun symbols(
        catalog: SymbolCatalog,
        query: String,
    ): List<String> = catalog.search(query).map { it.text }.take(RESULT_LIMIT)

    /**
     * Phrases whose text or search term contains the query, in the user's own order. An empty query
     * lists the phrases, as the panel does when it is not searching.
     */
    fun phrases(
        phrases: List<CommonPhrase>,
        query: String,
    ): List<String> = phrases.filter { it.matches(query) }.map { it.text }.take(RESULT_LIMIT)
}

/** What the routed editor callbacks need to feed text into the search. */
internal interface PanelSearchSink {
    fun append(text: String)

    fun backspace()

    fun submit()
}

/** Matches shown in the band: more than a screenful scrolls, but a query that matches half the catalog is noise. */
private const val RESULT_LIMIT = 40

/**
 * The editor callbacks while a search is on: text, deletion and Return feed the query, and nothing is
 * written to the editor (no marked text either), until a result is picked or the search ends.
 */
internal fun KeyboardController.Callbacks.routedThrough(search: () -> PanelSearchSink?): KeyboardController.Callbacks =
    copy(
        commit = { text, selected -> search()?.append(text)?.let { true } ?: commit(text, selected) },
        preedit = { text, language -> if (search() == null) preedit(text, language) },
        delete = { search()?.backspace() ?: delete() },
        moveCursor = { delta -> if (search() == null) moveCursor(delta) },
        action = { search()?.submit() ?: action() },
        contextBeforeCursor = { limit -> if (search() == null) contextBeforeCursor(limit) else null },
        deleteBefore = { count -> if (search() == null) deleteBefore(count) },
        replaceBeforeCursor = { expected, replacement ->
            search() == null && replaceBeforeCursor(expected, replacement)
        },
    )

internal val KeyboardController.panelSearchSink: PanelSearchSink
    get() =
        object : PanelSearchSink {
            override fun append(text: String) {
                val state = panelSearch ?: return
                state.query += text
                refreshPanelSearch()
            }

            override fun backspace() {
                val state = panelSearch ?: return
                if (state.query.isEmpty()) {
                    endPanelSearch()
                    return
                }
                state.query = state.query.substring(0, CaretMotion.left(state.query, state.query.length))
                refreshPanelSearch()
            }

            override fun submit() = endPanelSearch()
        }

/** The search key of a panel: the keyboard types the query from here on. */
internal fun KeyboardController.beginPanelSearch(kind: PanelSearchKind) {
    val quick = panels ?: return
    if (retired || panelSearch != null) return
    closeKeyboardMenu()
    enqueue {
        // A composition in progress belongs to the editor: confirm it before the search takes over.
        session.takeIf { ready }?.commitComposition()
        panelSearch = PanelSearchState(kind)
        // The query is typed with letters: a 123 or symbol page left open under the panel would hide them.
        page = null
        symbolMarksOverride = null
        render()
        syncBackOwnership()
        // The data is read off the main thread; what the panels already loaded is reused.
        scope.launch {
            when (kind) {
                PanelSearchKind.EMOJI -> {
                    val loaded = quick.emojiCatalog()
                    enqueue {
                        emojiCatalog = loaded
                        refreshPanelSearch()
                    }
                }
                PanelSearchKind.SYMBOLS, PanelSearchKind.PHRASES -> {
                    val loaded = quick.content()
                    enqueue {
                        searchContent = loaded
                        refreshPanelSearch()
                    }
                }
            }
        }
    }
}

internal fun KeyboardController.endPanelSearch() {
    val ending = panelSearch ?: return
    enqueue {
        if (panelSearch !== ending) return@enqueue
        // Keep callbacks routed to the query until its engine composition has been discarded.
        val active = session.takeIf { ready }
        if (active != null && !active.discardComposition()) return@enqueue
        panelSearch = null
        render()
        syncBackOwnership()
    }
}

private fun KeyboardController.refreshPanelSearch() {
    val state = panelSearch ?: return
    state.results =
        when (state.kind) {
            PanelSearchKind.EMOJI ->
                emojiCatalog
                    ?.search(state.query, RESULT_LIMIT)
                    .orEmpty()
                    .map { it.emoji }
            PanelSearchKind.SYMBOLS ->
                searchContent
                    ?.let {
                        PanelSearchMatching.symbols(
                            it.catalog,
                            state.query,
                        )
                    }.orEmpty()
            PanelSearchKind.PHRASES ->
                searchContent
                    ?.let {
                        PanelSearchMatching.phrases(
                            it.phrases,
                            state.query,
                        )
                    }.orEmpty()
        }
    render()
}

/**
 * The band while a search is on and nothing is composed: a leading chip that names the query (tapping
 * it ends the search), then the matching emoji. Null while composing, so the candidates stay visible.
 */
internal fun KeyboardController.panelSearchRow(snapshot: EngineSnapshot): EnglishSuggestionRow? {
    val state = panelSearch ?: return null
    if (snapshot.isComposing) return null
    val chip =
        if (state.query.isEmpty()) {
            keyNames.chinese().getString(
                when (state.kind) {
                    PanelSearchKind.EMOJI -> R.string.ime_emoji_search_empty
                    PanelSearchKind.SYMBOLS -> R.string.ime_symbol_search_empty
                    PanelSearchKind.PHRASES -> R.string.ime_phrase_search_empty
                },
            )
        } else {
            keyNames.chinese().getString(R.string.ime_emoji_search_chip, state.query)
        }
    val texts = listOf(chip) + state.results
    val cands = texts.mapIndexed { index, text -> Candidate(index = index, text = text) }
    return EnglishSuggestionRow(
        EngineSnapshot(isPrediction = true, candidates = cands, pageSize = cands.size, isLastPage = true),
        fromEngine = false,
    )
}

/**
 * The query with the spelling still being typed, at the start of the band while the engine composes,
 * so the search shows what is typed before anything is committed. Null when no search is on or
 * nothing is composing (the idle band's own query chip says it then).
 */
internal fun KeyboardController.searchEcho(active: InputSession?): List<RailItem>? {
    val state = panelSearch ?: return null
    if (active?.snapshot?.isComposing != true) return null
    val text = keyNames.chinese().getString(R.string.ime_emoji_search_chip, state.query + lastMarked)
    return listOf(RailItem(text, RailKind.ECHO))
}

/**
 * A result was tapped (index 0 is the query chip, which ends the search): the result goes into the
 * editor and the search stays open with its query, so several results can be inserted in a row.
 */
internal suspend fun KeyboardController.selectPanelSearchResult(
    index: Int,
    text: String,
) {
    if (index == 0) {
        endPanelSearch()
        return
    }
    val state = panelSearch ?: return
    val kind = state.kind
    // A lone ellipsis is typed as the Chinese six-dot form, as the symbol panel does.
    val literal = if (kind == PanelSearchKind.SYMBOLS && text == "…") "……" else text
    // The routed callbacks feed the query while a search is on; step it aside for this one insert.
    panelSearch = null
    val active = session.takeIf { ready }
    try {
        if (active != null) active.insertLiteral(literal) else callbacks.commit(literal, false)
    } finally {
        panelSearch = state
    }
    render()
    val source = quickContentDataSource ?: return
    when (kind) {
        PanelSearchKind.EMOJI -> scope.launch { source.recordRecentEmoji(text) }
        PanelSearchKind.SYMBOLS -> scope.launch { source.recordRecentSymbol(text) }
        else -> Unit
    }
}
