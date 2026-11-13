package app.songyan.ime.ime

import android.animation.ValueAnimator
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.songyan.ime.candidate.AmbiguitySpellingPicker
import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.candidate.ToolbarLayout
import app.songyan.ime.content.EmojiCatalog
import app.songyan.ime.content.QuickInsertDateFormat
import app.songyan.ime.ime.palette.KeyboardPalettes
import app.songyan.ime.ime.panels.EditAvailability
import app.songyan.ime.ime.panels.KeyboardPanelHost
import app.songyan.ime.ime.panels.PanelSearchKind
import app.songyan.ime.ime.panels.QuickContentPanelContent
import app.songyan.ime.ime.panels.QuickContentPanelDataSource
import app.songyan.ime.ime.panels.QuickContentPanels
import app.songyan.ime.ime.panels.QuickInsertHost
import app.songyan.ime.ime.panels.QuickInsertState
import app.songyan.ime.ime.panels.QuickSettingChange
import app.songyan.ime.ime.panels.QuickSettingsState
import app.songyan.ime.keyboard.engine.CandidateReadingFilter
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.engine.ReadingCandidate
import app.songyan.ime.keyboard.engine.SpellingRailChoices
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.keyboard.support.EnglishNextWordModel
import app.songyan.ime.keyboard.support.EnglishWordTracker
import app.songyan.ime.keyboard.support.SettingsTarget
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** What a generated companion certainly reads when the engine publishes no alphabet. */
internal const val LATIN_LETTERS = "abcdefghijklmnopqrstuvwxyz"

/** Key rows on every page; the nine-key shell shares the same four-band height budget. */
private const val PAGE_ROWS = 4

/** Token of an operation that runs for whichever editor is current (key presses). */
private const val ANY_GENERATION = -1

/** Presses kept while the session is prepared; beyond this the keyboard is not keeping up anyway. */
private const val MAX_HELD_KEYS = 64

/** Characters whose readings the keypad spelling remembers across compositions. */
internal const val MAX_KNOWN_READINGS = 4096

class KeyboardController(
    internal val context: Context,
    parentScope: CoroutineScope,
    hostCallbacks: Callbacks,
    internal val preferences: KeyboardPreferencesSource? = null,
    internal val quickContentDataSource: QuickContentPanelDataSource? = null,
    private val quickInsert: QuickInsertHost? = null,
    /** Where the toolbar's copied-text chip comes from; null shows none. */
    internal val copiedChipSource: CopiedChipSource? = null,
) {
    internal val keyNames = KeyNames(context)

    data class Callbacks(
        val commit: (String, Boolean) -> Boolean,
        val preedit: (String, InputLanguage) -> Unit,
        val delete: () -> Unit,
        val moveCursor: (Int) -> Unit,
        val action: () -> Unit,
        val error: (Throwable) -> Unit,
        val panelsChanged: (Boolean) -> Unit = {},
        /** Opens the app's settings screen from the in-keyboard settings panel. */
        val openSettings: () -> Unit = {},
        /** Hides the keyboard window (the toolbar's last entry). */
        val hideKeyboard: () -> Unit = {},
        val openSettingsPage: (SettingsTarget) -> Unit = {},
        /** Bounded text before the selection; null when the editor cannot report it. */
        val contextBeforeCursor: (Int) -> String? = { null },
        /** The keyboard just entered English input: the capital state of the caret is needed now. */
        val automaticCapsWanted: () -> Unit = {},
        /**
         * Replaces [expected], which must be exactly what precedes the caret, with [replacement] in one
         * edit; false (nothing changed) when the editor does not show that text there.
         */
        val replaceBeforeCursor: (expected: String, replacement: String) -> Boolean = { _, _ -> false },
        /** Deletes [count] UTF-16 units before the caret in one edit (a word replaced by a suggestion). */
        val deleteBefore: (Int) -> Unit = { count -> repeat(count) { delete() } },
        /**
         * One key of the edit panel against the editor: the action, whether Select is on, and whether the
         * keyboard may read text back (false in a private editor). True when the editor took it.
         */
        val editAction: (EditAction, Boolean, Boolean) -> Boolean = { _, _, _ -> false },
        /** Which edit keys are live (selection, clipboard); the flag says whether the keyboard may read text back. */
        val editAvailability: (
            Boolean,
        ) -> EditAvailability = { EditAvailability(hasSelection = false, canPaste = true) },
        /** The way to other input methods and to voice input. */
        val inputMethods: InputMethodAccess = InputMethodAccess.NONE,
        /** A button of the engine notice (retry, settings). */
        val engineNoticeAction: (EngineNoticeAction) -> Unit = {},
        /** The user picked an input method of another installed package from the menu; the composition is settled. */
        val switchSource: (sourceId: String, schemaId: String) -> Unit = { _, _ -> },
    )

    /**
     * Why the Chinese engine is not taking input, or null while it is. While set, the key area shows
     * the notice instead of keys and every key press is dropped; see [EngineNotice].
     */
    internal var engineNotice: EngineNotice? = null
        private set

    /** The system's other input methods as last read; refreshed when the menu opens or the notice shows. */
    internal var inputMethodOptions = InputMethodOptions()

    /** The system asked for the keyboard to draw its own input method switcher (API 35+). */
    internal var customSwitcherRequested = false

    /**
     * English direct input: the editor forces ASCII, the field is private, the session is in English
     * mode, or Rime owns ASCII mode itself (a schema switch or a key binding). The last one still
     * sends keys to the engine; the front end only follows it for the faces and the marks.
     */
    internal val englishInput: Boolean
        get() =
            policy.ascii ||
                privateDirectInput ||
                session.takeIf { ready }?.let { it.mode == InputMode.ENGLISH || it.snapshot.isAsciiMode } == true

    /**
     * Whether the marks the open page types and shows are ASCII now: the 中/英 branch the user
     * flipped, else the branch the page opens in, else the input language (English input opens the
     * ASCII marks, Chinese input the Chinese ones).
     */
    internal val asciiMarksNow: Boolean get() = asciiMarksOn(page)

    /** [asciiMarksNow] for the page of [kind] (null: the letters page, which has no branch of its own). */
    internal fun asciiMarksOn(kind: KeyboardPageKind?): Boolean {
        val opening =
            kind?.let {
                KeyboardPages.openingMarks(
                    it,
                    KeyboardLayoutCatalog.snapshot.value.registry
                        .composition(compositionKey(), layoutFamilies()),
                )
            }
        return KeyboardPages.marksAscii(symbolMarksOverride, opening, englishInput, policy.asciiMarks)
    }

    /**
     * Shows the engine's state in place of the keys, or the normal keyboard again when [value] is null.
     * Nothing is typed while a notice shows, so the engine takes over from an empty composition.
     */
    fun setEngineNotice(value: EngineNotice?) {
        if (retired || value == engineNotice) return
        val wasShowing = engineNotice != null
        engineNotice = value
        // The keys of a requested switch stay hidden until the new session draws them and fades
        // them in: until then they are the old input method's.
        if (value == null && wasShowing && announceWhenReady && ImeMotion.enabled) {
            switchFade?.cancel()
            keyboard.alpha = 0f
        }
        if (value != null) refreshInputMethodOptions()
        if (wasShowing != (value != null)) closeAll()
        syncToolbar()
        render()
    }

    /** Whether the system wants the keyboard to carry the input method switcher itself. */
    fun setCustomSwitcherRequested(value: Boolean) {
        if (customSwitcherRequested == value) return
        customSwitcherRequested = value
        syncToolbar()
    }

    internal fun refreshInputMethodOptions() {
        inputMethodOptions = callbacks.inputMethods.options()
        syncToolbar()
    }

    /** The emoji search being typed through the keyboard, or null. */
    internal var panelSearch: PanelSearchState? = null

    /** The catalog the emoji search looks in, once loaded. */
    internal var emojiCatalog: EmojiCatalog? = null

    /** The symbols and phrases the symbol and phrase searches look in, once loaded. */
    internal var searchContent: QuickContentPanelContent? = null

    /** What the keyboard writes to the editor; while an emoji search is on it feeds the query instead. */
    internal val callbacks: Callbacks =
        hostCallbacks.routedThrough {
            if (panelSearch !=
                null
            ) {
                panelSearchSink
            } else {
                null
            }
        }

    /** The vertical column of bar, keys and panels; one-handed mode narrows it. */
    internal val content =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(KeyboardPalette.from(context).surface)
        }
    internal val popupLayer = KeyPopupLayer(context)

    /** The column and the popup layer above it: a popup may rise over the candidate bar. */
    internal val frame =
        FrameLayout(context).apply {
            addView(content, FrameLayout.LayoutParams(-1, -2))
            addView(popupLayer, FrameLayout.LayoutParams(-1, -1))
        }
    internal val shell = OneHandShell(context, frame)

    /** The keyboard's outer view: the column plus the one-handed gutter when it is on. */
    val view: View get() = shell
    internal val candidates = CandidateBarView(context)
    internal val toolbar = ToolbarView(context)

    /** The system's inline autofill suggestions, shown where the toolbar is while the field is idle. */
    val inlineSuggestions = InlineSuggestionStrip(context)

    /** Whether the band was idle at the last render; the chip and the suggestions only use an idle band. */
    internal var bandIdle = true

    /**
     * The top band: the candidates while something is composed, the toolbar otherwise. Both are one
     * row tall, so swapping them never moves the key area.
     */
    internal val bandRow = FrameLayout(context)

    /** The reading row (14 / 18-key layouts only) above [bandRow]; one unit with it for panels and the menu. */
    internal val readingRow = ReadingRowView(context)
    internal val barRow =
        LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    internal val keyboard = KeyboardView(context)
    internal val feedback = KeyboardFeedback(context)
    internal val menu =
        KeyboardSwitcherPanel(
            context,
            BuiltinTemplateSchemas.QUANPIN,
            KeyboardLayoutKind.QWERTY26,
        ).apply { visibility = View.GONE }

    /**
     * Everything this controller launches (preference collectors, the layout catalog observer, the
     * worker, the panels) lives under its own child job, so [retire] stops all of it: a retired
     * controller is never collected into again and can be garbage collected.
     */
    internal val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    internal var session: InputSession? = null

    /**
     * The input language of the active scheme, read from the session's cached profile.
     *
     * The profile is resolved when the scheme or one of its script switches changes, never here:
     * this is a plain field read, safe for callers that need it outside the drawing path.
     */
    val inputLanguage: InputLanguage
        get() = session?.inputLanguage?.language ?: InputLanguage.ZH_HANS

    internal var pendingBackend: EngineBackend? = null
    internal var policy = EditorPolicy(false, false, false, EditorInfo.IME_ACTION_NONE)

    /** The page above the letter keyboard; null is the letter page itself. */
    internal var page: KeyboardPageKind? = null
        set(value) {
            field = value
            typedOnSymbolPage = false
        }

    /** A delivered digit or symbol arms the next space to return to letters for this visit. */
    internal var typedOnSymbolPage = false

    /** A phone field's pad is showing its page of the other dial characters. */
    internal var phoneSymbols = false

    /** The row symbol page shows its extra page of everyday marks (see [KeyboardPages.Config.everydayMarksPage]). */
    internal var everydayMarksPage = true

    /** The symbol branch chosen on the 123 / symbol pages; null follows the editor. */
    internal var symbolMarksOverride: Boolean? = null
    internal var layoutKind = KeyboardLayoutKind.QWERTY26
        set(value) {
            field = value
            keyboard.wideContentCapDp = WideSurface.contentCapDp(value)
        }
    internal var schemaId = BuiltinTemplateSchemas.QUANPIN
    internal var preferredLayout = KeyboardLayoutKind.QWERTY26

    /** A jump key switched to another layout for a visit: 返回 goes back to [preferredLayout]. */
    internal var visitingLayout = false
    internal var preferredSchema = BuiltinTemplateSchemas.QUANPIN
    internal var mergedSwipe: MergedSwipeConfig? = null

    /** The bundled 9-key first-key policy (idle: symbol cycle; composing: separator). */
    internal val t9FirstKey = T9FirstKeyState()

    /** The user's saved quick-symbol order; empty means the default sequence. */
    internal var t9QuickSymbols = ""

    /** The nine-key shell's first key is the live punctuation-cycling key, not a key the layout authored. */
    internal var t9FirstKeyCycles = false

    internal var mainDefinitionMemo: MainDefinitionMemo? = null

    /** The layouts a jump key may open, as last worked out (see [jumpableLayouts]). */
    internal var jumpTargetsMemo: JumpTargets.Memo? = null

    /**
     * The 9-key reading filter: real candidates read with their proven pronunciations. Choosing a
     * reading narrows the candidate list to the words that read that way; it never changes the
     * engine's input.
     */
    internal val readingFilter = CandidateReadingFilter()

    /** The composition spelling picker: engine-proven lockable spellings, and the lock in force. */
    internal val ambiguityPicker = AmbiguitySpellingPicker()

    /** What the 9-key composing rail offers right now (engine-backed, never guessed). */
    internal var railChoices = SpellingRailChoices()

    /** First visible rail item when the choices outnumber the rail's slots. */
    internal var railScroll = 0

    /** The composition revision the rail's engine queries last ran for; -1 forces the next one. */
    internal var railRevision = -1

    /** The user's reading placement choice for 14 / 18-key layouts; see [ReadingRowBudget] for when it applies. */
    internal var readingPlacement = ReadingPlacement.ROW

    /** The reading row's height in dp in this window, 0 when it does not fit; refreshed with the appearance. */
    internal var readingRowDp = ReadingRowBudget.ROW_DP

    /** The reading filter decides what the candidate bar lists, so a tap selects by absolute index. */
    internal var candidatesFiltered = false

    /** Letter -> key of each compact layout, from its own key labels; built once per layout. */
    internal val readingKeyMaps = HashMap<KeyboardLayoutKind, Map<Char, Char>>()

    /** The current page's candidates with their proven readings: what a keypad sentence is spelled from. */
    internal var pageReadings = emptyList<ReadingCandidate>()

    /** Readings seen earlier in the session, per character, so a later keypad sentence can use them. */
    internal val knownReadings = HashMap<Char, MutableList<String>>()

    /** ABC page word guessing: the word being typed, the built-in next-word table and the row's words. */
    internal val englishNextWords = EnglishNextWordModel()
    internal val englishWords = EnglishWordTracker(englishNextWords)
    internal var englishSuggestions = emptyList<String>()

    /** The schema whose English translator answers completions; null until probed or when none does. */
    internal var englishWordSchemaId: String? = null
    internal var englishProbeSource: String? = null
    internal var englishQueryJob: Job? = null

    /** The setting; read from the preferences. */
    internal var doubleSpacePeriodEnabled = true

    /** Overridable for tests: a device test cannot tap two keys within the real window reliably. */
    internal var doubleSpaceWindowMs = DoubleSpacePeriod.WINDOW_MS

    /** When the previous key was a delivered space (uptime ms), or [DoubleSpacePeriod.NOT_ARMED]. */
    internal var doubleSpaceArmedAt = DoubleSpacePeriod.NOT_ARMED

    /** Commits that reached the editor; tells whether a space key press typed anything. */
    internal var deliveredCommits = 0

    /** Bumped whenever the caret moves or the document changes, so a late completion is dropped. */
    internal var englishDocumentGeneration = 0

    /** The composition text last sent to the field; a late reading re-renders only when it changes it. */
    internal var lastMarked = ""
    internal var rowHeightDp = 56f
    internal var oneHandMode = KeyboardOneHandMode.OFF
    internal var lastOneHandSide = KeyboardOneHandMode.RIGHT
    internal var hideIdleToolbar = false

    /** The user's choice of idle toolbar entries and their order. */
    internal var toolbarLayout = ToolbarLayout.DEFAULT

    /** Input methods (engine ids) and layouts (wire ids) the user took out of the keyboard menu. */
    internal var menuHiddenSchemas: Set<String> = emptySet()
    internal var menuHiddenLayouts: Set<String> = emptySet()

    /** Input methods of the other installed packages the menu offers next to the running one's. */
    internal var otherInputMethods: List<OtherInputMethod> = emptyList()
        private set

    /** Main thread. */
    fun setOtherInputMethods(value: List<OtherInputMethod>) {
        if (value == otherInputMethods) return
        otherInputMethods = value
        syncMenu()
    }

    /** The user's shuangpin hints per letter; empty while they are off. */
    internal var shuangpinHints: Map<Char, String> = emptyMap()

    /** The stored light/dark choice, kept for the keyboard settings panel. */
    internal var colorMode = ColorMode.SYSTEM

    /** The user turned the input-method switch key on; layouts and footers then keep their globe keys. */
    internal var showsSwitchKey = false

    /** The symbol page stays open after a symbol is typed (the corner lock); stored by the user. */
    internal var symbolLock = false

    /** The space bar names the keyboard the user just switched to; see [announceSwitch]. */
    internal var switchNotice = false

    /** The switch to another package did not happen: nothing will arrive to announce. */
    fun cancelPendingSwitch() {
        announceWhenReady = false
        switchFade?.cancel()
        keyboard.alpha = 1f
        closeKeyboardMenu()
        render()
    }

    /** The engine is ready: a pending switch that kept the running engine shows its keys again. */
    fun settleSwitch() {
        if (announceWhenReady && ready && windowShown) {
            finishSwitch()
            render()
        }
    }

    /** Another package was picked: its keyboard is announced once its engine is ready and shown. */
    internal var announceWhenReady = false
    internal val noticeTimer = Handler(Looper.getMainLooper())
    internal var switchFade: ValueAnimator? = null
    internal val endSwitchNotice =
        Runnable {
            // Cleared here, not in the queued render: a render dropped for a newer editor must not
            // leave the name on the space bar.
            if (switchNotice) {
                switchNotice = false
                enqueue { render() }
            }
        }

    /** The dial pad of a number editor is showing, and the candidate bar is gone with it. */
    internal var dialPadShown = false

    /** The key area height every page is laid out against, the nine-key shell included. */
    internal val keyAreaHeightDp: Float get() = rowHeightDp * PAGE_ROWS
    internal var menuOpen = false
    internal var generation = 0
    internal var dispatchGeneration = 0
    internal var retired = false
    internal var selectingCandidate = false
    internal var preparing = false
    internal var ready = false

    /**
     * The current editor is truly private and the engine cannot stop learning, so composition keys
     * must be committed literally instead. Re-derived on every session preparation and every policy
     * change; never inherited from the previous editor.
     */
    internal var privateDirectInput = false
    internal var automaticShift = false
    private val operations = Channel<Pair<Int, suspend () -> Unit>>(Channel.UNLIMITED)

    /**
     * Key presses that arrived while the session for the current editor was still being prepared.
     * They are typed, in order, as soon as it is ready: a press is never dropped because the editor
     * restarted its input or the keyboard was still binding.
     */
    internal val heldKeys = ArrayDeque<suspend () -> Unit>()

    /**
     * The twelve-key kana surface's binding state. The model is driven on the operation worker;
     * only the stored 只用 flick setting is mirrored into it from the UI side.
     */
    internal val kana12 = Kana12BindingState()

    /**
     * Bumped when an input finishes: the presses still queued for it were meant for that field and
     * must not type into the field that replaces it. A restart of the same field does not finish its
     * input (it only starts it again), so the epoch stays and its queued presses still arrive.
     */
    private var keyEpoch = 0

    private val worker =
        scope.launch {
            for ((token, operation) in operations) {
                if ((token != generation && token != ANY_GENERATION) || retired) continue
                dispatchGeneration = generation
                try {
                    operation()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    candidates.status = context.getString(R.string.ime_unavailable)
                    callbacks.error(error)
                }
            }
        }

    /**
     * Inserts [text] through the session, on the engine queue: the composition is confirmed first, so
     * the inserted text never lands inside (or ahead of) a spelling still being typed.
     */
    internal fun insertText(text: String) {
        enqueue {
            // Inserted text ends whatever the kana model claimed on the composition tail.
            kana12Reset()
            val active = session.takeIf { ready }
            if (active != null) active.insertLiteral(text) else callbacks.commit(text, false)
        }
    }

    private val panelCallbacks =
        object : QuickContentPanels.Callbacks {
            override fun insert(text: String) = insertText(text)

            override fun symbolPageInserted() {
                // One symbol returns to the keyboard it came from, unless the page is locked. Queued
                // behind the insert so the text lands before the page changes.
                if (symbolLock) return
                enqueue {
                    panels?.close()
                    page = if (policy.numeric) KeyboardPageKind.NUMERIC else null
                    symbolMarksOverride = null
                    render()
                }
            }

            override fun symbolPageAction(action: LayoutAction) {
                when (action.type) {
                    LayoutActionKind.SYMBOL_LOCK -> {
                        toggleSymbolLock()
                        panels?.refreshSymbolPage()
                    }
                    LayoutActionKind.PAGE_SWITCH -> {
                        panels?.close()
                        enqueueKey(action)
                    }
                    else -> enqueueKey(action)
                }
            }

            override fun symbolPageLocked(): Boolean = symbolLock

            override fun deleteBackward() {
                // The keyboard's own delete path: it ends a composition first, then deletes text.
                enqueueKey(LayoutAction(LayoutActionKind.BACKSPACE))
            }

            override fun repeatLastInput() {
                enqueue { if (confirmComposition()) quickInsert?.repeatLastInput() }
            }

            override fun insertDateFormat(format: QuickInsertDateFormat) {
                enqueue { if (confirmComposition()) quickInsert?.insertDateFormat(format) }
            }

            override fun openSettings() {
                callbacks.openSettings()
            }

            override fun openSettingsPage(target: SettingsTarget) {
                callbacks.openSettingsPage(target)
            }

            override fun quickSettingsState(): QuickSettingsState = this@KeyboardController.quickSettingsState()

            override fun applyQuickSetting(change: QuickSettingChange) =
                this@KeyboardController.applyQuickSetting(change)

            override fun openLayoutChooser() = openKeyboardMenu()

            override fun quickInsertState(): QuickInsertState =
                quickInsert?.quickInsertState()
                    ?: QuickInsertState()

            override fun edit(
                action: EditAction,
                extend: Boolean,
            ) {
                enqueue {
                    if (confirmComposition()) callbacks.editAction(action, extend, !policy.privateInput)
                    refreshEditPanel()
                    render()
                }
            }

            override fun repeatEdit(
                action: EditAction,
                extend: Boolean,
                stillHeld: () -> Boolean,
            ) {
                enqueueRepeat(stillHeld) {
                    if (confirmComposition()) callbacks.editAction(action, extend, !policy.privateInput)
                    refreshEditPanel()
                    render()
                }
            }

            override fun startSearch(kind: PanelSearchKind) = beginPanelSearch(kind)

            override fun emojiMetadataVersion(): Int = policy.emojiMetaVersion
        }

    /** The quick-content panels; null until the app supplies its data source. */
    internal val panels: QuickContentPanels? =
        quickContentDataSource?.let { dataSource ->
            QuickContentPanels(
                context = context,
                host =
                    KeyboardPanelHost(content, keyboard, barRow).apply {
                        // Any panel close returns the key area: the back handler must stop owning Back.
                        onHidden = { callbacks.panelsChanged(false) }
                    },
                scope = scope,
                dataSource = dataSource,
                callbacks = panelCallbacks,
            )
        }

    init {
        wireViews()
    }

    internal fun enqueue(action: suspend () -> Unit) {
        if (!retired) operations.trySend(generation to action)
    }

    /**
     * Key presses outlive an input restart, but not the end of their input connection: they belong
     * to whatever editor is current when they run.
     *
     * [pressAt] is the monotonic press time, read here on the UI thread: a key whose effect depends
     * on how fast the user pressed it (the kana tap cycle) must not measure the queue delay.
     */
    internal fun enqueueKey(
        action: LayoutAction,
        t9FirstKey: Boolean = false,
        pressAt: Double = kana12Now(),
    ) {
        // No engine, no typing: a press here would reach the editor as raw text. Nor while another
        // package's input method is on its way: the keys on screen would type into the old one.
        if (engineNotice != null || announceWhenReady) return
        if (retired) {
            if (action.type == LayoutActionKind.BACKSPACE) {
                BackspaceTrace.record(BackspaceTrace.Event.DROPPED_KEYBOARD_RETIRED)
            }
            return
        }
        enqueuePress(action.type == LayoutActionKind.BACKSPACE) { dispatch(action, t9FirstKey, pressAt) }
    }

    /**
     * Queues one press of any key surface (the kana pad included) with the rules of [enqueueKey]:
     * it survives a restart of the same field and is held while the session is prepared, but never
     * reaches the editor that replaces the one it was pressed in.
     */
    internal fun enqueuePress(
        backspace: Boolean = false,
        press: suspend () -> Unit,
    ) {
        if (retired || engineNotice != null || announceWhenReady) return
        val epoch = keyEpoch
        operations.trySend(ANY_GENERATION to { runOrHold(backspace, epoch, press) })
    }

    /**
     * Queues one step of a held control. [stillHeld] is asked on the main thread when the step is
     * about to run: a release, a cancel or a second finger may end the hold while its steps are
     * still queued, and such a step must do nothing. Unlike [enqueueKey] a step is never kept for a
     * session that is still being prepared: a hold cannot outlive the session it was made in.
     */
    internal fun enqueueRepeat(
        stillHeld: () -> Boolean,
        step: suspend () -> Unit,
    ) {
        if (retired || engineNotice != null || announceWhenReady) return
        val epoch = keyEpoch
        val stepOp: suspend () -> Unit = {
            if (!retired && ready && epoch == keyEpoch && stillHeld()) step()
        }
        operations.trySend(ANY_GENERATION to stepOp)
    }

    /** Engine replies cannot authorize writes after the owning input generation ends. */
    internal fun fieldStillCurrent(): Boolean = dispatchGeneration == generation && !retired && !preparing

    /**
     * Returns once every press and operation queued so far has run. An engine swap waits for this
     * before it unbinds, so a press made just before the swap is handled by the engine it was made
     * on instead of reaching a keyboard with no session.
     */
    suspend fun awaitIdle() {
        if (retired) return
        val drained = CompletableDeferred<Unit>()
        if (operations.trySend(ANY_GENERATION to { drained.complete(Unit) }).isFailure) return
        drained.await()
    }

    private suspend fun runOrHold(
        backspace: Boolean,
        epoch: Int,
        press: suspend () -> Unit,
    ) {
        if (epoch != keyEpoch) return
        if (!ready && pendingBackend != null) {
            val held = heldKeys.size < MAX_HELD_KEYS
            if (held) heldKeys.addLast(press)
            if (backspace) {
                BackspaceTrace.record(
                    if (held) {
                        BackspaceTrace.Event.HELD_WHILE_PREPARING
                    } else {
                        BackspaceTrace.Event.DROPPED_HELD_QUEUE_FULL
                    },
                )
            }
            return
        }
        press()
    }

    /** Engine confirmation must not authorize a follow-up write to a replacement editor. */
    internal suspend fun confirmComposition(): Boolean {
        val active = session.takeIf { ready } ?: return true
        val committed = active.commitComposition()
        return committed && fieldStillCurrent()
    }

    /**
     * Back belongs to the keyboard while anything it can close is open: a panel, the menu, the
     * expanded candidates, or a key or candidate popup. Only then does the system get to hide it.
     */
    internal fun syncBackOwnership() {
        callbacks.panelsChanged(
            menuOpen ||
                candidates.expanded ||
                panels?.isShowing == true ||
                keyboard.popupOpen ||
                candidates.popupOpen ||
                panelSearch != null,
        )
    }

    /**
     * [keepComposition]: the editor still shows the engine's composition, so a keyboard view rebuilt
     * under it (a configuration change) continues that composition instead of discarding it.
     */
    fun bind(
        backend: EngineBackend,
        keepComposition: Boolean = false,
    ) {
        pendingBackend = backend
        enqueue { prepareSession(keepComposition) }
    }

    fun unbind() {
        generation++
        ready = false
        pendingBackend = null
        heldKeys.clear()
        enqueue { kana12Reset() }
        session?.engine?.releaseWordCompletions()
        englishQueryJob?.cancel()
        englishQueryJob = null
        englishWordSchemaId = null
        englishProbeSource = null
        englishWords.reset()
        englishSuggestions = emptyList()
        session?.retire()
        session = null
        mergedSwipe = null
        privateDirectInput = false
        // A requested switch keeps the menu up over the keys until the new ones fade in.
        if (!announceWhenReady) closeKeyboardMenu()
        callbacks.preedit("", InputLanguage.ZH_HANS)
        render()
    }

    fun configure(value: EditorPolicy) {
        val changed = value != policy
        if (changed && windowShown) copiedChipSource?.onInputStarted(value.privateInput)
        policy = value
        // A password field must not echo what is typed, not even as a magnified preview.
        keyboard.previewSuppressed = value.privateInput
        syncToolbar()
        syncPageWithEditor()
        // An editor whose restrictions change without a new input session, or whose session was
        // prepared under an earlier policy, must not keep that policy's mode: re-derive it on the
        // live session. A session that is still being prepared picks the policy up there instead.
        if (changed) enqueue { reapplyEditorMode() }
        render()
    }

    /**
     * A new editor, or the same editor restarted. [keepComposition] is true only when the editor
     * still shows the composition (see [EditorConnection.startInput]); the engine composition then
     * survives the restart instead of being discarded under the user's fingers.
     */
    fun startInput(
        value: EditorPolicy,
        keepComposition: Boolean = false,
    ) {
        // Search spelling belongs to the query, even when the editor itself survives a restart.
        val keepEditorComposition = keepComposition && panelSearch == null
        generation++
        // Reads queued under the old generation are dropped without running.
        candidates.abandonLoad()
        ready = false
        policy = value
        keyboard.previewSuppressed = value.privateInput
        // Autofill suggestions belong to the previous field; the system sends the new field's own.
        inlineSuggestions.clear()
        // Fail closed for a truly
        // private editor until the engine proves it can stop learning; a no-suggestions editor is
        // never private, so it starts (and stays) in Chinese.
        privateDirectInput = value.privateInput && session?.learningProtectionSupported != true
        // The word and its context belong to the previous editor's text.
        resetEnglishWord()
        // A new editor always starts on the letters page; a numeric editor then opens its digit pad.
        page = null
        phoneSymbols = false
        syncToolbar()
        syncPageWithEditor()
        symbolMarksOverride = null
        closeAll()
        // A new editor never inherits the previous one's kana claim, queued behind its own session.
        enqueue { kana12Reset() }
        enqueue { prepareSession(keepEditorComposition) }
    }

    private fun syncPageWithEditor() {
        if (policy.numeric) {
            page = KeyboardPageKind.NUMERIC
        } else if (page == KeyboardPageKind.NUMERIC) {
            page = null
        }
    }

    /** Re-reads the palette after a day/night or font-scale change without rebuilding the session. */
    fun refreshAppearance() {
        val palette = KeyboardPalette.from(context)
        rowHeightDp = palette.tokens.rowHeightDp
        recomputeReadingRow()
        readingRow.refreshAppearance()
        content.setBackgroundColor(palette.surface)
        popupLayer.refreshAppearance()
        shell.setBackgroundColor(palette.surface)
        shell.refreshAppearance(palette)
        candidates.refreshAppearance()
        toolbar.refreshAppearance()
        inlineSuggestions.refreshAppearance()
        keyboard.refreshAppearance()
        menu.refreshAppearance()
        panels?.refreshAppearance()
        render()
    }

    /**
     * Whether the host's capital-letter state matters right now. Automatic Shift only applies to
     * English input, so a running Chinese session never pays for asking the editor on every
     * selection update.
     */
    val wantsAutomaticShift: Boolean
        get() {
            // A session still being prepared will pick the stored value up, so it is always wanted.
            val active = session.takeIf { ready } ?: return true
            return active.mode == InputMode.ENGLISH || active.snapshot.isAsciiMode
        }

    fun setAutomaticShift(active: Boolean) {
        automaticShift = active
        enqueue {
            val current = session.takeIf { ready }
            if (current != null) {
                current.setAutomaticShift(active)
            }
            render()
        }
    }

    /** Dims or lights the edit panel's Copy, Cut and Paste from the editor and the clipboard as they are now. */
    private fun refreshEditPanel() {
        panels?.updateEditAvailability(callbacks.editAvailability(!policy.privateInput))
    }

    /** The editor reported a new selection: a Copy and Cut that depend on it follow while the edit panel is open. */
    fun onEditorSelectionChanged(
        start: Int,
        end: Int,
    ) {
        if (policy.privateInput) return
        panels?.updateEditSelection(start != end)
    }

    /** The user moved the caret or the host changed the document: the English word guess is stale. */
    fun onSelectionMovedExternally() {
        resetEnglishWord()
    }

    fun discardComposition() {
        enqueue {
            kana12Reset()
            session?.discardComposition()
            callbacks.preedit("", InputLanguage.ZH_HANS)
            render()
        }
    }

    /**
     * The keyboard is hidden while the editor stays: the composition is abandoned (removed from the
     * field and the engine) instead of lingering as marked text nobody can see being edited.
     */
    fun hideInput() {
        closeAll()
        heldKeys.clear()
        discardComposition()
    }

    fun finishInput() {
        inlineSuggestions.clear()
        generation++
        keyEpoch++
        heldKeys.clear()
        candidates.abandonLoad()
        ready = false
        resetEnglishWord()
        session?.engine?.releaseWordCompletions()
        callbacks.preedit("", InputLanguage.ZH_HANS)
        closeKeyboardMenu()
        enqueue {
            kana12Reset()
            session?.discardComposition()
            render()
        }
    }

    internal var windowShown = false

    /**
     * The keyboard is on screen for the current editor. Only now is the clipboard looked at for the
     * copied-text chip, and never in a private editor.
     */
    fun onWindowShown() {
        windowShown = true
        copiedChipSource?.onInputStarted(policy.privateInput)
        syncBand()
        // Another package's engine may have arrived while the keyboard was closed.
        if (announceWhenReady && ready) {
            finishSwitch()
            render()
        }
    }

    /** The keyboard window went away: the chip and the inline suggestions go with it. */
    fun onWindowHidden() {
        windowShown = false
        copiedChipSource?.onInputFinished()
        inlineSuggestions.clear()
        syncBand()
    }

    /**
     * Opens the categorized symbol page when the pairing's symbol page is one; false leaves the row
     * page to draw (also when no panel host exists).
     */
    internal fun openSymbolPage(): Boolean {
        val quick = panels ?: return false
        if (retired) return false
        val registry = KeyboardLayoutCatalog.snapshot.value.registry
        val choice =
            KeyboardPages.categorizedSymbols(layoutKind, registry.composition(compositionKey(), layoutFamilies()))
                ?: return false
        closeKeyboardMenu()
        val ascii = asciiMarksOn(KeyboardPageKind.SYMBOLS)
        quick.openSymbolPage(
            QuickContentPanels.SymbolPageRequest(
                categories = choice.categories,
                asciiMarks = ascii,
                // Only the built-in tabs take the pairing's missing marks; a user's categories stay as made.
                leadingMarks =
                    if (choice.categories == null) {
                        KeyboardPages.missingMarks(
                            layoutKind,
                            registry.composition(compositionKey(), layoutFamilies()),
                            ascii,
                        )
                    } else {
                        emptyList()
                    },
                enterLabel = enterLabel(),
                inputLanguage =
                    session?.inputLanguage?.language ?: app.songyan.ime.keyboard.engine.InputLanguage.ZH_HANS,
            ),
        )
        callbacks.panelsChanged(true)
        return true
    }

    /** Opens the symbol panel (long press on the 符号 key). */
    fun openSymbolPanel() {
        val quick = panels ?: return
        if (retired) return
        closeKeyboardMenu()
        quick.openSymbols()
        callbacks.panelsChanged(true)
    }

    /** Opens the phrase and clipboard panel (from the input-method menu). */
    fun openQuickContentPanel() {
        val quick = panels ?: return
        if (retired) return
        closeKeyboardMenu()
        quick.openPhrases()
        callbacks.panelsChanged(true)
    }

    /** Opens the clipboard history (the toolbar's first entry). */
    fun openClipboardPanel() {
        val quick = panels ?: return
        if (retired) return
        closeKeyboardMenu()
        quick.openClipboard()
        callbacks.panelsChanged(true)
    }

    /** Opens the emoji panel (the toolbar's emoji entry). */
    fun openEmojiPanel() {
        val quick = panels ?: return
        if (retired) return
        closeKeyboardMenu()
        quick.openEmoji()
        callbacks.panelsChanged(true)
    }

    /** Opens the edit panel (the toolbar's edit entry). */
    fun openEditPanel() {
        val quick = panels ?: return
        if (retired) return
        closeKeyboardMenu()
        quick.openEdit()
        refreshEditPanel()
        callbacks.panelsChanged(true)
    }

    /** Opens the in-keyboard settings panel (from the input-method menu). */
    fun openKeyboardSettingsPanel() {
        val quick = panels ?: return
        if (retired) return
        closeKeyboardMenu()
        quick.openKeyboardSettings()
        callbacks.panelsChanged(true)
    }

    /** A commit reached the editor: the settings panel's repeat row may need a new preview. */
    fun onTextInserted() {
        panels?.onTextInserted()
    }

    /** Closes everything the keyboard has open: a new editor or a hidden keyboard starts clean. */
    fun closeAll() {
        keyboard.closePopup()
        popupLayer.clear()
        candidates.dismissPopup()
        panels?.close()
        closeKeyboardMenu()
        candidates.collapse()
        panelSearch = null
        syncBackOwnership()
    }

    /**
     * Closes what Back should close first, innermost first: a key or candidate popup, then a panel,
     * the menu or the expanded candidates. Returns whether anything was open.
     */
    fun closePanels(): Boolean {
        val popupClosed = keyboard.closePopup()
        val characterPopupClosed = candidates.dismissPopup()
        if (popupClosed || characterPopupClosed) {
            syncBackOwnership()
            return true
        }
        val quickClosed = panels?.navigateBack() == true || panels?.close() == true
        val menuClosed = closeKeyboardMenu()
        val candidatesClosed = candidates.collapse()
        val searchClosed = panelSearch != null
        endPanelSearch()
        syncBackOwnership()
        return quickClosed || menuClosed || candidatesClosed || searchClosed
    }

    /**
     * The process is short of memory: gives back what can be rebuilt (built page definitions, resolved
     * palettes, icon drawables, accumulated candidate pages, the emoji catalog, hidden panels' data).
     * The composition, the candidates and the open panel are untouched, and the next draw rebuilds
     * what it needs, so the keyboard looks and types the same afterwards.
     */
    fun trimMemory() {
        if (retired) return
        KeyboardPages.trim()
        KeyboardPalettes.trim()
        candidates.trimMemory()
        panels?.trimMemory()
        if (panelSearch == null) {
            emojiCatalog = null
            searchContent = null
        }
        render()
    }

    fun retire() {
        retired = true
        noticeTimer.removeCallbacks(endSwitchNotice)
        switchFade?.cancel()
        copiedChipSource?.stop()
        feedback.close()
        generation++
        session?.retire()
        operations.close()
        scope.cancel()
    }

    internal fun openKeyboardMenu() {
        if (retired || menuOpen) return
        // Finish the settings panel's visual exit before the menu replaces the same surface.
        panels?.settle()
        syncMenu()
        refreshInputMethodOptions()
        menuOpen = true
        // The menu replaces the whole key surface, so it takes that surface's exact height: a
        // shorter menu would resize the IME window and scroll the editor under the user's text.
        val rowDp = readingRow.heightDp.takeIf { readingRow.visibility == View.VISIBLE }
        val barsDp =
            rowHeightDp * PAGE_ROWS + (rowDp ?: 0f) +
                ReadingRowBudget.bandDp(KeyboardPalette.from(context).tokens.candidateBarHeightDp, rowDp)
        val fallback = (barsDp * context.resources.displayMetrics.density).toInt()
        val surface = (barRow.height + keyboard.height).takeIf { it > 0 } ?: fallback
        menu.layoutParams =
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, surface)
        menu.visibility = View.VISIBLE
        menu.revealSelection()
        keyboard.visibility = View.GONE
        barRow.visibility = View.GONE
        callbacks.panelsChanged(true)
    }

    internal fun closeKeyboardMenu(): Boolean {
        if (!menuOpen) return false
        menuOpen = false
        menu.visibility = View.GONE
        keyboard.visibility = View.VISIBLE
        barRow.visibility = if (dialPadShown) View.GONE else View.VISIBLE
        callbacks.panelsChanged(false)
        return true
    }
}
