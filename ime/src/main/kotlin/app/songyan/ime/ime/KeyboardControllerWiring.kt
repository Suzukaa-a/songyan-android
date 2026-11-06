package app.songyan.ime.ime

import android.media.AudioManager
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The only way into the band row: a view must be a [BarContent], which places its content by the
 * shared rule ([BarContentBox]), so no kind of band content can skip it.
 */
internal fun <V> KeyboardController.addBandContent(view: V) where V : View, V : BarContent {
    bandRow.addView(view, FrameLayout.LayoutParams(-1, -2))
}

/** Connects the views, the panels and the candidate bar to the controller; runs once from its init. */
internal fun KeyboardController.wireViews() {
    rowHeightDp = KeyboardPalette.from(context).tokens.rowHeightDp
    recomputeReadingRow()
    addBandContent(toolbar)
    addBandContent(inlineSuggestions)
    addBandContent(candidates)
    inlineSuggestions.visibility = View.GONE
    inlineSuggestions.onChanged = { syncBand() }
    copiedChipSource?.onChanged = { syncBand() }
    toolbar.onChipPaste = { chip ->
        copiedChipSource?.used()
        insertText(chip.text)
        syncBand()
    }
    toolbar.onChipDismiss = {
        copiedChipSource?.dismiss()
        syncBand()
    }
    readingRow.visibility = View.GONE
    barRow.addView(readingRow, LinearLayout.LayoutParams(-1, -2))
    barRow.addView(bandRow, LinearLayout.LayoutParams(-1, -2))
    candidates.visibility = View.GONE
    content.addView(barRow, LinearLayout.LayoutParams(-1, -2))
    content.addView(keyboard, LinearLayout.LayoutParams(-1, -2))
    content.addView(menu, LinearLayout.LayoutParams(-1, -2))
    shell.onSwapSide = {
        val other =
            if (oneHandMode ==
                KeyboardOneHandMode.LEFT
            ) {
                KeyboardOneHandMode.RIGHT
            } else {
                KeyboardOneHandMode.LEFT
            }
        scope.launch { preferences?.setOneHand(other.wire) }
    }
    shell.onRestore = { scope.launch { preferences?.setOneHand(KeyboardOneHandMode.OFF.wire) } }
    shell.onWidthChanged = { syncToolbar() }
    keyboard.onAction = { action ->
        candidates.dismissPopup()
        enqueueKey(action)
    }
    keyboard.onT9FirstKey = { action ->
        candidates.dismissPopup()
        enqueueKey(action, t9FirstKey = true)
    }
    // A held key repeats through the queue, so each step is checked again when it runs: one queued
    // for a hold that has since ended types nothing.
    keyboard.onRepeatAction = { action, stillHeld ->
        enqueueRepeat(stillHeld) { dispatch(action) }
    }
    keyboard.onPopupChanged = { syncBackOwnership() }
    candidates.onPopupChanged = { syncBackOwnership() }
    keyboard.onDoubleShift = {
        enqueue {
            val active = session.takeIf { ready }
            if (active != null) {
                active.doubleTapShift()
            }
            render()
        }
    }
    keyboard.onCursor = { delta ->
        // Cursor mode: the session confirms the composition through the engine before moving the
        // caret, so holding space never drops what the user typed, and never strands a hidden
        // spelling in the editor.
        enqueue {
            val active = session.takeIf { ready }
            if (active != null) active.moveCursor(delta) else callbacks.moveCursor(delta)
            resetEnglishWord()
        }
    }
    keyboard.onLanguageMenu = { openKeyboardMenu() }
    keyboard.onPickInputMethod = { callbacks.inputMethods.pick() }
    keyboard.onSymbolPanel = { openSymbolPanel() }
    readingRow.onChoice = { index, label -> enqueue { applyRailChoice(index - railScroll, label) } }
    candidates.onReading = { index, label -> enqueue { applyRailChoice(index - railScroll, label) } }
    keyboard.onRailChoice = { position, label -> enqueue { applyRailChoice(position, label) } }
    keyboard.onRailScroll = { steps -> scrollRail(steps) }
    keyboard.popupLayer = popupLayer
    keyboard.composing = { session?.snapshot?.isComposing == true }
    keyboard.onReleaseFeedback = { feedback.release(keyboard) }
    candidates.onPressFeedback = { feedback.press(candidates) }
    readingRow.onPressFeedback = { feedback.press(readingRow) }
    toolbar.onPressFeedback = { feedback.press(toolbar) }
    keyboard.onFeedback = { action, kind ->
        val sound =
            when (action.type) {
                LayoutActionKind.SPACE -> AudioManager.FX_KEYPRESS_SPACEBAR
                LayoutActionKind.BACKSPACE -> AudioManager.FX_KEYPRESS_DELETE
                LayoutActionKind.ENTER -> AudioManager.FX_KEYPRESS_RETURN
                else -> AudioManager.FX_KEYPRESS_STANDARD
            }
        when (kind) {
            FeedbackKind.PRESS -> feedback.press(keyboard, sound)
            FeedbackKind.REPEAT -> feedback.repeat(keyboard, sound)
            FeedbackKind.HOLD -> feedback.hold(keyboard)
            FeedbackKind.TICK -> feedback.tick(keyboard)
        }
    }
    toolbar.onAction = { action ->
        when (action) {
            ToolbarView.Action.CLIPBOARD -> openClipboardPanel()
            ToolbarView.Action.INPUT_METHODS -> openKeyboardMenu()
            ToolbarView.Action.ONE_HAND -> toggleOneHand()
            ToolbarView.Action.EMOJI -> openEmojiPanel()
            ToolbarView.Action.EDIT -> openEditPanel()
            ToolbarView.Action.SETTINGS -> openKeyboardSettingsPanel()
            ToolbarView.Action.HIDE -> callbacks.hideKeyboard()
            ToolbarView.Action.SWITCH_INPUT_METHOD -> callbacks.inputMethods.switchToOther()
            ToolbarView.Action.VOICE_INPUT -> callbacks.inputMethods.startVoice()
        }
    }
    toolbar.onNoticeItem = { item ->
        when (item) {
            ToolbarView.NoticeItem.RETRY -> callbacks.engineNoticeAction(EngineNoticeAction.RETRY)
            ToolbarView.NoticeItem.SETTINGS -> callbacks.engineNoticeAction(EngineNoticeAction.SETTINGS)
            ToolbarView.NoticeItem.SWITCH_INPUT_METHOD -> callbacks.inputMethods.switchToOther()
        }
    }
    candidates.expandedHeight = {
        val fallback = (keyAreaHeightDp * context.resources.displayMetrics.density).toInt()
        bandRow.height + (keyboard.height.takeIf { it > 0 } ?: fallback)
    }
    candidates.onBackspace = {
        BackspaceTrace.record(BackspaceTrace.Event.CANDIDATE_BAR_DELETE, composing = true)
        enqueueKey(LayoutAction(LayoutActionKind.BACKSPACE))
    }
    // The expanded grid's held Delete repeats like a held key: the view's hold check (which also
    // stops at the end of the composition it started in) runs again when each step does.
    candidates.onRepeatBackspace = { stillHeld ->
        BackspaceTrace.record(BackspaceTrace.Event.CANDIDATE_BAR_DELETE, composing = true)
        enqueueRepeat(stillHeld) { dispatch(LayoutAction(LayoutActionKind.BACKSPACE)) }
    }
    candidates.onRetype = { enqueueKey(LayoutAction(LayoutActionKind.CLEAR_INPUT)) }
    candidates.onLoadMore = { revision, page ->
        val compositionRevision = session?.compositionRevision
        enqueue {
            val active = session.takeIf { ready }
            if (active == null ||
                revision != candidates.revision ||
                active.compositionRevision != compositionRevision
            ) {
                candidates.loadFailed(revision)
                return@enqueue
            }
            try {
                candidates.appendPage(revision, page, active.readCandidatePage(page))
            } catch (error: CancellationException) {
                candidates.abandonLoad()
                throw error
            } catch (error: Exception) {
                candidates.loadFailed(revision)
            }
        }
    }
    candidates.onDeleteWord = { request ->
        val compositionRevision = session?.compositionRevision
        enqueue {
            val active = session.takeIf { ready }
            if (active == null) return@enqueue
            // The list moved on since the long press: deleting by position would hit another word.
            if (request.revision != candidates.revision || active.compositionRevision != compositionRevision) {
                return@enqueue
            }
            val gone = active.deleteCandidate(request.absoluteIndex, request.text)
            // Same code, same first page, but later pages shifted: the strip must not keep old indices.
            candidates.listChanged()
            candidates.flash(
                context.getString(
                    if (gone) R.string.ime_candidate_delete_word_done else R.string.ime_candidate_delete_word_kept,
                    request.text,
                ),
            )
        }
    }
    candidates.onCandidate = { choice ->
        val revision = session?.compositionRevision
        val suggested = candidates.suggestionRow
        enqueue {
            if (suggested) {
                if (panelSearch !=
                    null
                ) {
                    selectPanelSearchResult(choice.index, choice.text)
                } else {
                    selectEnglishSuggestion(choice.text)
                }
                ImePerf.commitDone()
                return@enqueue
            }
            val active = session
            if (active == null) {
                ImePerf.commitDone()
                return@enqueue
            }
            val sameList = choice.revision == candidates.revision
            val shownIsCurrent = sameList && active.compositionRevision == revision
            // A queued key the engine handled by itself (a page key, a moved highlight) leaves the
            // list as it was: a candidate of a paged list is still addressed by its absolute index,
            // whatever page the engine is on now.
            val sameListAbsolute = !shownIsCurrent && sameList && choice.absoluteIndex >= 0
            val target: Int? =
                if (shownIsCurrent) {
                    choice.index
                } else {
                    val snap = active.snapshot
                    if (!snap.isComposing && !snap.isPrediction) {
                        null
                    } else if (sameListAbsolute) {
                        choice.absoluteIndex
                    } else {
                        // A queued key already moved the engine on: the cell now holds another word, so
                        // the tapped word is looked up again on the engine's page, or the tap is dropped.
                        CandidateRelease.relocate(choice.text, choice.comment, snap.candidates)?.index
                    }
                }
            if (target != null && !(policy.suppressesPrediction && active.snapshot.isPrediction)) {
                selectingCandidate = true
                try {
                    ImePerf.commitRunStart()
                    if ((shownIsCurrent && choice.absoluteIndex >= 0) || sameListAbsolute) {
                        active.selectCandidateAbsolute(choice.absoluteIndex)
                    } else if (shownIsCurrent && candidatesFiltered) {
                        // A filtered list is numbered by absolute index, not by engine page.
                        active.selectCandidateAbsolute(target)
                    } else {
                        active.selectCandidate(target)
                    }
                } finally {
                    selectingCandidate = false
                }
            }
            ImePerf.commitDone()
        }
    }
    candidates.onCandidateCharacter = { pick ->
        val revision = session?.compositionRevision
        enqueue {
            val active = session.takeIf { ready }
            if (active != null &&
                (pick.revision != candidates.revision || active.compositionRevision != revision)
            ) {
                // Newer queued input already moved the composition on: taking a character of the
                // old strip would overwrite it, so the pick is dropped.
                return@enqueue
            }
            // Taking one character of a candidate replaces the composition rather than appending
            // to it. The commit lands first, while the editor still has the marked region, so
            // commitText replaces that region; clearing the engine composition first would make
            // finishComposingText publish the half-typed spelling as real text.
            callbacks.commit(pick.text, false)
            active?.discardComposition()
            render()
        }
    }
    candidates.onExpanded = {
        keyboard.visibility = if (it) View.GONE else View.VISIBLE
        syncBackOwnership()
    }
    menu.onSchema = { id -> enqueue { chooseSchema(id) } }
    menu.onLayout = { kind -> enqueue { chooseLayout(kind) } }
    menu.onQuickContent = { openQuickContentPanel() }
    menu.onKeyboardSettings = { openKeyboardSettingsPanel() }
    menu.onEmoji = { openEmojiPanel() }
    menu.onEdit = { openEditPanel() }
    menu.onOneHand = {
        closeKeyboardMenu()
        toggleOneHand()
    }
    menu.onClose = { closeKeyboardMenu() }
    menu.onSwitchInputMethod = {
        closeKeyboardMenu()
        callbacks.inputMethods.switchToOther()
    }
    menu.onPickInputMethod = {
        closeKeyboardMenu()
        callbacks.inputMethods.pick()
    }
    menu.onVoiceInput = {
        closeKeyboardMenu()
        callbacks.inputMethods.startVoice()
    }
    observePreferences()
    observeLayoutCatalog()
}
