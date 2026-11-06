package app.songyan.ime.ime.panels

import android.content.Context
import app.songyan.ime.content.EmojiCatalog
import app.songyan.ime.content.QuickInsertDateFormat
import app.songyan.ime.ime.EditAction
import app.songyan.ime.ime.tools.KeyboardToolsEditor
import app.songyan.ime.ime.tools.KeyboardToolsGateway
import app.songyan.ime.ime.tools.KeyboardToolsPanel
import app.songyan.ime.ime.tools.KeyboardToolsSession
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
    private val context: Context,
    private val host: QuickContentHost,
    private val scope: CoroutineScope,
    private val dataSource: QuickContentPanelDataSource,
    private val callbacks: Callbacks,
    /** The app's tool store and runner; null leaves the tools panel out. */
    private val tools: KeyboardToolsGateway? = null,
    /** What a tool may read and write in the editor; null leaves the tools panel out. */
    private val toolEditor: KeyboardToolsEditor? = null,
    /** The scheme language that decides a tool's script-conversion direction. */
    private val inputLanguage: () -> InputLanguage = { InputLanguage.ZH_HANS },
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

        /** Leaves the keyboard for the settings screen where keyboard tools are managed. */
        fun manageTools()
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
            onManageTools = {
                // The tools page is a settings screen: the keyboard leaves before it opens.
                close()
                callbacks.manageTools()
            }
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

    private val toolsPanel: KeyboardToolsPanel? =
        if (tools != null && toolEditor != null) KeyboardToolsPanel(context) else null

    /**
     * The tools panel's decisions. It captures and writes through [toolEditor], runs tools through
     * [tools] on this panel's own scope, and never touches the engine.
     */
    private val toolsSession: KeyboardToolsSession? =
        toolsPanel?.let { view ->
            KeyboardToolsSession(
                scope = scope,
                panel = view,
                gateway = requireNotNull(tools),
                editor = requireNotNull(toolEditor),
                inputLanguage = inputLanguage,
                insert = { text ->
                    callbacks.insert(text)
                    close()
                },
                manage = { callbacks.manageTools() },
                present = { show(view) },
                dismiss = { close() },
            ).apply {
                view.onChoice = { kind -> chooseSource(kind) }
                view.onTool = { id -> run(id) }
                view.onApp = { id, label -> runApp(id, label) }
                view.onReplace = { replace() }
                view.onInsert = { insertResult() }
                view.onCopy = { copyResult() }
                view.onManage = { manageTools() }
            }
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

    /**
     * The tools panel. It opens with the source text captured and the tools listed; [pinnedToolId]
     * runs that tool at once, which is what a pinned toolbar entry does.
     */
    fun openTools(pinnedToolId: String? = null): Boolean {
        val session = toolsSession ?: return false
        session.open(pinnedToolId)
        return true
    }

    /**
     * The preview of an answer another app returned: the keyboard calls this when it comes back to
     * the editor that handed the text out. False when nothing waits, so the key area is left alone.
     */
    fun openProcessTextResult(onlyIfToolsOpen: Boolean = false): Boolean {
        if (onlyIfToolsOpen && (!host.isShowing || shown !== toolsPanel)) return false
        if (host.isShowing && shown !== toolsPanel) return false
        val session = toolsSession ?: return false
        if (onlyIfToolsOpen) {
            session.open()
            return true
        }
        return session.openAnswer()
    }

    /** A day/night or font-scale change reaches the panels too; they outlive one keyboard show. */
    fun refreshAppearance() {
        symbols.refreshAppearance()
        symbolPage.refreshAppearance()
        phrases.refreshAppearance()
        settings.refreshAppearance()
        emoji.refreshAppearance()
        edit.refreshAppearance()
        toolsPanel?.refreshAppearance()
    }

    /**
     * Releases what hidden panels and the emoji catalog hold; each is loaded again when its panel
     * opens. The open panel keeps its data, so trimming never changes what the user sees.
     */
    fun trimMemory() {
        content = null
        val panels =
            listOfNotNull<QuickContentPanelView>(symbols, symbolPage, phrases, settings, emoji, edit, toolsPanel)
        for (panel in panels) {
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

    /**
     * The panel surface went away without [close] (its own close button hides the host directly).
     * Nothing may update a panel that is no longer on screen, and no tool run may land in it.
     */
    fun onHostHidden() {
        toolsSession?.closed()
        shown = null
    }

    /** Ends a closing panel's exit at once; see [QuickContentHost.settle]. */
    fun settle() = host.settle()

    /** Closes the panel if one is open; returns whether it closed anything. */
    fun close(preserveExternalAnswer: Boolean = false): Boolean {
        if (!host.isShowing) return false
        // Host hiding also reports onHostHidden synchronously; clear the session first so a
        // lifecycle close cannot let that callback consume a pending external answer.
        toolsSession?.closed(preserveExternalAnswer)
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
        symbols.update(loaded.catalog, loaded.recentSymbols, loaded.builtinCatalog)
    }

    private fun applyContent(loaded: QuickContentPanelContent) {
        symbols.update(loaded.catalog, loaded.recentSymbols, loaded.builtinCatalog)
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
                            SymbolCategoryTitles.catalogTitle(
                                authorName = category.name,
                                id = category.id,
                                builtinCatalog = loaded.builtinCatalog,
                                lookup = context::getString,
                            ),
                            loaded.catalog.itemsIn(category.id).map { it.text },
                        )
                    },
                    request.leadingMarks,
                    request.inputLanguage,
                )
        // Only a page derived from built-in data has built-in tab titles; a saved page keeps the
        // names its author gave it, even when it started as a copy of the built-in one.
        val titled =
            if (request.categories == null) {
                categories.map { category ->
                    category.withName(
                        SymbolCategoryTitles.pageTitle(
                            authorName = category.name,
                            id = category.id,
                            builtinPage = true,
                            lookup = context::getString,
                        ),
                    )
                }
            } else {
                categories
            }
        symbolPage.update(titled, request.asciiMarks, reopen = symbolPageFresh)
        symbolPageFresh = false
    }
}
