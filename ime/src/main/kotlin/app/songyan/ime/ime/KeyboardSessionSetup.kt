package app.songyan.ime.ime

import android.animation.ValueAnimator
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.keyboard.session.InputSession

internal suspend fun KeyboardController.prepareSession(keepComposition: Boolean = false) {
    val backend = pendingBackend ?: return
    if (session?.engine !== backend) {
        session?.retire()
        val created = InputSession(backend, layoutKind, resourceSet(backend))
        session = created
        created.onDispatchCommit =
            { text ->
                val delivered =
                    dispatchGeneration == generation &&
                        !retired &&
                        !preparing &&
                        callbacks.commit(text, selectingCandidate)
                if (delivered) {
                    deliveredCommits++
                    noteCommitForEnglishWord(text)
                }
                delivered
            }
        created.onUpdate = { if (dispatchGeneration == generation && !retired && !preparing) render() }
        created.onMoveCursor = { if (dispatchGeneration == generation && !retired) callbacks.moveCursor(it) }
        created.onError = { callbacks.error(it) }
        created.confirmationTarget = { confirmationTargetOf(created) }
    }
    val active = session ?: return
    preparing = true
    try {
        readPreferences()
        active.refresh()
        if (!keepComposition || !active.snapshot.isComposing) {
            check(active.discardComposition()) { "Engine composition could not be cleared" }
        }
        applyEditorMode(active)
        active.setAutomaticShift(automaticShift)
        applyPreferences(active)
        // Also applies the saved scheme switches, so a change made in the settings app reaches this editor.
        active.reloadOptions()
        active.refresh()
        if (dispatchGeneration != generation) return
        ready = true
        candidates.status = null
    } finally {
        preparing = false
    }
    updateSwipe(active)
    syncMenu()
    // The new package may arrive while the keyboard is closed; it is named when the keys show.
    if (announceWhenReady && windowShown) finishSwitch()
    render()
    ImePerf.ready()
    while (heldKeys.isNotEmpty() && ready) {
        heldKeys.removeFirst()()
    }
}

/**
 * Applies the editor's privacy and mode decision to the current session.
 *
 * Re-run whenever the session is prepared or the editor policy changes, because the protection
 * result is what decides direct input: a transient engine failure must not leave the keyboard
 * in a mode the current editor never asked for. Only a truly private editor may lose Chinese
 * input; a no-suggestions editor (search fields) suppresses learning and prediction but keeps
 * composing.
 */
internal suspend fun KeyboardController.applyEditorMode(active: InputSession) {
    val protection = active.setLearningEnabled(!policy.suppressesLearning)
    privateDirectInput = policy.directInputRequired(protection == InputSession.LearningProtection.ENFORCED)
    active.setMode(policy.modeFor(privateDirectInput))
}

/**
 * The engine could not keep a commit in this private editor out of its learning data: stop
 * composing here, as if protection had never been available.
 */
internal suspend fun KeyboardController.enforceLostLearningProtection(active: InputSession) {
    if (!active.learningProtectionLost || !policy.privateInput || privateDirectInput) return
    privateDirectInput = true
    active.setMode(InputMode.ENGLISH)
    render()
}

/** Re-derives the mode of an already ready session after its editor policy changed. */
internal suspend fun KeyboardController.reapplyEditorMode() {
    val active = session ?: return
    // Not ready = a session preparation is queued after this action and applies the policy then.
    if (!ready) return
    applyEditorMode(active)
    updateSwipe(active)
    render()
}

/**
 * The engine schema list decides whether combination selection can be resolved at all; without
 * it the built-in template pairing still maps a layout to its schema. Source schemes the engine
 * can load beyond the bundled ones (an imported scheme package) join the same map, otherwise the
 * gate before selectSchema would refuse a schema the engine is already able to run.
 */
internal fun KeyboardController.resourceSet(backend: EngineBackend): KeyboardResourceSet? =
    KeyboardResourceSets.forEngineSchemas(backend.availableSchemas, backend.encodingAdapters)

internal suspend fun KeyboardController.chooseLayout(kind: KeyboardLayoutKind) {
    val before = layoutKind
    val active = session
    val accepted =
        if (active != null && ready) {
            active.selectLayout(kind, preferredSchema)
            syncSelection(active)
            updateSwipe(active)
            active.layout == kind && active.selectedSchemaID == preferredSchema
        } else {
            layoutKind = kind
            true
        }
    // The next startup must use an accepted pairing, even when the menu offered an unsupported one.
    if (accepted) {
        preferredLayout = kind
        visitingLayout = false
        page = null
        preferences?.setLayoutId(kind.wire)
    }
    syncMenu()
    closeKeyboardMenu()
    if (accepted && kind != before) announceSwitch(named = false)
    render()
}

/**
 * Makes [boardId] the main layout of the running input method's identity shape and moves the keyboard
 * to that shape. Only the pairing and the layout change: the engine keeps its schema, so nothing is
 * redeployed. The pairing is saved first; the catalog it publishes redraws the board, and a refused
 * save leaves the keyboard where it was.
 */
internal suspend fun KeyboardController.chooseBoard(boardId: String) {
    val saved = preferences?.setMainLayout(schemaId, layoutFamilies(), boardId) ?: false
    if (!saved) {
        closeKeyboardMenu()
        return
    }
    chooseLayout(KeyboardLayoutKind.QWERTY26)
    announceSwitch(named = false)
    render()
}

/**
 * The layouts a jump key may open now; see [JumpTargets.usable]. The renderer asks after every key, so
 * the answer is kept until the layout, the input method or the resource set changes.
 */
internal fun KeyboardController.jumpableLayouts(): Set<KeyboardLayoutKind> {
    val set = session?.takeIf { ready }?.resourceSet
    jumpTargetsMemo
        ?.takeIf { it.current == layoutKind && it.set === set && it.schemaId == preferredSchema }
        ?.let { return it.layouts }
    val layouts = JumpTargets.usable(layoutKind, set, preferredSchema)
    jumpTargetsMemo = JumpTargets.Memo(layoutKind, set, preferredSchema, layouts)
    return layouts
}

internal suspend fun KeyboardController.chooseSchema(target: String) {
    if (target.isBlank()) return
    val before = schemaId
    if (target.startsWith(OtherInputMethod.MENU_ID_PREFIX)) {
        otherInputMethods.firstOrNull { it.menuId == target }?.let { switchToOtherPackage(it) }
        return
    }
    val active = session
    val accepted =
        if (active != null && ready) {
            active.selectLayout(layoutKind, target)
            syncSelection(active)
            updateSwipe(active)
            active.selectedSchemaID == target
        } else {
            schemaId = target
            true
        }
    if (accepted) {
        preferredSchema = target
        page = null
        preferences?.setActiveSchemaId(target)
        // A family that cannot run the saved layout (zhuyin, cangjie, Japanese) replaced it; save
        // that so the next start does not begin from a pairing it must repair again.
        if (active != null && ready) persistSelection(active)
    }
    syncMenu()
    closeKeyboardMenu()
    if (accepted && target != before) announceSwitch()
    render()
}

/**
 * Another package's input method was picked. The composition is committed first, as before any
 * outside write, because the engine is replaced and its spelling cannot continue; a field that is
 * no longer current keeps the keyboard where it is.
 */
private suspend fun KeyboardController.switchToOtherPackage(method: OtherInputMethod) {
    val settled = confirmComposition()
    if (!settled) {
        closeKeyboardMenu()
        render()
        return
    }
    // The menu stays up, its new choice lit, until the new keys can fade in in its place: closing
    // it first would show the old keys only to take them away again.
    announceWhenReady = true
    menu.markPicked(method.menuId)
    callbacks.switchSource(method.sourceId, method.schemaId)
}

/** Another package's engine is ready and shown: the menu gives way to its keys. */
internal fun KeyboardController.finishSwitch() {
    announceWhenReady = false
    closeKeyboardMenu()
    syncMenu()
    announceSwitch()
}

/**
 * The user switched input method, layout or board: the new keys fade in, and when the input method
 * changed ([named]) the space bar names it for [SWITCH_NOTICE_MS], so the switch shows where the
 * eyes already are. A new layout shows in its keys; naming the unchanged input method adds nothing.
 * The fade only changes opacity, so a key pressed during it lands as usual; with animations off
 * the keys simply appear.
 */
internal fun KeyboardController.announceSwitch(named: Boolean = true) {
    if (named) {
        switchNotice = true
        noticeTimer.removeCallbacks(endSwitchNotice)
        noticeTimer.postDelayed(endSwitchNotice, SWITCH_NOTICE_MS)
    }
    switchFade?.cancel()
    if (!ImeMotion.enabled) {
        keyboard.alpha = 1f
        return
    }
    // A plain animator, not the view's: it runs even while the view is detached (the notice of
    // another package arrives with its engine), so the keys never stay faint.
    switchFade =
        // Keys uncovered by the preparing notice are hidden already and fade in from there.
        ValueAnimator.ofFloat(if (keyboard.alpha < 1f) keyboard.alpha else ImeMotion.SWITCH_FROM_ALPHA, 1f).apply {
            duration = ImeMotion.SWITCH_MS
            interpolator = ImeMotion.standard
            addUpdateListener { keyboard.alpha = it.animatedValue as Float }
            start()
        }
}

/** How long the space bar names the keyboard after a switch; the same as on iOS. */
internal const val SWITCH_NOTICE_MS = 2_500L

internal suspend fun KeyboardController.cycleLayout() {
    val usable = jumpableLayouts()
    val offered = KeyboardMenuEntries.layouts(menuHiddenLayouts, layoutKind, usable)
    val next = KeyboardMenuEntries.nextLayout(offered, layoutKind) ?: return
    chooseLayout(next)
}

/**
 * The scheme families the session already resolved (the cached profile carries the engine alphabet),
 * for choosing a keyboard's physical layout. An id the language table cannot place by its name still
 * selects the layout of its family; before the session is ready the id-only answer stands.
 */
internal fun KeyboardController.layoutFamilies(): LayoutFamilyContext =
    session?.layoutFamilyContext() ?: LayoutFamilyContext.UNKNOWN

internal fun KeyboardController.syncSelection(active: InputSession) {
    layoutKind = active.layout
    if (active.selectedSchemaID.isNotBlank()) schemaId = active.selectedSchemaID
    applyLayoutCapabilities(active)
}

/**
 * The compaction capabilities the engine queries are gated on: only a folded layout may ask for
 * spellings/readings, and only the telephone pad renders the numeric transport.
 */
internal fun KeyboardController.applyLayoutCapabilities(active: InputSession) {
    active.compactSpellingLayout = active.layout.requiresSpellingDisplay
    active.numericTransportLayout = active.layout == KeyboardLayoutKind.PHONE9 && page != null
}

internal suspend fun KeyboardController.updateSwipe(active: InputSession) {
    val fold = active.layout.compactFold
    if (fold == null || active.mode != InputMode.CHINESE) {
        mergedSwipe = null
        return
    }
    val schema = active.snapshot.schemaID
    val alphabet = runCatching { active.engine.spellerAlphabet(schema) }.getOrNull().orEmpty()
    if (alphabet.isNotEmpty()) {
        mergedSwipe = MergedSwipeConfig(fold, alphabet, useExactTokens = true)
        return
    }
    // No alphabet could be read for the running schema: the generated companion for this layout is
    // still structural proof that plain letters are readable, but nothing proves it reads the exact
    // tokens, so none is ever sent.
    val companion = BuiltinTemplateSchemas.engineSchemaID(active.selectedSchemaID, active.layout)
    val proven =
        companion != null &&
            companion != active.selectedSchemaID &&
            (schema.isEmpty() || schema == companion)
    mergedSwipe = if (proven) MergedSwipeConfig(fold, LATIN_LETTERS, useExactTokens = false) else null
}
