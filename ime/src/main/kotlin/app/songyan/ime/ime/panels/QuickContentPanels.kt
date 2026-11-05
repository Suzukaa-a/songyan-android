package app.songyan.ime.ime.panels

import android.content.Context
import app.songyan.ime.content.EmojiCatalog
import app.songyan.ime.content.QuickInsertDateFormat
import app.songyan.ime.ime.EditAction
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.support.SettingsTarget
import app.songyan.ime.layout.KeyboardPageCategory
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.SymbolPageCategories
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Owns the three quick-content panels of one keyboard instance and the actions they trigger.
 *
 * The keyboard only calls [openSymbols], [openPhrases], [openKeyboardSettings], [onTextInserted]
 * and [close]: this type shows the panel immediately, loads its lists off the main thread, keeps
 * them in sync with what the user inserts, and forwards actions to [Callbacks]. Everything the
 * editor needs (commit, repeat, date insert, mode switch, settings) stays behind those callbacks,
 * so no panel ever touches the engine or the input connection.
 *
 * The symbol panel stays open for multiple insertions; inserting a phrase, a clipboard entry or a
 * quick insert closes it. The settings panel stays open while settings change.
 */
class QuickContentPanels(
    context: Context,
    private val host: QuickContentHost,
    private val scope: CoroutineScope,
    private val dataSource: QuickContentPanelDataSource,
    private val callbacks: Callbacks,
) {
    /**
     * What the categorized symbol page shows. [categories] null is the built-in page, derived from the
     * symbol library and the recent list when the data arrives; otherwise the page's own categories.
     */
    class SymbolPageRequest(
        val categories: List<KeyboardPageCategory>?,
        val asciiMarks: Boolean,
        val enterLabel: String,
        /** The everyday marks the pairing reaches nowhere else; the built-in 常用 lists them first. */
        val leadingMarks: List<String> = emptyList(),
        /** The schemas input language: the built-in 常用 adds the marks Japanese needs. */
        val inputLanguage: InputLanguage = InputLanguage.ZH_HANS,
    )

    interface Callbacks {
        /** Commits [text] through the existing editor path. */
        fun insert(text: String)

        /** A symbol of the categorized page was typed: the keyboard leaves the page unless it is locked. */
        fun symbolPageInserted()

        /** A function key of the categorized page: the keyboard runs it (and closes the page for a page switch). */
        fun symbolPageAction(action: LayoutAction)

        /** Whether the symbol page lock is on. */
        fun symbolPageLocked(): Boolean

        fun repeatLastInput()

        fun insertDateFormat(format: QuickInsertDateFormat)

        /** Deletes the character before the caret, as the keyboard's own delete key does. */
        fun deleteBackward()

        fun openSettings()

        /** Opens the settings app on a specific page. */
        fun openSettingsPage(target: SettingsTarget)

        /** The live values of the keyboard settings panel, read when it opens. */
        fun quickSettingsState(): QuickSettingsState

        /** Applies a setting at once and stores it where the settings app reads it. */
        fun applyQuickSetting(change: QuickSettingChange)

        /** Leaves the panel for the keyboard menu, where the layout is chosen. */
        fun openLayoutChooser()

        /** The repeat and date rows of the phrases panel, computed when the panel opens. */
        fun quickInsertState(): QuickInsertState

        /** One key of the edit panel; [extend] is true while its Select mode is on. */
        fun edit(
            action: EditAction,
            extend: Boolean,
        )

        /** A panel's search key: the keyboard takes over to type the query. */
        fun startSearch(kind: PanelSearchKind)

        /** The emoji metadata version the editor can render; 0 when it does not say. */
        fun emojiMetadataVersion(): Int
    }

    private val symbols: SymbolPanelView =
        SymbolPanelView(context).apply {
            onOpenSettings = { openSettingsScreen(SettingsTarget.QUICK_CONTENT) }
            onInsert = { text ->
                callbacks.insert(text)
                scope.launch {
                    dataSource.recordRecentSymbol(text)
                    refreshSymbols()
                }
            }
            onBackspace = { callbacks.deleteBackward() }
            onSpace = { callbacks.insert(" ") }
            onSearch = {
                close()
                callbacks.startSearch(PanelSearchKind.SYMBOLS)
            }
        }

    private val symbolPage: SymbolPagePanelView =
        SymbolPagePanelView(context).apply {
            onInsert = { text ->
                callbacks.insert(text)
                scope.launch { dataSource.recordRecentSymbol(text) }
                callbacks.symbolPageInserted()
            }
            onAction = { action -> callbacks.symbolPageAction(action) }
            isLocked = { callbacks.symbolPageLocked() }
        }

    /** The page being shown, and whether its next data push is the first (the page then opens on its first tab). */
    private var symbolPageRequest: SymbolPageRequest? = null
    private var symbolPageFresh = false

    private val phrases: PhraseClipboardPanelView =
        PhraseClipboardPanelView(context).apply {
            onOpenSettings = { target -> openSettingsScreen(target) }
            onRepeat = {
                callbacks.repeatLastInput()
                close()
            }
            onInsertDateFormat = { format ->
                callbacks.insertDateFormat(format)
                close()
            }
            onInsertText = { text ->
                callbacks.insert(text)
                close()
            }
            onDeleteClipboard = { text ->
                scope.launch {
                    dataSource.deleteClipboardEntry(text)
                    refreshContent()
                }
            }
            onEnableClipboard = {
                scope.launch {
                    dataSource.setClipboardEnabled(true)
                    refreshContent()
                }
            }
            onSearch = {
                close()
                callbacks.startSearch(PanelSearchKind.PHRASES)
            }
        }

    private val settings: KeyboardSettingsPanelView =
        KeyboardSettingsPanelView(context).apply {
            onChange = { change -> callbacks.applyQuickSetting(change) }
            onChooseLayout = {
                close()
                callbacks.openLayoutChooser()
            }
            onOpenQuickContent = { openPhrases() }
            onOpenSettings = {
                close()
                callbacks.openSettings()
            }
        }

    private val emojiSource = EmojiSource(context)

    private val emoji: EmojiPanelView =
        EmojiPanelView(context).apply {
            onInsert = { text ->
                callbacks.insert(text)
                scope.launch {
                    dataSource.recordRecentEmoji(text)
                    updateEmojiRecents()
                }
            }
            onSearch = {
                close()
                callbacks.startSearch(PanelSearchKind.EMOJI)
            }
            onBackspace = { callbacks.deleteBackward() }
        }

    private val edit: EditPanelView =
        EditPanelView(context).apply {
            onEdit = { action, extend -> callbacks.edit(action, extend) }
        }

    private var shown: QuickContentPanelView? = null
    private var content: QuickContentPanelContent? = null

    /** Whether a panel currently covers the key area. */
    val isShowing: Boolean get() = host.isShowing

    fun openSymbols() {
        show(symbols)
        content?.let { applyContent(it) }
        scope.launch { refreshContent() }
    }

    /** The categorized symbol page: category tabs and a scrolling grid; its lists load off the main thread. */
    fun openSymbolPage(request: SymbolPageRequest) {
        symbolPageRequest = request
        symbolPageFresh = true
        symbolPage.enterLabel = request.enterLabel
        content?.let { applySymbolPage(it) }
        show(symbolPage)
        scope.launch { refreshContent() }
    }

    /** Draws the page again (the lock changed). */
    fun refreshSymbolPage() {
        symbolPage.invalidate()
    }

    fun openPhrases() {
        phrases.openSection(clipboard = false)
        phrases.updateQuickInsert(callbacks.quickInsertState())
        show(phrases)
        content?.let { applyContent(it) }
        scope.launch { refreshContent() }
    }

    /** The phrases and clipboard panel, on the clipboard history. */
    fun openClipboard() {
        phrases.openSection(clipboard = true)
        phrases.updateQuickInsert(callbacks.quickInsertState())
        show(phrases)
        content?.let { applyContent(it) }
        scope.launch { refreshContent() }
    }

    /** The emoji panel: categories, recents, skin tones; the lists load off the main thread. */
    fun openEmoji() {
        emoji.reset()
        show(emoji)
        scope.launch {
            val catalog = emojiSource.catalog(callbacks.emojiMetadataVersion())
            val recents = dataSource.loadRecentEmoji()
            // The panel may have been closed or replaced while the catalog loaded.
            if (shown === emoji) {
                emoji.lookup(catalog::find)
                emoji.update(catalog.categories, recents)
            }
        }
    }

    /** The edit panel: arrows, select, select all, copy, cut, paste, undo. */
    fun openEdit() {
        edit.reset()
        show(edit)
    }

    /** The edit panel's live keys; ignored while another panel is showing. */
    fun updateEditAvailability(value: EditAvailability) {
        if (shown === edit) edit.setAvailability(value)
    }

    /** A new selection state from the editor, for Copy and Cut. */
    fun updateEditSelection(hasSelection: Boolean) {
        if (shown === edit) edit.setSelection(hasSelection)
    }

    /** The emoji catalog for the editor's renderer, for the keyboard's own emoji search. */
    suspend fun emojiCatalog(): EmojiCatalog = emojiSource.catalog(callbacks.emojiMetadataVersion())

    /** The symbols and phrases for the keyboard's own search: what the panels hold, or a fresh read. */
    suspend fun content(): QuickContentPanelContent = content ?: dataSource.loadContent()

    private suspend fun updateEmojiRecents() {
        emoji.updateRecents(dataSource.loadRecentEmoji())
    }

    fun openKeyboardSettings() {
        settings.update(callbacks.quickSettingsState())
        show(settings)
    }

    /** A day/night or font-scale change reaches the panels too; they outlive one keyboard show. */
    fun refreshAppearance() {
        symbols.refreshAppearance()
        symbolPage.refreshAppearance()
        phrases.refreshAppearance()
        settings.refreshAppearance()
        emoji.refreshAppearance()
        edit.refreshAppearance()
    }

    /**
     * Releases what hidden panels and the emoji catalog hold; each is loaded again when its panel
     * opens. The open panel keeps its data, so trimming never changes what the user sees.
     */
    fun trimMemory() {
        content = null
        for (panel in listOf<QuickContentPanelView>(symbols, symbolPage, phrases, settings, emoji, edit)) {
            if (panel === shown) panel.releaseLayoutCache() else panel.releaseData()
        }
        if (shown !== emoji) emojiSource.release()
    }

    /** Leaves the keyboard surface and opens the app's settings, where the empty list is filled. */
    private fun openSettingsScreen(target: SettingsTarget) {
        close()
        callbacks.openSettingsPage(target)
    }

    /** After a commit the repeat preview changed while the settings panel may be open. */
    fun onTextInserted() {
        if (shown === phrases) phrases.updateQuickInsert(callbacks.quickInsertState())
    }

    /** Back inside a panel that has pages (an open choice of the settings panel); false when there is none. */
    fun navigateBack(): Boolean = shown === settings && settings.backToGrid()

    /** Ends a closing panel's exit at once; see [QuickContentHost.settle]. */
    fun settle() = host.settle()

    /** Closes the panel if one is open; returns whether it closed anything. */
    fun close(): Boolean {
        if (!host.isShowing) return false
        host.hide()
        shown = null
        edit.reset()
        emoji.reset()
        return true
    }

    private fun show(panel: QuickContentPanelView) {
        shown = panel
        host.show(panel)
    }

    private suspend fun refreshContent() {
        val loaded = dataSource.loadContent()
        content = loaded
        applyContent(loaded)
    }

    /** Re-reads the recent list after an insert; the cached snapshot still holds the old one. */
    private suspend fun refreshSymbols() {
        val loaded = dataSource.loadContent()
        content = loaded
        symbols.update(loaded.catalog, loaded.recentSymbols)
    }

    private fun applyContent(loaded: QuickContentPanelContent) {
        symbols.update(loaded.catalog, loaded.recentSymbols)
        applySymbolPage(loaded)
        phrases.update(loaded.phrases.map { it.text }, loaded.clipboard, loaded.clipboardEnabled)
    }

    private fun applySymbolPage(loaded: QuickContentPanelContent) {
        val request = symbolPageRequest ?: return
        val categories =
            request.categories?.let { SymbolPageCategories.resolved(it, loaded.recentSymbols) }
                ?: SymbolPageCategories.builtin(
                    request.asciiMarks,
                    loaded.recentSymbols,
                    loaded.catalog.orderedCategories.map { category ->
                        SymbolPageCategories.LibrarySet(
                            category.id,
                            category.name,
                            loaded.catalog.itemsIn(category.id).map { it.text },
                        )
                    },
                    request.leadingMarks,
                    request.inputLanguage,
                )
        symbolPage.update(categories, request.asciiMarks, reopen = symbolPageFresh)
        symbolPageFresh = false
    }
}
