package app.songyan.ime.ime

import android.view.View
import android.view.inputmethod.EditorInfo
import app.songyan.ime.keyboard.display.CompositionText
import app.songyan.ime.keyboard.display.ReadingInputCoverage
import app.songyan.ime.keyboard.display.SentenceSpelling
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.keyboard.support.ShiftState
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.CompactSpellingLayout
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput

internal fun KeyboardController.render() {
    val active = session.takeIf { ready }
    val snapshot = active?.snapshot ?: EngineSnapshot()
    if (active != null && englishSuggestionsActive(active) && englishProbeSource != active.selectedSchemaID) {
        probeEnglishSuggestions(active)
    }
    val suggestions = panelSearchRow(snapshot) ?: englishSuggestionRow(active, snapshot)
    candidates.suggestionRow = suggestions != null && !suggestions.fromEngine
    val visible =
        when {
            suggestions != null -> suggestions.snapshot
            policy.suppressesPrediction && snapshot.isPrediction -> snapshot.copy(candidates = emptyList())
            else -> snapshot
        }
    val marked = compositionText(active, snapshot)
    lastMarked = marked
    // A chosen reading narrows what the bar lists to the real candidates that read that way.
    candidatesFiltered = readingFilterShowing(active)
    // Only the engine's own menu is read further page by page; suggestions and a reading filter's
    // matches are shown as they are.
    candidates.update(
        if (candidatesFiltered) filteredSnapshot(visible) else visible,
        browsable = suggestions == null && !candidatesFiltered,
    )
    // Idle, the band shows the toolbar; a composition (or a notice) takes it back for candidates.
    val idle = !visible.isComposing && visible.candidates.isEmpty() && candidates.status == null
    bandIdle = idle
    // A composition ends the suggestions of the field: the system sends new ones when they apply.
    if (!idle) inlineSuggestions.clear()
    syncBand()
    if (dispatchGeneration == generation &&
        active != null
    ) {
        callbacks.preedit(if (snapshot.isComposing) marked else "")
    }
    val english = englishNow()
    val currentPage = if (policy.numeric) KeyboardPageKind.NUMERIC else page
    val marksAscii = asciiMarksNow
    val catalog = KeyboardLayoutCatalog.snapshot.value
    val config =
        KeyboardPages.Config(
            catalogRevision = catalog.revision,
            pairing = catalog.registry.composition(compositionKey()),
            layout = layoutKind,
            englishDirectInput = english,
            symbolMarksAscii = marksAscii,
            languageSwitch = !policy.ascii && !privateDirectInput,
            lettersReachable = !policy.numeric,
            field = policy.fieldKind,
            showsGlobe = showsSwitchKey,
            jumpLayouts = jumpableLayouts(),
            everydayMarksPage = everydayMarksPage,
        )
    val faces =
        KeyboardFaces(
            english = english,
            page = currentPage,
            symbolMarksAscii = marksAscii,
            phone = layoutKind == KeyboardLayoutKind.PHONE9,
            symbolLocked = symbolLock,
        )
    val shiftState = active?.shiftState
    val shiftActive = shiftState != null && shiftState != ShiftState.OFF
    val locked = shiftState == ShiftState.LOCKED
    // A number, phone or date editor types on its own dial pad: the generic ten-column symbol page
    // makes the digits the user actually needs the hardest keys to hit. Nothing is composed there,
    // so the candidate bar gives its row back.
    val dialPad = EditorDigitLayouts.pad(policy.numericKind, policy.numberFlags, policy.dateTimeFlags, phoneSymbols)
    if ((dialPad != null) != dialPadShown) {
        dialPadShown = dialPad != null
        if (!menuOpen) candidates.visibility = if (dialPadShown) View.GONE else View.VISIBLE
    }
    updateReadingSurface(active, dialPad != null)
    if (dialPad != null) {
        clearRail()
        keyboard.extraRows = 0
        keyboard.syllableHints = emptyMap()
        keyboard.updateDial(dialPad, enterLabel(), faces)
        return
    }
    // A 123 page the user authored replaces the shell's digit pad, as it does on every other layout.
    val phonePage =
        currentPage == null ||
            (currentPage == KeyboardPageKind.NUMERIC && !KeyboardPages.pairsAuthoredNumericPage(config))
    // A nine-key layout the shell cannot draw (an author broke its telephone structure) is drawn as
    // rows with the generated bottom row, like any other layout; the digit pad does not read it.
    val phoneDefinition = if (layoutKind == KeyboardLayoutKind.PHONE9) mainDefinition() else null
    // The unchanged keypad preset is the shell's digit pad on every layout and in either language.
    val keypadPage = currentPage == KeyboardPageKind.NUMERIC && KeyboardPages.pairsKeypadPreset(config)
    val phoneShell =
        keypadPage ||
            (
                phoneDefinition != null &&
                    !english &&
                    phonePage &&
                    (currentPage != null || PhoneKeypadLayout.draws(phoneDefinition))
            )
    t9FirstKeyCycles =
        phoneShell &&
        currentPage == null &&
        phoneDefinition != null &&
        PhoneKeypadLayout.cyclesFirstKey(phoneDefinition)
    if (phoneShell) {
        val names = keyNames.chinese()
        val railWindow =
            if (currentPage == null && snapshot.isComposing) {
                T9RailPolicy.window(railItems(), railScroll)
            } else {
                emptyList()
            }
        // A password field's pages share its taller key area; the shell stretches into it.
        keyboard.extraRows = if (policy.fieldKind == FieldKind.PASSWORD) 1 else 0
        keyboard.syllableHints = emptyMap()
        keyboard.updatePhone(
            config =
                PhoneKeypadLayout.Config(
                    width = 0f,
                    height = keyAreaHeightDp,
                    numeric = currentPage == KeyboardPageKind.NUMERIC,
                    composing = snapshot.isComposing,
                    asciiPunctuation = marksAscii,
                    savedQuickSymbols = t9QuickSymbols,
                    languageSwitch = !policy.ascii && !privateDirectInput,
                    lettersReachable = !policy.numeric,
                    definition = phoneDefinition ?: PhoneKeypadLayout.BUNDLED,
                    showsGlobe = showsSwitchKey,
                    labels =
                        PhoneKeypadLayout.Labels(
                            page = context.getString(R.string.ime_numbers),
                            language = "中/英",
                            space = names.getString(R.string.ime_space),
                            symbols = names.getString(R.string.ime_symbols),
                            back =
                                if (english) {
                                    context.getString(R.string.ime_back)
                                } else {
                                    context.getString(R.string.ime_back_chinese)
                                },
                            zero = "0",
                            enter = enterLabel(),
                            clear = names.getString(R.string.ime_clear),
                            delete = context.getString(R.string.ime_glyph_delete),
                        ),
                ),
            shift = shiftActive,
            enterLabel = enterLabel(),
            locked = locked,
            faces = faces,
            railChoices = railWindow.map { it.label },
            railSelected = railWindow.count { it.selected },
        )
        if (phoneDefinition != null) scheduleReadingRail(active, snapshot) else clearRail()
        return
    }
    if (layoutKind.showsReadings && !english && currentPage == null) {
        scheduleReadingRail(active, snapshot)
    } else {
        clearRail()
    }
    keyboard.extraRows = if (policy.fieldKind == FieldKind.PASSWORD) 1 else 0
    keyboard.syllableHints =
        if (currentPage == null && active?.mode == InputMode.CHINESE) {
            KeyboardPages.syllableHints(config, shuangpinHints)
        } else {
            emptyMap()
        }
    keyboard.update(
        definition = KeyboardPages.definition(currentPage, config),
        rails = KeyboardPages.rails(currentPage, config),
        shift = shiftActive,
        enterLabel = enterLabel(),
        locked = locked,
        faces = faces,
        swipe = mergedSwipe.takeUnless { english || currentPage != null },
    )
}

/**
 * What the field and the candidate bar show for the composition: a compact layout's key code is
 * spelled out (see [CompositionText]); a 26-key preedit is shown as the engine wrote it.
 */
internal fun KeyboardController.compositionText(
    active: InputSession?,
    snapshot: EngineSnapshot,
): String {
    if (!snapshot.isComposing) return ""
    val compact = active?.compactSpellingLayout == true && active.mode == InputMode.CHINESE
    // Every key of a 26-key layout is one letter of the input, so the engine's preedit is exactly
    // what was typed (double pinyin included: `ni'hc`, not the reading `ni hao`). Showing anything
    // else breaks the one-press-one-change link Delete and the user's own apostrophes rely on.
    if (!compact) return snapshot.preedit
    val keyMap =
        readingKeyMaps.getOrPut(
            layoutKind,
        ) { CompactSpellingLayout.readingKeyMap(mainDefinition()) }
    val exact = exactTokenLetters()
    filteredSpelling(active, snapshot, keyMap, exact)?.let { return it }
    return CompositionText.marked(
        snapshot,
        compactLayout = true,
        readingKeyMap = keyMap,
        exactLetters = exact,
        readings = pageReadings,
        knownReadings = knownReadings,
    )
}

/**
 * The field follows the reading filter's first match, because that is what space commits: its
 * reading, trimmed to what was typed. Only a preedit whose highlight reaches the end is replaced; a
 * Chinese preview or a mid-way highlight keeps the engine's text.
 */
private fun KeyboardController.filteredSpelling(
    active: InputSession?,
    snapshot: EngineSnapshot,
    keyMap: Map<Char, Char>,
    exact: Map<Char, Char>,
): String? {
    if (!readingFilterShowing(active)) return null
    // The engine's own display value spells its highlight, which is not the candidate that space
    // commits while a reading is chosen, so it is replaced like a plain pinyin preedit.
    if (snapshot.preedit.any { it.code >= 0x80 }) return null
    if (snapshot.preeditSelEnd < snapshot.preedit.toByteArray(Charsets.UTF_8).size) return null
    val path =
        readingFilter.matches
            .firstOrNull()
            ?.readingPaths
            ?.firstOrNull(readingFilter::startsWithPrefix) ?: return null
    val joiner = if (snapshot.hidesNumericTransportCode) SentenceSpelling.SEPARATOR else " "
    return ReadingInputCoverage.of(snapshot.rawInput, keyMap, exact)?.typedText(path, joiner)
}

/** The candidate bar's view while a reading is chosen: the matches, numbered by their absolute index. */
internal fun KeyboardController.filteredSnapshot(base: EngineSnapshot): EngineSnapshot {
    val matches = readingFilter.matches
    return base.copy(
        candidates = matches.map { Candidate(it.index, it.text, it.comment, it.selectKey, it.selectLabel) },
        highlightedIndex = matches.firstOrNull()?.index ?: -1,
        pageIndex = 0,
        pageSize = 0,
        isLastPage = true,
    )
}

/** The pairing key of the running keyboard: the input method and the physical shape. */
internal fun KeyboardController.compositionKey(): KeyboardCompositionKey =
    KeyboardCompositionKey(schemaId, LayoutInput.Builtin.fromWire(layoutKind.wire) ?: LayoutInput.Builtin.QWERTY26)

/** The main layout last resolved, with what it was resolved for; the renderer asks after every key. */
internal class MainDefinitionMemo(
    val revision: Long,
    val kind: KeyboardLayoutKind,
    val schemaId: String,
    val definition: LayoutDefinition,
)

/** The main layout the keyboard draws: the pairing's choice, or the bundled preset of this shape. */
internal fun KeyboardController.mainDefinition(): LayoutDefinition {
    val snapshot = KeyboardLayoutCatalog.snapshot.value
    mainDefinitionMemo
        ?.takeIf { it.revision == snapshot.revision && it.kind == layoutKind && it.schemaId == schemaId }
        ?.let { return it.definition }
    val registry = snapshot.registry
    val resolved =
        registry.resolvedLayout(registry.composition(compositionKey()).mainLayoutID)
            ?: builtinDefinition(layoutKind)
    mainDefinitionMemo = MainDefinitionMemo(snapshot.revision, layoutKind, schemaId, resolved)
    return resolved
}

internal fun KeyboardController.builtinDefinition(kind: KeyboardLayoutKind) =
    when (kind) {
        KeyboardLayoutKind.QWERTY26 -> BuiltinLayouts.qwerty26
        KeyboardLayoutKind.MERGED14 -> BuiltinLayouts.merged14
        KeyboardLayoutKind.MERGED18 -> BuiltinLayouts.merged18
        KeyboardLayoutKind.PHONE9 -> BuiltinLayouts.phone9
    }

/** The editor's own action label wins: it is the string the editor will act on. */
internal fun KeyboardController.enterLabel(): String =
    policy.customActionLabel ?: keyNames.chinese().getString(enterKeyLabelRes(policy.action))

/** Whether the keyboard is in English mode. */
internal fun KeyboardController.englishNow(): Boolean =
    policy.ascii || privateDirectInput || session.takeIf { ready }?.mode == InputMode.ENGLISH

/**
 * The localized face of the Enter key for an [EditorInfo.IME_MASK_ACTION] action.
 *
 * An editor that labeled the key itself ([EditorPolicy.customActionLabel]) has no resource here: the
 * controller paints the editor's own string instead. Everything that is not a platform action code,
 * including [EditorPolicy.ACTION_CUSTOM_LABEL] and [EditorInfo.IME_ACTION_UNSPECIFIED], keeps the
 * Return face, which is what the key does in those cases.
 */
internal fun enterKeyLabelRes(action: Int): Int =
    when (action) {
        EditorInfo.IME_ACTION_GO -> R.string.ime_go
        EditorInfo.IME_ACTION_SEARCH -> R.string.ime_search
        EditorInfo.IME_ACTION_SEND -> R.string.ime_send
        EditorInfo.IME_ACTION_NEXT -> R.string.ime_next
        EditorInfo.IME_ACTION_DONE -> R.string.ime_done
        EditorInfo.IME_ACTION_PREVIOUS -> R.string.ime_previous
        else -> R.string.ime_enter
    }

/**
 * Chooses what the top band shows (see [BandContent]) and applies it. Also called when the chip or the
 * inline suggestions come or go, without a full render.
 */
internal fun KeyboardController.syncBand() {
    val chip = if (bandIdle) copiedChipSource?.current() else null
    val content = BandContent.of(bandIdle, inlineSuggestions.hasSuggestions, chip != null)
    toolbar.chip = if (content == BandContent.CHIP) chip else null
    toolbar.visibility =
        if (content == BandContent.TOOLBAR || content == BandContent.CHIP) View.VISIBLE else View.GONE
    inlineSuggestions.visibility = if (content == BandContent.INLINE_SUGGESTIONS) View.VISIBLE else View.GONE
    candidates.visibility = if (content == BandContent.CANDIDATES) View.VISIBLE else View.GONE
}

/**
 * Puts the engine notice and the input method entries where they live: the toolbar row (the notice
 * takes it whole, otherwise the switcher joins the shortcuts when the system asked for one) and the
 * keyboard menu. Idempotent, so it is called after every change of what it depends on.
 */
internal fun KeyboardController.syncToolbar() {
    val options = inputMethodOptions
    keyboard.notice =
        engineNotice?.let {
            context.getString(
                if (it.kind ==
                    EngineNoticeKind.FAILED
                ) {
                    R.string.ime_engine_failed_hint
                } else {
                    R.string.ime_engine_preparing_hint
                },
            )
        }
    toolbar.notice =
        engineNotice?.let { notice ->
            ToolbarView.Notice(
                text =
                    context.getString(
                        if (notice.kind ==
                            EngineNoticeKind.FAILED
                        ) {
                            R.string.ime_engine_failed
                        } else {
                            R.string.ime_engine_preparing
                        },
                    ),
                items =
                    buildList {
                        for (action in notice.actions) {
                            add(
                                if (action ==
                                    EngineNoticeAction.RETRY
                                ) {
                                    ToolbarView.NoticeItem.RETRY
                                } else {
                                    ToolbarView.NoticeItem.SETTINGS
                                },
                            )
                        }
                        if (options.canSwitch) add(ToolbarView.NoticeItem.SWITCH_INPUT_METHOD)
                    },
            )
        }
    // One-handed mode only exists on a phone-width portrait window; elsewhere the button would do nothing.
    val oneHandAvailable = shell.isAvailable()
    toolbar.actions =
        if (hideIdleToolbar) {
            emptyList()
        } else if (customSwitcherRequested && options.canSwitch) {
            ToolbarView.DEFAULT_ACTIONS.flatMap { action ->
                if (action ==
                    ToolbarView.Action.HIDE
                ) {
                    listOf(ToolbarView.Action.SWITCH_INPUT_METHOD, action)
                } else {
                    listOf(action)
                }
            }
        } else {
            ToolbarView.DEFAULT_ACTIONS
        }.filter { it != ToolbarView.Action.ONE_HAND || oneHandAvailable }
    menu.setInputMethodOptions(options)
    menu.setToolbarShortcuts(shown = hideIdleToolbar, oneHandAvailable = oneHandAvailable, oneHand = oneHandMode)
}
