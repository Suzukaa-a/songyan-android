package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
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
    render()
    ImePerf.ready()
    while (heldKeys.isNotEmpty() && ready) {
        val (action, t9FirstKey) = heldKeys.removeFirst()
        dispatch(action, t9FirstKey)
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
    preferredLayout = kind
    visitingLayout = false
    page = null
    preferences?.setLayoutId(kind.wire)
    val active = session
    if (active != null && ready) {
        active.selectLayout(kind, preferredSchema)
        syncSelection(active)
        updateSwipe(active)
    } else {
        layoutKind = kind
    }
    syncMenu()
    closeKeyboardMenu()
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
    preferredSchema = target
    page = null
    preferences?.setActiveSchemaId(target)
    val active = session
    if (active != null && ready) {
        active.selectLayout(layoutKind, target)
        syncSelection(active)
        updateSwipe(active)
    } else {
        schemaId = target
    }
    syncMenu()
    closeKeyboardMenu()
    render()
}

internal suspend fun KeyboardController.cycleLayout() {
    val usable = jumpableLayouts()
    val offered = KeyboardMenuEntries.layouts(menuHiddenLayouts, layoutKind).filter { it in usable }
    val next = KeyboardMenuEntries.nextLayout(offered, layoutKind) ?: return
    chooseLayout(next)
}

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
