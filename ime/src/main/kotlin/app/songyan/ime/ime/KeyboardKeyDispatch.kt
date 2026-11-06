package app.songyan.ime.ime

import android.os.Trace
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutLanguageSwitchBehavior
import app.songyan.ime.layout.LayoutPageJump
import app.songyan.ime.layout.LayoutPageTarget
import kotlinx.coroutines.launch

internal suspend fun KeyboardController.toggleInputMode(active: InputSession?) {
    if (active == null || policy.ascii || privateDirectInput) return
    val target = if (active.mode == InputMode.ENGLISH) InputMode.CHINESE else InputMode.ENGLISH
    active.setMode(target)
    active.setAutomaticShift(automaticShift)
    if (target == InputMode.ENGLISH) callbacks.automaticCapsWanted()
    symbolMarksOverride = null
    updateSwipe(active)
    resetEnglishWord()
    render()
}

internal fun KeyboardController.toggleSymbolLock() {
    symbolLock = !symbolLock
    val locked = symbolLock
    scope.launch { preferences?.setSymbolLock(locked) }
    render()
}

internal fun KeyboardController.toggleSymbolMarks() {
    symbolMarksOverride = !asciiMarksNow
    render()
}

/** Opens [target] of the running layout; null (an unknown target) stays where it is. */
internal fun KeyboardController.openPage(target: LayoutPageTarget?) {
    if (policy.numericKind == NumericEditorKind.PHONE &&
        (target == LayoutPageTarget.SYMBOLS || target == LayoutPageTarget.NUMERIC)
    ) {
        // A phone pad has two pages of its own; the key area never leaves it for the symbol page.
        phoneSymbols = target == LayoutPageTarget.SYMBOLS
        render()
        return
    }
    if (target == LayoutPageTarget.SYMBOLS && page == KeyboardPageKind.SYMBOLS) {
        // The switch on the symbol page's extra page of everyday marks turns to the usual rows.
        everydayMarksPage = false
        render()
        return
    }
    // The categorized symbol page is a panel over the key area, not a page of rows.
    if (target == LayoutPageTarget.SYMBOLS && openSymbolPage()) return
    // Every visit to the symbol page starts on its extra page, when it has one.
    everydayMarksPage = true
    page =
        when (target) {
            LayoutPageTarget.LETTERS -> null
            LayoutPageTarget.NUMERIC -> KeyboardPageKind.NUMERIC
            LayoutPageTarget.SYMBOLS -> KeyboardPageKind.SYMBOLS
            null -> page
        }
    if (page == null) symbolMarksOverride = null
    render()
}

/**
 * A jump key: the session runs the target layout for a visit (its input method follows, e.g. 9 键
 * runs its compact companion) and its page opens. The visit is not stored as the user's layout: 返回,
 * or a new editor, brings back the layout the user chose. A target the running input method cannot
 * use, or a switch the session refused, leaves the keyboard as it was.
 */
internal suspend fun KeyboardController.jumpToPage(jump: LayoutPageJump?) {
    val target = jump ?: return
    val kind = KeyboardLayoutKind.fromWire(target.layout.wire) ?: return
    if (kind !in jumpableLayouts()) return
    if (kind != layoutKind) {
        val active = session.takeIf { ready } ?: return
        active.selectLayout(kind, preferredSchema)
        syncSelection(active)
        updateSwipe(active)
        if (layoutKind != kind) return
        visitingLayout = kind != preferredLayout
        syncMenu()
    }
    openPage(target.page)
}

/**
 * Leaving the visited page (返回, or 符 on a visited 123 page): [target] opens on the user's own layout,
 * so a jump key on their symbol page and 符 on the visited page take them round in a loop. False when
 * not visiting, or when [target] is the visited layout's own page kind the user is already on.
 */
private suspend fun KeyboardController.endLayoutVisit(target: LayoutPageTarget): Boolean {
    if (!visitingLayout) return false
    if (target == LayoutPageTarget.NUMERIC && page == KeyboardPageKind.NUMERIC) return false
    val active = session.takeIf { ready } ?: return false
    applyPreferences(active)
    updateSwipe(active)
    syncMenu()
    openPage(target)
    return true
}

internal suspend fun KeyboardController.dispatch(
    action: LayoutAction,
    t9FirstKey: Boolean = false,
    pressAt: Double = kana12Now(),
) {
    ImePerf.keyDispatchStart()
    val traceCookie = System.identityHashCode(action)
    if (android.os.Build.VERSION.SDK_INT >= 29) Trace.beginAsyncSection("Songyan.keyDispatch", traceCookie)
    try {
        val active = session.takeIf { ready }
        // Every key but the kana keys themselves ends the twelve-key model's claim on the
        // composition: what happens next is not the state the model predicted.
        if (action.type != LayoutActionKind.KANA &&
            action.type != LayoutActionKind.KANA_MODIFIER
        ) {
            kana12Reset()
        }
        // Any key but a second space ends the double-space window.
        val spaceArmedAt = doubleSpaceArmedAt
        doubleSpaceArmedAt = DoubleSpacePeriod.NOT_ARMED
        when (action.type) {
            LayoutActionKind.GLOBE -> callbacks.inputMethods.switchToOther()
            LayoutActionKind.PAGE_SWITCH -> {
                val target = action.pageSwitchTarget
                if (target == null || !endLayoutVisit(target)) openPage(target)
            }
            LayoutActionKind.LAYOUT_PAGE_SWITCH -> jumpToPage(action.layoutPageJump)
            LayoutActionKind.MODE_SWITCH ->
                if (action.value == KeyboardPages.SYMBOL_PANEL_MODE) openSymbolPanel() else cycleLayout()
            LayoutActionKind.LANGUAGE_SWITCH -> {
                when (action.languageSwitchBehavior) {
                    LayoutLanguageSwitchBehavior.LETTERS ->
                        if (!endLayoutVisit(LayoutPageTarget.LETTERS)) {
                            page = null
                            render()
                        }
                    LayoutLanguageSwitchBehavior.SYMBOLS -> toggleSymbolMarks()
                    null -> if (page == null) toggleInputMode(active) else toggleSymbolMarks()
                }
            }
            LayoutActionKind.SHIFT -> {
                if (active != null) {
                    active.tapShift()
                }
                render()
            }
            LayoutActionKind.CLEAR_INPUT -> active?.discardComposition()
            LayoutActionKind.BACKSPACE ->
                if (active?.snapshot?.isComposing ==
                    true
                ) {
                    // The newest rail choice (a chosen reading, a locked spelling) is undone first.
                    if (undoRailChoice(active)) {
                        BackspaceTrace.record(BackspaceTrace.Event.RAIL_CHOICE_UNDONE, composing = true)
                    } else {
                        val before = active.snapshot
                        active.tap(KeyAction.Backspace)
                        BackspaceTrace.record(
                            if (active.snapshot !== before) {
                                BackspaceTrace.Event.ENGINE_DELETE
                            } else {
                                BackspaceTrace.Event.ENGINE_NO_CHANGE
                            },
                            composing = true,
                        )
                    }
                } else {
                    BackspaceTrace.record(BackspaceTrace.Event.EDITOR_DELETE)
                    active?.dismissPrediction()
                    callbacks.delete()
                    noteBackspaceForEnglishWord()
                }
            LayoutActionKind.ENTER ->
                if (active?.snapshot?.isComposing ==
                    true
                ) {
                    active.tap(KeyAction.Enter)
                } else {
                    callbacks.action()
                }
            LayoutActionKind.SPACE ->
                if (active != null) {
                    typeSpace(active, spaceArmedAt)
                } else {
                    callbacks.commit(" ", false)
                }
            LayoutActionKind.CODE -> {
                val text = action.value ?: return
                if (active != null) {
                    // A lock in force is restored before the next code, or the key would land after
                    // an alias; a restore that fails drops the key rather than mix the two.
                    if (!restoreLockBeforeInput(active)) return
                    active.tap(KeyAction.Code(text))
                } else if (policy.ascii) {
                    callbacks.commit(text, selectingCandidate)
                }
            }
            LayoutActionKind.LITERAL -> {
                val text = action.literalValue(asciiMarksNow) ?: return
                if (t9FirstKey &&
                    layoutKind == KeyboardLayoutKind.PHONE9 &&
                    t9FirstKeyCycles &&
                    page == null &&
                    active?.snapshot?.isComposing != true
                ) {
                    handleT9FirstKey()
                    return
                }
                if (active != null) {
                    active.tap(KeyAction.Literal(text))
                } else {
                    callbacks.commit(text, selectingCandidate)
                }
                // The row-shaped 123 and symbol pages keep their place, as Gboard's do: marks and
                // digits come in runs. Only the categorised symbol panel returns after one symbol
                // unless locked.
            }
            LayoutActionKind.SYMBOL_LOCK -> toggleSymbolLock()
            // The twelve-key kana actions resolve one row's kana into the engine codes it spells;
            // the cursor key moves the engine's own composition caret without confirming anything.
            LayoutActionKind.KANA -> {
                val rowID = action.value
                if (rowID != null) kana12Row(rowID, pressAt) else kana12Reset()
            }
            LayoutActionKind.KANA_MODIFIER -> kana12Modifier(pressAt)
            LayoutActionKind.CURSOR_LEFT -> kana12CursorLeft()
        }
    } finally {
        if (android.os.Build.VERSION.SDK_INT >=
            29
        ) {
            Trace.endAsyncSection("Songyan.keyDispatch", traceCookie)
        }
        ImePerf.keyDispatched()
    }
    session.takeIf { ready }?.let { enforceLostLearningProtection(it) }
}
