package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.display.CompositionDisplayPolicy
import app.songyan.ime.keyboard.engine.AmbiguityApplyOutcome
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendError
import app.songyan.ime.keyboard.engine.EngineOptionState
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.engine.InputLanguageProfile
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.ReadingCandidate
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.support.ShiftState
import kotlinx.coroutines.CancellationException
import java.util.concurrent.atomic.AtomicBoolean

/** Input mode: Chinese composition, or English (ASCII) direct input. */
enum class InputMode {
    /** Chinese: letter/digit codes go to the engine for composition (default). */
    CHINESE,

    /** English: letters/digits/space/return/symbols are committed directly, bypassing the engine. */
    ENGLISH,
}

/**
 * The consumer-side input state machine: it owns an [EngineBackend] and the current layout,
 * translates keycap actions into engine calls, and broadcasts state.
 *
 * Threading: every function is expected to be called from one serial owner (the IME's
 * view/controller coroutine scope); the engine backend serializes internally on its own dispatcher,
 * so these suspend calls never block the frame. UI must never mutate engine state directly.
 */
class InputSession(
    val engine: EngineBackend,
    layout: KeyboardLayoutKind,
    resourceSet: KeyboardResourceSet? = null,
) {
    val layout: KeyboardLayoutKind
        get() = currentLayout

    var selectedSchemaID: String
        internal set

    var mode: InputMode = InputMode.CHINESE
        private set

    var snapshot: EngineSnapshot = EngineSnapshot()
        private set

    var options: List<EngineOptionState> = emptyList()
        internal set

    /**
     * Cached input language of the source schema. It is recomputed only when the schema or a
     * declared script switch changes, so drawing a key never queries the engine for it.
     */
    var inputLanguage: InputLanguageProfile =
        InputLanguageProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC)
        internal set

    /** The schema and declared script conversion [inputLanguage] was derived from. */
    internal var inputLanguageSource: String? = null

    private var familyContextKey: Pair<String, InputSchemeFamily>? = null
    private var familyContextValue: LayoutFamilyContext = LayoutFamilyContext.UNKNOWN

    /**
     * The families this session already resolved, for layout selection. Reading the cached profile
     * keeps schema resolution and the engine alphabet off the key path; the same schema and family
     * hand back the same context, so drawing a key allocates nothing.
     */
    fun layoutFamilyContext(): LayoutFamilyContext {
        val family = inputLanguage.family
        familyContextKey?.let { if (it.first == selectedSchemaID && it.second == family) return familyContextValue }
        val context = LayoutFamilyContext.of(selectedSchemaID to family)
        familyContextKey = selectedSchemaID to family
        familyContextValue = context
        return context
    }

    internal var inputLanguageConversion: String? = null

    /** `speller/alphabet` read once per source schema; an empty entry is a failed read (fail closed). */
    internal val alphabetCache = mutableMapOf<String, String>()

    /**
     * Real engine-state version, incremented on every [apply]. The host uses it as part of the
     * candidate-set identity, so a set with the same raw code is invalidated when
     * options/reconversion/learning/session change. Read-only browsing does not increment it.
     */
    var compositionRevision: Int = 0
        private set

    var shiftState: ShiftState = ShiftState.OFF
        private set

    /**
     * True when the user armed the current ONCE/LOCKED shift by hand. An automatic caps update from
     * the host must not cancel a manual one-shot shift, otherwise a selection callback that arrives
     * just after the Shift tap silently eats the user's next capital.
     */
    private var shiftIsManual = false

    /**
     * Whether the current physical definition is proven to support compact spelling (composition
     * disambiguation / read-only reading browse). Injected by the front end from the real layout
     * structure; false (fail closed) means no query and no rewrite ever reaches the engine.
     */
    var compactSpellingLayout: Boolean = false
        set(value) {
            field = value
            spellingDemandEligible.set(value)
        }

    /**
     * True only when the current physical definition structurally matches a telephone keypad.
     * Combined with an all-digit engine [EngineSnapshot.rawInput] this is the fail-closed protection
     * for imported 9-key adapters that cannot provide a trusted canonical display capability.
     */
    var numericTransportLayout: Boolean = false

    /**
     * The candidate a confirming key must commit instead of the engine's highlight, as an absolute
     * index: a reading filter decides what space, return and the symbol keys confirm. [NO_TARGET]
     * means a filter is active but leaves nothing to confirm, so the key confirms nothing. Null
     * (the default) keeps the engine's own choice.
     */
    var confirmationTarget: (() -> Int?)? = null

    /** Layout->schema mapping of the current resource set; null = the built-in template pairing. */
    val resourceSet: KeyboardResourceSet?

    /** Called on state changes (candidates/preedit/composition state). */
    var onUpdate: (() -> Unit)? = null

    /** Called on engine errors. */
    var onError: ((Throwable) -> Unit)? = null

    /** Called with text to commit each time the engine produces a commit. */
    var onCommitted: ((String) -> Unit)? = null

    /** Preferred output route. True means a live host outlet accepted the commit. */
    var onDispatchCommit: ((String) -> Boolean)? = null

    /** Host hook for relative cursor movement (negative = left); nil = no movement, never faked. */
    var onMoveCursor: ((Int) -> Unit)? = null

    val inputUnavailableReason: String? get() = engine.inputUnavailableReason

    /**
     * Whether the engine may learn from what is typed. False for password fields and for editors that
     * pass `IME_FLAG_NO_PERSONALIZED_LEARNING`; the IME sets it before every input session.
     *
     * Disabling dismisses any post-commit prediction and stops prediction candidates from reaching
     * the UI, and asks the engine to stop writing its user dictionary. It never changes commits.
     */
    var learningEnabled: Boolean = true
        internal set

    internal var currentLayout: KeyboardLayoutKind

    private val spellingDemandEligible = AtomicBoolean(false)

    internal var retired = false

    init {
        currentLayout = layout
        val resolved: KeyboardResourceSet? =
            if (resourceSet != null &&
                (resourceSet.selectedSchemaID != null || resourceSet.schemas.isNotEmpty())
            ) {
                val runtime = engine.availableSchemas
                val schemas =
                    if (resourceSet.schemas.isEmpty()) {
                        runtime
                    } else if (engine.kind.isRealRime) {
                        resourceSet.schemas.filter { source -> runtime.any { it.id == source.id } }
                    } else {
                        resourceSet.schemas
                    }
                resourceSet.copy(schemas = schemas)
            } else {
                resourceSet
            }
        this.resourceSet = resolved
        val actualSchema = engine.currentSnapshot().schemaID
        val initial =
            if (actualSchema.isEmpty()) {
                resolved?.schemaIDFor(currentLayout)
                    ?: BuiltinTemplateSchemas.defaultSchemaID(currentLayout)
                    ?: ""
            } else {
                actualSchema
            }
        selectedSchemaID = resolved?.sourceSchemaID(initial) ?: initial
        engine.spellingDemand = { spellingDemandEligible.get() }
    }

    /** Kind-level hint that the layout is a compact spelling layout; not the engine gate. */
    val requiresSpellingDisplay: Boolean get() = currentLayout.requiresSpellingDisplay

    // MARK: Options and mode

    suspend fun reloadOptions() {
        if (retired) return
        options =
            try {
                engine.optionStates()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                options = emptyList()
                // The scheme may have changed under the failed read: its family must not outlive it.
                refreshInputLanguage()
                onError?.invoke(error)
                onUpdate?.invoke()
                return
            }
        refreshInputLanguage()
        onUpdate?.invoke()
    }

    suspend fun setOption(
        name: String,
        value: Boolean,
    ) = setOptionImpl(name, value)

    /**
     * Switches input mode. Before entering English the current composition is confirmed; if that
     * fails or a composition remains, Chinese mode is kept. The English page shows no
     * candidates/preedit, but a post-commit prediction survives the switch and stays tappable.
     */
    suspend fun setMode(newMode: InputMode) {
        if (retired || newMode == mode) return
        val keepsPrediction = newMode == InputMode.ENGLISH && snapshot.isPrediction
        if (newMode == InputMode.ENGLISH && !keepsPrediction) {
            if (!commitComposition()) return
        }
        mode = newMode
        // Shift is transient English-input state: any mode switch resets it so a previous
        // uppercase state cannot leak into the next session.
        shiftState = ShiftState.OFF
        shiftIsManual = false
        if (newMode == InputMode.ENGLISH) {
            apply(if (keepsPrediction) snapshot else EngineSnapshot())
        } else {
            refresh()
        }
    }

    /** Whether the engine can stop learning while input continues (see [setLearningEnabled]). */
    val learningProtectionSupported: Boolean get() = engine.supportsLearningSuppression

    /** What a [setLearningEnabled] call actually achieved. */
    enum class LearningProtection {
        /** The engine stopped learning; composition and prediction remain available. */
        ENFORCED,

        /**
         * The engine cannot stop learning by itself. The caller must not send composition keys in
         * this editor: use direct input (English / literal commits) instead.
         */
        UNSUPPORTED,
    }

    /**
     * Applies the privacy state of the current editor (password fields, editors that pass
     * `IME_FLAG_NO_PERSONALIZED_LEARNING`).
     *
     * Disabling first dismisses and then suppresses post-commit prediction, so no learning-derived
     * candidate reaches the UI. It then asks the engine to stop writing learning data; when the
     * engine cannot do that, [LearningProtection.UNSUPPORTED] is returned and the caller must fall
     * back to direct input. Unsupported protection is never reported as success.
     */
    suspend fun setLearningEnabled(enabled: Boolean): LearningProtection = setLearningEnabledImpl(enabled)

    /**
     * True when learning is off for this editor but the engine could not keep a commit out of its
     * learning data; the host must stop composing here (see [EngineBackend.learningSuppressionIntact]).
     */
    val learningProtectionLost: Boolean get() = !learningEnabled && !engine.learningSuppressionIntact

    /** Removes prediction candidates from the published snapshot (never mutates engine state). */
    internal fun suppressPrediction() {
        if (!snapshot.isPrediction) return
        snapshot = snapshot.copy(candidates = emptyList(), isPrediction = false, highlightedIndex = -1)
    }

    // MARK: Shift tri-state (English direct input)

    /**
     * Shift single tap: off -> once; a tap in once/locked releases to off. Chinese mode arms it too:
     * the next letter goes to the engine as a capital (see [tap]).
     */
    fun tapShift() {
        shiftState =
            when (shiftState) {
                ShiftState.OFF -> ShiftState.ONCE
                ShiftState.ONCE, ShiftState.LOCKED -> ShiftState.OFF
            }
        shiftIsManual = shiftState.isActive
        onUpdate?.invoke()
    }

    /**
     * Applies a host-derived one-shot capital without disturbing manual Caps Lock, and without
     * clearing a one-shot capital the user just armed: a late selection callback after the Shift tap
     * is not the user changing their mind.
     */
    fun setAutomaticShift(active: Boolean) {
        if ((mode != InputMode.ENGLISH && !snapshot.isAsciiMode) || shiftState == ShiftState.LOCKED) return
        if (shiftIsManual && shiftState == ShiftState.ONCE) return
        val target = if (active) ShiftState.ONCE else ShiftState.OFF
        if (shiftState == target) return
        shiftState = target
        shiftIsManual = false
        onUpdate?.invoke()
    }

    /**
     * Shift double tap = Caps Lock, in Chinese mode too: every letter then reaches the engine as a
     * capital, and a schema that does not read capitals types them as themselves. Recognizing the
     * double tap is the UI gesture's job.
     */
    fun doubleTapShift() {
        shiftState = if (shiftState == ShiftState.LOCKED) ShiftState.OFF else ShiftState.LOCKED
        shiftIsManual = shiftState.isActive
        onUpdate?.invoke()
    }

    // MARK: Schema / layout selection

    /**
     * Validates the target, then confirms the current composition and switches schema. If
     * confirmation fails, the composition and the original layout are kept.
     */
    suspend fun selectLayout(newLayout: KeyboardLayoutKind) = selectLayoutImpl(newLayout)

    /** Selects a schema (by its user-visible id), switching layout when the pairing differs. */
    suspend fun selectSchema(schemaID: String) = selectSchemaImpl(schemaID)

    /** Explicit layout association: validate both targets, then confirm once and switch atomically. */
    suspend fun selectLayout(
        newLayout: KeyboardLayoutKind,
        boundSchemaID: String,
    ) = selectLayoutBoundImpl(newLayout, boundSchemaID)

    /** Explicit revalidation of the current schema (separate from selecting the current item). */
    suspend fun revalidateCurrentSchema() = revalidateCurrentSchemaImpl()

    /**
     * Repairs a running layout the current schema's family cannot use, keeping the schema. Called on
     * editor start, where a stored pair can match the preference and still be a forbidden one.
     */
    suspend fun repairFamilyLayout() = repairFamilyLayoutImpl()

    /**
     * User-requested resource/layout transitions must call this before teardown. A failed or partial
     * confirmation keeps the current composition and rejects the transition. Compact spelling is off
     * while the composition is confirmed.
     */
    suspend fun prepareForConfigurationChange(): Boolean {
        if (retired) return false
        val previous = compactSpellingLayout
        compactSpellingLayout = false
        try {
            return commitComposition()
        } finally {
            compactSpellingLayout = previous
        }
    }

    // MARK: Key handling

    /**
     * Handles one keycap action.
     *
     * [KeyAction.Literal] and [KeyAction.LetterVariant] commit text directly; in English mode
     * letters/digits/space/return are likewise committed directly, and backspace is left to the host
     * instead of going to the engine.
     */
    suspend fun tap(action: KeyAction) {
        if (!inputAvailable()) return
        when (action) {
            is KeyAction.LetterVariant -> {
                commitTextKey(action.text)
                return
            }

            // Both text-key kinds share the same shift rule: the English page's letters reach the
            // session as literals on some layouts, and a literal letter must obey Shift exactly like
            // a key code. Digits and punctuation keep the shift state for the next letter.
            is KeyAction.Literal -> {
                commitTextKey(action.text)
                return
            }

            else -> Unit
        }
        if (mode == InputMode.ENGLISH) {
            when (action) {
                is KeyAction.Code -> {
                    // Shift applies here: once/locked uppercase ASCII letters, and once resets after
                    // a letter (digits/symbols do not consume it).
                    val output = englishOutput(action.value)
                    insertLiteral(output.first)
                    if (output.second) onUpdate?.invoke()
                }

                KeyAction.Space -> insertLiteral(" ")
                KeyAction.Enter -> insertLiteral("\n")

                // An empty-composition backspace is deleted by the host text view.
                KeyAction.Backspace -> return
                KeyAction.SwitchKeyboard, KeyAction.CycleLayout -> return
                is KeyAction.Literal, is KeyAction.LetterVariant -> return
            }
            return
        }
        try {
            when (action) {
                is KeyAction.Code -> {
                    if (snapshot.isAsciiMode) {
                        // Rime owns ASCII mode, so keep the key on the engine path while applying
                        // the keyboard's visible Shift state to the emitted key.
                        val output = englishOutput(action.value)
                        apply(engine.processKey(output.first))
                        if (output.second) onUpdate?.invoke()
                    } else {
                        // An armed Shift in Chinese mode sends the capital to the engine too: the
                        // input method decides what it means (rime-ice's `V` symbol input, for
                        // one). A schema that does not read capitals hands the letter back, and it
                        // is typed as itself after whatever was composed.
                        val output = englishOutput(action.value)
                        apply(engine.processKey(output.first))
                        if (output.second) onUpdate?.invoke()
                    }
                }

                KeyAction.Space ->
                    when {
                        // Space can be a first-tone code rather than a candidate choice.
                        inputLanguage.family == InputSchemeFamily.ZHUYIN && !snapshot.isAsciiMode ->
                            apply(engine.processKey(EngineBackend.SPACE_KEY))
                        snapshot.isComposing && confirmationTarget?.invoke() != null -> commitComposition()
                        else -> apply(engine.processKey(EngineBackend.SPACE_KEY))
                    }
                KeyAction.Backspace -> {
                    var next = engine.processKey(EngineBackend.BACKSPACE_KEY)
                    // Taking back a partial choice reopens the earlier segment with the candidate that
                    // was chosen still highlighted; the keyboard never moves the highlight itself, so
                    // a highlight away from the first candidate here is only that leftover.
                    if (next.isComposing && next.highlightedIndex > 0 && next.committed.isEmpty()) {
                        next = engine.processKey(EngineBackend.HOME_KEY)
                    }
                    apply(next)
                }
                KeyAction.Enter ->
                    if (inputLanguage.family == InputSchemeFamily.JAPANESE && !snapshot.isAsciiMode) {
                        // The scheme's Return binding commits kana, independently of the candidate filter.
                        apply(engine.processKey(EngineBackend.RETURN_KEY))
                    } else if (snapshot.isComposing && confirmationTarget?.invoke() != null) {
                        commitComposition()
                    } else if (snapshot.isComposing && (compactSpellingLayout || numericTransportLayout)) {
                        // The raw input of a folded or numeric layout is key codes, not letters, so
                        // Return confirms the highlighted candidate like Space does; the engine's
                        // commit-the-raw-input default would type the digits.
                        apply(engine.processKey(EngineBackend.SPACE_KEY))
                    } else {
                        apply(engine.processKey(EngineBackend.RETURN_KEY))
                    }

                // Handled by the host view layer (system input-method switch / layout cycle).
                KeyAction.SwitchKeyboard, KeyAction.CycleLayout -> return
                is KeyAction.Literal -> insertLiteral(action.text)
                is KeyAction.LetterVariant -> insertLiteral(action.text)
            }
        } catch (error: EngineBackendError) {
            onError?.invoke(error)
        }
    }

    /**
     * Commits one text key (a literal or a letter variant) in English direct input.
     *
     * A single letter is uppercased while Shift is active and consumes a one-shot Shift; digits and
     * punctuation pass through and keep the shift armed. Caps Lock keeps uppercasing until toggled.
     */
    private suspend fun commitTextKey(text: String) {
        val consumesShift = mode == InputMode.ENGLISH && text.length == 1 && text[0].isLetter()
        insertLiteral(if (consumesShift && shiftState.isActive) text.uppercase() else text)
        if (consumesShift && shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
            shiftIsManual = false
            onUpdate?.invoke()
        }
    }

    /** Commits text directly (digit/symbol keys and English-mode letters). */
    suspend fun insertLiteral(text: String): Boolean {
        if (!inputAvailable() || text.isEmpty()) return false
        if (snapshot.isComposing || snapshot.isPrediction) {
            if (!commitComposition()) return false
        }
        return dispatchCommit(text)
    }

    /**
     * Confirms the current composition (the shared confirm semantics before space/return/English
     * switch/symbol keys):
     *  - candidates present: confirms the highlighted one (index 0 if none highlighted);
     *  - composing without candidates: sends return so the engine commits the raw text;
     *  - empty composition: no-op;
     *  - post-commit prediction: only dismisses it, never picks a word for the user.
     */
    suspend fun commitComposition(): Boolean = commitCompositionImpl()

    // MARK: Candidate selection and paging

    /** Selects the candidate at page-local [index]. */
    suspend fun selectCandidate(index: Int) {
        if (!inputAvailable()) return
        try {
            apply(engine.selectCandidate(index))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError?.invoke(error)
        }
    }

    /**
     * Forgets the candidate [text] at the cross-page absolute [absoluteIndex]. True when the word is
     * gone from that place afterwards; false when it stayed (a word the user never taught the engine)
     * or the engine cannot delete.
     */
    suspend fun deleteCandidate(
        absoluteIndex: Int,
        text: String,
    ): Boolean = deleteCandidateImpl(absoluteIndex, text)

    /**
     * Selects a candidate by cross-page absolute index. Used after the front end shows several pages
     * continuously so a concatenated-array index is not mistaken for a per-page index.
     */
    suspend fun selectCandidateAbsolute(index: Int) {
        if (!inputAvailable()) return
        try {
            apply(engine.selectCandidateAbsolute(index))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError?.invoke(error)
        }
    }

    enum class CandidateConfirmationResult { FAILED, PARTIAL, COMPLETED }

    companion object {
        /** A [confirmationTarget] answer: a reading filter is active and no candidate matches it. */
        const val NO_TARGET: Int = -1
    }

    /** Exactly one explicit selection; a changed snapshot is not proof of full confirmation. */
    suspend fun confirmCandidateAbsolute(index: Int): CandidateConfirmationResult = confirmCandidateAbsoluteImpl(index)

    suspend fun nextPage() {
        if (!inputAvailable()) return
        try {
            apply(engine.nextPage())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError?.invoke(error)
        }
    }

    suspend fun previousPage() {
        if (!inputAvailable()) return
        try {
            apply(engine.previousPage())
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError?.invoke(error)
        }
    }

    // MARK: Cursor movement

    /**
     * Requests moving the cursor by [offset] characters (negative = left). The session never touches
     * the text field: with a composition the composition is committed first (same confirm path as
     * [insertLiteral], never silently dropping typed input), then [onMoveCursor] fires.
     */
    suspend fun moveCursor(offset: Int) {
        if (!inputAvailable()) return
        if (offset == 0) return
        if (snapshot.isComposing || snapshot.isPrediction) {
            if (!commitComposition()) return
        }
        onMoveCursor?.invoke(offset)
    }

    /**
     * Moves the engine's composition caret by [offset] characters without confirming anything.
     *
     * This is the composition caret, not the editor's: nothing is committed and no text is typed.
     * Returns whether the caret really moved; false when idle, when the backend cannot move the
     * caret natively (the default), or when the engine reported the same caret position afterwards.
     */
    suspend fun moveCompositionCaret(offset: Int): Boolean {
        if (!inputAvailable() || offset == 0) return false
        if (!snapshot.isComposing) return false
        val before = snapshot.preeditCaretByte
        val moved =
            try {
                engine.moveCompositionCaret(offset)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onError?.invoke(error)
                return false
            } ?: return false
        apply(moved)
        return snapshot.isComposing && snapshot.preeditCaretByte != before
    }

    /**
     * Dismisses post-commit prediction. A no-op returning true when there is none; returns false if
     * dismissal fails, keeping the current state and never picking a word for the user.
     */
    suspend fun dismissPrediction(): Boolean {
        if (retired) return false
        if (!snapshot.isPrediction) return true
        return try {
            val updated = engine.dismissPrediction().copy(committed = "")
            apply(updated)
            !snapshot.isPrediction && !snapshot.isComposing
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            onError?.invoke(error)
            false
        }
    }

    /**
     * Reads the engine's current state (used on page entry/activation). A read never replays a
     * commit: the cached snapshot of a backend may still carry the text of an earlier operation.
     */
    suspend fun refresh() {
        if (retired) return
        apply(engine.currentSnapshot().copy(committed = ""))
    }

    /**
     * Explicitly discards only the engine composition. Never emits [onCommitted]; the host may clear
     * its document only after this returns true.
     *
     * Uses the engine's dedicated [EngineBackend.cancelComposition] rather than a synthesized Esc,
     * because librime's editor only steps back one segment. The returned commit is always emptied.
     * A failure keeps the last trusted composition and returns false, so a failure is never folded
     * into an empty non-composing state.
     */
    suspend fun discardComposition(): Boolean = discardCompositionImpl()

    /** Called by the owner before scheduling engine shutdown. */
    fun retire() {
        retired = true
        onUpdate = null
        onError = null
        onCommitted = null
        onDispatchCommit = null
        onMoveCursor = null
    }

    // MARK: Read-only browsing and ambiguity (compact spelling)

    val supportsReadOnlyBrowsing: Boolean get() = engine.supportsReadOnlyBrowsing

    /**
     * Read-only fetch of the candidates of a page by absolute offset. It does not change the
     * engine's page/highlight and does not [apply], so host marked text is untouched.
     *
     * Error / termination semantics: browsing unsupported, no paging, or an invalid page returns an
     * empty list (normal termination); a failed read throws (retryable); an empty or short page on
     * success is true EOF.
     */
    suspend fun readCandidatePage(pageIndex: Int): List<Candidate> = readCandidatePageImpl(pageIndex)

    /** Read-only spelling report for the tail segment; null when unprovable (no guessing). */
    suspend fun ambiguitySpellings(): AmbiguitySpellingsReport? = ambiguitySpellingsImpl()

    /** Read-only reading browse; null = not authorized or unsupported (callers terminate normally). */
    suspend fun readCandidatesWithReadings(
        fromIndex: Int,
        count: Int,
    ): List<ReadingCandidate>? = readCandidatesWithReadingsImpl(fromIndex, count)

    /** Compare-and-swap spelling rewrite; refreshes from the engine's real post-state on success. */
    suspend fun applyAmbiguityOption(
        option: String,
        expectedInput: String,
        segment: IntRange,
    ): AmbiguityApplyOutcome = applyAmbiguityOptionImpl(option, expectedInput, segment)

    /** Compare-and-swap undo of a locked segment; same gate as [applyAmbiguityOption]. */
    suspend fun restoreAmbiguity(
        expectedInput: String,
        segment: IntRange,
        expectedOption: String,
        reducedCode: String,
    ): AmbiguityApplyOutcome = restoreAmbiguityImpl(expectedInput, segment, expectedOption, reducedCode)

    // MARK: Private

    internal fun inputAvailable(): Boolean {
        if (retired) return false
        val reason = inputUnavailableReason ?: return true
        onError?.invoke(EngineBackendError.OperationFailed(reason))
        return false
    }

    private fun dispatchCommit(text: String): Boolean {
        onDispatchCommit?.let { return it(text) }
        onCommitted?.let {
            it(text)
            return true
        }
        return false
    }

    /**
     * Shift conversion for a letter typed in direct English input: once/locked uppercase ASCII a-z;
     * the second component says whether the shift state changed (the caller then fires onUpdate).
     */
    private fun englishOutput(raw: String): Pair<String, Boolean> {
        if (raw.length != 1) return raw to false
        val char = raw[0]
        if (char !in 'a'..'z') return raw to false
        if (!shiftState.isActive) return raw to false
        val upper = char.uppercaseChar().toString()
        if (shiftState == ShiftState.ONCE) {
            shiftState = ShiftState.OFF
            shiftIsManual = false
            return upper to true
        }
        return upper to false
    }

    /**
     * Folds an engine snapshot into session state: only [EngineSnapshot.committed] is cleared (it is
     * dispatched separately); everything else, including paging metadata and candidate indices, is
     * kept as the engine reported it. Returns whether the commit was delivered.
     */
    internal fun apply(newSnapshot: EngineSnapshot): Boolean {
        compositionRevision += 1
        val committed = newSnapshot.committed
        snapshot = newSnapshot.copy(committed = "")
        snapshot =
            snapshot.copy(
                hidesNumericTransportCode =
                    snapshot.hidesNumericTransportCode ||
                        (numericTransportLayout && CompositionDisplayPolicy.isNumericTransport(snapshot.rawInput)),
            )
        if (newSnapshot.schemaID.isNotEmpty()) {
            selectedSchemaID = resourceSet?.sourceSchemaID(newSnapshot.schemaID) ?: newSnapshot.schemaID
        }
        val delivered = committed.isNotEmpty() && dispatchCommit(committed)
        onUpdate?.invoke()
        return delivered
    }
}

/**
 * [runCatching] that lets coroutine cancellation through: a cancelled caller must stop, not carry on
 * as if an engine call had merely failed.
 */
internal inline fun <T> catchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
