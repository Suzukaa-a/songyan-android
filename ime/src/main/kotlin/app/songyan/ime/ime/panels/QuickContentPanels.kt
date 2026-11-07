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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The view of a panel that has been opened at least once, or null while it has never opened.
 *
 * Editor, insert and refresh callbacks can arrive while a panel is closed, so they read this
 * instead of the lazy value itself, which would build a panel the user never opened.
 */
private val <T> Lazy<T>.initializedValue: T?
    get() = if (isInitialized()) value else null

/**
 * Owns the quick-content panels of one keyboard instance and the actions they trigger.
 * UI-thread access lets each view wait until it opens, keeping unused panels out of keyboard startup.
 *
 * The keyboard only calls [openSymbols], [openPhrases], [openKeyboardSettings], [onTextInserted]
 * and [close]: this type shows the panel immediately, loads its lists off the main thread, keeps
 * them in sync with what the user inserts, and forwards actions to [Callbacks]. Everything the
 * editor needs (commit, repeat, date insert, mode switch, settings) stays behind those callbacks,
 * so no panel ever touches the engine or the input connection.
 *
 * A panel's view is built the first time it opens and then reused; a keyboard that never opens one
 * does not pay for it, and only an opening may create one. The factory that creates them also
 * records them, so appearance refresh and memory trimming reach every existing panel by themselves.
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

        /**
         * One step of a held edit-panel key. [stillHeld] is asked when the step runs: a hold that
         * ended while the step was queued must not act. The default treats it as a plain key.
         */
        fun repeatEdit(
            action: EditAction,
            extend: Boolean,
            stillHeld: () -> Boolean,
        ) = edit(action, extend)

        /** A panel's search key: the keyboard takes over to type the query. */
        fun startSearch(kind: PanelSearchKind)

        /** The emoji metadata version the editor can render; 0 when it does not say. */
        fun emojiMetadataVersion(): Int

        /** Leaves the keyboard for the settings screen where keyboard tools are managed. */
        fun manageTools()
    }

    /** Every panel view created so far; appearance refresh and memory trimming walk it. */
    private val initializedPanels = mutableListOf<QuickContentPanelView>()

    /**
     * A panel view created at most once, the first time its panel opens.
     *
     * The factory registers what it creates, so declaring a panel through it is all it takes to be
     * reached by [refreshAppearance] and [trimMemory]; a new panel cannot be left out of them.
     */
    private fun <T : QuickContentPanelView> lazyPanel(factory: () -> T): Lazy<T> =
        lazy(LazyThreadSafetyMode.NONE) {
            val panel = factory()
            initializedPanels += panel
            panel
        }

    private val symbols: Lazy<SymbolPanelView> =
        lazyPanel {
            SymbolPanelView(context).apply {
                onOpenSettings = { openSettingsScreen(SettingsTarget.QUICK_CONTENT) }
                onInsert = { text ->
                    callbacks.insert(text)
                    scope.launch { recordRecentSymbol(text) }
                }
                onBackspace = { callbacks.deleteBackward() }
                onSpace = { callbacks.insert(" ") }
                onSearch = {
                    close()
                    callbacks.startSearch(PanelSearchKind.SYMBOLS)
                }
            }
        }

    private val symbolPage: Lazy<SymbolPagePanelView> =
        lazyPanel {
            SymbolPagePanelView(context).apply {
                onInsert = { text ->
                    callbacks.insert(text)
                    // The page's own grid stays as it was: the recent row is rebuilt when it next opens.
                    scope.launch { recordRecentSymbol(text) }
                    callbacks.symbolPageInserted()
                }
                onAction = { action -> callbacks.symbolPageAction(action) }
                isLocked = { callbacks.symbolPageLocked() }
            }
        }

    /** The page being shown, and whether its next data push is the first (the page then opens on its first tab). */
    private var symbolPageRequest: SymbolPageRequest? = null
    private var symbolPageFresh = false

    private val phrases: Lazy<PhraseClipboardPanelView> =
        lazyPanel {
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
        }

    private val settings: Lazy<KeyboardSettingsPanelView> =
        lazyPanel {
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
        }

    private val emojiSource = EmojiSource(context)

    private val emoji: Lazy<EmojiPanelView> =
        lazyPanel {
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
        }

    private val edit: Lazy<EditPanelView> =
        lazyPanel {
            EditPanelView(context).apply {
                onEdit = { action, extend -> callbacks.edit(action, extend) }
                onEditRepeated = { action, extend, stillHeld -> callbacks.repeatEdit(action, extend, stillHeld) }
            }
        }

    /** The tools panel; null when this keyboard has no tools (no gateway or no editor side). */
    private val toolsPanel: Lazy<KeyboardToolsPanel>? =
        if (tools != null && toolEditor != null) lazyPanel { KeyboardToolsPanel(context) } else null

    /**
     * The tools panel's decisions. It captures and writes through [toolEditor], runs tools through
     * [tools] on this panel's own scope, and never touches the engine. Built with the panel.
     */
    private val toolsSession: Lazy<KeyboardToolsSession>? =
        toolsPanel?.let { panel ->
            lazy(LazyThreadSafetyMode.NONE) {
                val view = panel.value
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
        }

    /** The panel the keyboard last opened; null before the first one and after [close]. */
    private var shown: QuickContentPanelView? = null
    private var content: QuickContentPanelContent? = null

    /**
     * Puts full content reads and recent-list writes in one order.
     *
     * A read that started before an insertion could otherwise finish after it and push its older
     * snapshot over the newer recent row, and two taps in a row could show their lists swapped.
     */
    private val contentMutex = Mutex()

    /**
     * The panel actually on screen. [shown] alone is stale after a panel closes itself through the
     * host (its own close button, a bottom key), and a hidden panel must receive nothing.
     */
    private val visiblePanel: QuickContentPanelView?
        get() = if (host.isShowing) shown else null

    /** Whether a panel currently covers the key area. */
    val isShowing: Boolean get() = host.isShowing

    fun openSymbols() {
        val panel = symbols.value
        show(panel)
        content?.let { applyContent(it) }
        scope.launch { refreshContent() }
    }

    /** The categorized symbol page: category tabs and a scrolling grid; its lists load off the main thread. */
    fun openSymbolPage(request: SymbolPageRequest) {
        symbolPageRequest = request
        symbolPageFresh = true
        val panel = symbolPage.value
        panel.enterLabel = request.enterLabel
        show(panel)
        content?.let { applyContent(it) }
        scope.launch { refreshContent() }
    }

    /** Draws the page again (the lock changed). */
    fun refreshSymbolPage() {
        symbolPage.initializedValue?.invalidate()
    }

    fun openPhrases() {
        val panel = phrases.value
        panel.openSection(clipboard = false)
        panel.updateQuickInsert(callbacks.quickInsertState())
        show(panel)
        content?.let { applyContent(it) }
        scope.launch { refreshContent() }
    }

    /** The phrases and clipboard panel, on the clipboard history. */
    fun openClipboard() {
        val panel = phrases.value
        panel.openSection(clipboard = true)
        panel.updateQuickInsert(callbacks.quickInsertState())
        show(panel)
        content?.let { applyContent(it) }
        scope.launch { refreshContent() }
    }

    /** The emoji panel: categories, recents, skin tones; the lists load off the main thread. */
    fun openEmoji() {
        val panel = emoji.value
        panel.reset()
        show(panel)
        scope.launch {
            val catalog = emojiSource.catalog(callbacks.emojiMetadataVersion())
            val recents = dataSource.loadRecentEmoji()
            // The panel may have been closed or replaced while the catalog loaded.
            if (visiblePanel === panel) {
                panel.lookup(catalog::find)
                panel.update(catalog.categories, recents)
            }
        }
    }

    /** The edit panel: arrows, select, select all, copy, cut, paste, undo. */
    fun openEdit() {
        val panel = edit.value
        panel.reset()
        show(panel)
    }

    /** The edit panel's live keys; ignored while another panel is showing. */
    fun updateEditAvailability(value: EditAvailability) {
        val panel = edit.initializedValue ?: return
        if (visiblePanel === panel) panel.setAvailability(value)
    }

    /** A new selection state from the editor, for Copy and Cut. */
    fun updateEditSelection(hasSelection: Boolean) {
        val panel = edit.initializedValue ?: return
        if (visiblePanel === panel) panel.setSelection(hasSelection)
    }

    /** The emoji catalog for the editor's renderer, for the keyboard's own emoji search. */
    suspend fun emojiCatalog(): EmojiCatalog = emojiSource.catalog(callbacks.emojiMetadataVersion())

    /** The symbols and phrases for the keyboard's own search: what the panels hold, or a fresh read. */
    suspend fun content(): QuickContentPanelContent = content ?: dataSource.loadContent()

    private suspend fun updateEmojiRecents() {
        val recents = dataSource.loadRecentEmoji()
        val panel = emoji.initializedValue ?: return
        // The rows are loaded again when the panel opens; a closed panel keeps none of them.
        if (visiblePanel === panel) panel.updateRecents(recents)
    }

    fun openKeyboardSettings() {
        val panel = settings.value
        panel.update(callbacks.quickSettingsState())
        show(panel)
    }

    /**
     * The tools panel. It opens with the source text captured and the tools listed; [pinnedToolId]
     * runs that tool at once, which is what a pinned toolbar entry does.
     */
    fun openTools(pinnedToolId: String? = null): Boolean {
        val session = toolsSession?.value ?: return false
        session.open(pinnedToolId)
        return true
    }

    /**
     * The preview of an answer another app returned: the keyboard calls this when it comes back to
     * the editor that handed the text out. False when nothing waits, so the key area is left alone.
     */
    fun openProcessTextResult(onlyIfToolsOpen: Boolean = false): Boolean {
        val toolsView = toolsPanel?.initializedValue
        if (onlyIfToolsOpen && (!host.isShowing || toolsView == null || shown !== toolsView)) return false
        if (host.isShowing && shown !== toolsView) return false
        val session = toolsSession?.value ?: return false
        if (onlyIfToolsOpen) {
            session.open()
            return true
        }
        return session.openAnswer()
    }

    /** A day/night or font-scale change reaches the panels built so far too; they outlive one keyboard show. */
    fun refreshAppearance() {
        for (panel in initializedPanels) panel.refreshAppearance()
    }

    /**
     * Releases what hidden panels and the emoji catalog hold; each is loaded again when its panel
     * opens. The open panel keeps its data, so trimming never changes what the user sees. A panel
     * that never opened holds nothing and is not built just to be released.
     */
    fun trimMemory() {
        content = null
        val visible = visiblePanel
        // A hidden page loads its categories again from the request of its own next opening.
        val pagePanel = symbolPage.initializedValue
        if (pagePanel == null || visible !== pagePanel) {
            symbolPageRequest = null
            symbolPageFresh = false
        }
        for (panel in initializedPanels) {
            if (panel === visible) panel.releaseLayoutCache() else panel.releaseData()
        }
        // The catalog also serves the keyboard's own emoji search, so it goes unless the panel shows it.
        val emojiPanel = emoji.initializedValue
        if (emojiPanel == null || visible !== emojiPanel) emojiSource.release()
    }

    /** Leaves the keyboard surface and opens the app's settings, where the empty list is filled. */
    private fun openSettingsScreen(target: SettingsTarget) {
        close()
        callbacks.openSettingsPage(target)
    }

    /** After a commit the repeat preview changed while the settings panel may be open. */
    fun onTextInserted() {
        val panel = phrases.initializedValue ?: return
        if (visiblePanel === panel) panel.updateQuickInsert(callbacks.quickInsertState())
    }

    /** Back inside a panel that has pages (an open choice of the settings panel); false when there is none. */
    fun navigateBack(): Boolean {
        val panel = settings.initializedValue ?: return false
        return visiblePanel === panel && panel.backToGrid()
    }

    /**
     * The panel surface went away without [close] (its own close button hides the host directly).
     * Nothing may update a panel that is no longer on screen, and no tool run may land in it.
     */
    fun onHostHidden() {
        toolsSession?.initializedValue?.closed()
        shown = null
    }

    /** Ends a closing panel's exit at once; see [QuickContentHost.settle]. */
    fun settle() = host.settle()

    /** Closes the panel if one is open; returns whether it closed anything. */
    fun close(preserveExternalAnswer: Boolean = false): Boolean {
        if (!host.isShowing) return false
        // Host hiding also reports onHostHidden synchronously; clear the session first so a
        // lifecycle close cannot let that callback consume a pending external answer.
        toolsSession?.initializedValue?.closed(preserveExternalAnswer)
        host.hide()
        shown = null
        edit.initializedValue?.reset()
        emoji.initializedValue?.reset()
        return true
    }

    private fun show(panel: QuickContentPanelView) {
        shown = panel
        host.show(panel)
    }

    private suspend fun refreshContent() =
        contentMutex.withLock {
            val loaded = dataSource.loadContent()
            // Closed while loading: the panel is gone, so neither it nor the owner keeps the result.
            if (visiblePanel == null) return@withLock
            content = loaded
            applyContent(loaded)
        }

    /**
     * Records an inserted symbol and shows it in the open panel's recent row.
     *
     * The store answers with the list to show, so this updates that list only: reading the whole
     * content again would reload the catalog, the phrases and the clipboard for one row.
     */
    private suspend fun recordRecentSymbol(text: String) =
        contentMutex.withLock {
            val recents = dataSource.recordRecentSymbol(text)
            // Closed or trimmed meanwhile: the next opening loads everything, so nothing comes back here.
            val panel = visiblePanel ?: return@withLock
            content = content?.copy(recentSymbols = recents)
            val symbolView = symbols.initializedValue
            if (symbolView != null && panel === symbolView) symbolView.updateRecents(recents)
        }

    /**
     * Pushes freshly loaded content to the panel on screen only. A hidden panel was released on trim
     * and must not be filled back by a load that finishes after it closed.
     */
    private fun applyContent(loaded: QuickContentPanelContent) {
        val panel = visiblePanel ?: return
        val symbolView = symbols.initializedValue
        val phraseView = phrases.initializedValue
        when {
            symbolView != null && panel === symbolView ->
                symbolView.update(loaded.catalog, loaded.recentSymbols, loaded.builtinCatalog)
            phraseView != null && panel === phraseView ->
                phraseView.update(
                    loaded.phrases.map { it.text },
                    loaded.clipboard,
                    loaded.clipboardEnabled,
                )
            panel === symbolPage.initializedValue -> applySymbolPage(loaded)
        }
    }

    private fun applySymbolPage(loaded: QuickContentPanelContent) {
        val request = symbolPageRequest ?: return
        val panel = symbolPage.initializedValue ?: return
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
        panel.update(titled, request.asciiMarks, reopen = symbolPageFresh)
        symbolPageFresh = false
    }
}
