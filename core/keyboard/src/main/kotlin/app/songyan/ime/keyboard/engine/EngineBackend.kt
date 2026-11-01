package app.songyan.ime.keyboard.engine

/**
 * The consumer-side engine contract: the only engine surface the keyboard and the settings UI
 * depend on. Implementations (the real librime bridge, and the demo engine used by tests) run every
 * call on one serial owner; callers may call these suspend functions from any coroutine.
 *
 * Optional capabilities have defaults so an implementation only overrides what it supports.
 * Defaults must remain "fail closed": never invent a value the engine did not prove.
 */
interface EngineBackend {
    /** Engine implementation kind; the UI labels demo candidates as demo data. */
    val kind: EngineBackendKind

    /** Non-null after an unrecoverable activation failure; input must wait for recovery. */
    val inputUnavailableReason: String?

    /**
     * Directories needed for install/deploy. Real engine: shared = deployed scheme resource directory,
     * user = isolated user directory (build/, userdb, sync). May be slow; call off the main thread.
     */
    suspend fun setup(
        sharedDirectory: String,
        userDirectory: String,
    )

    /** Activates an input scheme; [schemaID] is the engine-side schema id (e.g. `songyan_quanpin`). */
    suspend fun selectSchema(schemaID: String)

    /** Non-mutating preflight before confirming an input for a user-requested layout change. */
    suspend fun validateSchema(schemaID: String) = Unit

    /** Actual enabled schemas for this engine/resource generation, not the compiled inventory. */
    val availableSchemas: List<KeyboardSchemaDescriptor>

    /**
     * Compact-layout companions of this generation's source schemes ([EncodingAdapterRegistry]).
     * They are selectable engine schemas without being listed in [availableSchemas].
     */
    val encodingAdapters: List<KeyboardResourceSet.EncodingAdapter> get() = emptyList()

    /** The `speller/alphabet` of a running schema, generated adapters included; null when unknown. */
    suspend fun spellerAlphabet(schemaID: String): String? =
        availableSchemas.firstOrNull { it.id == schemaID }?.alphabet

    /**
     * Processes one key press. [keyValue] is a logical key:
     *  - a single-character text key (lowercase letter or digit);
     *  - control keys use reserved values: `"\u0008"` (backspace), `" "` (space = confirm the
     *    composition), `"\n"` (return), `"Esc"` (cancel the composition).
     * Returns the resulting state snapshot.
     */
    suspend fun processKey(keyValue: String): EngineSnapshot

    /**
     * Cancels the current composition (no commit, no commit text) and returns the validated
     * post-cancel snapshot. Distinct from `processKey("Esc")`: librime's editor cancels only one
     * segment, so an engine with a dedicated clear API must override this to clear unconditionally.
     *
     * Failure contract: an unavailable session, a rejected cancel, or a failed post-cancel read must
     * throw; never substitute an empty snapshot for success, because the caller would then let the
     * host clear its document.
     */
    suspend fun cancelComposition(): EngineSnapshot = processKey(ESC_KEY)

    /**
     * Dismisses post-commit prediction without committing or deleting host text; returns the current
     * snapshot unchanged when there is none.
     */
    suspend fun dismissPrediction(): EngineSnapshot {
        val snapshot = currentSnapshot()
        if (!snapshot.isPrediction) return snapshot
        return processKey(ESC_KEY).copy(committed = "")
    }

    /** Selects the candidate at page-local [index]; the snapshot may carry commit text. */
    suspend fun selectCandidate(index: Int): EngineSnapshot

    /**
     * Forgets the candidate at the cross-page absolute [index] (a word the user's typing taught the
     * engine). Returns the state afterwards, or null when this engine cannot delete; the caller
     * compares what is listed to learn whether the word really went away. Default: unsupported.
     */
    suspend fun deleteCandidate(index: Int): EngineSnapshot? = null

    /**
     * Selects a candidate by cross-page absolute index. The default falls back to in-page selection
     * only when the target is on the current page; cross-page requests fail explicitly rather than
     * committing a concatenated index as an in-page one.
     */
    suspend fun selectCandidateAbsolute(index: Int): EngineSnapshot {
        val snapshot = currentSnapshot()
        if (snapshot.pageSize <= 0) return selectCandidate(index)
        if (index < 0 || index / snapshot.pageSize != snapshot.pageIndex) {
            throw EngineBackendError.OperationFailed("current engine does not support cross-page selection")
        }
        return selectCandidate(index % snapshot.pageSize)
    }

    /** Whether read-only cross-page candidate fetching is supported (never changes page/highlight). */
    val supportsReadOnlyBrowsing: Boolean get() = false

    /**
     * Reads a run of candidates by absolute index, read-only.
     *
     * Contract: throwing means genuinely unavailable or failed (callers may retry, must not treat it
     * as EOF); a normal return, including an empty or short list, is a successful read (real EOF).
     */
    suspend fun readCandidates(
        fromIndex: Int,
        count: Int,
    ): List<Candidate> = emptyList()

    /** Optional read-only pronunciation browsing; null means unsupported. */
    suspend fun readCandidatesWithReadings(
        fromIndex: Int,
        count: Int,
    ): List<ReadingCandidate>? = null

    /** Optional word completions for the ABC page; null = the schema cannot be selected. */
    suspend fun wordCompletions(
        prefix: String,
        schemaID: String,
        limit: Int,
    ): List<String>? = null

    /** Schemas worth probing for word completions of [sourceSchemaID], discovered from configuration. */
    suspend fun wordCompletionSchemas(sourceSchemaID: String): List<String> = emptyList()

    /** Drops the read-only word-completion session; the next query recreates it. */
    fun releaseWordCompletions() = Unit

    /**
     * Read-only marked-text proof: the highlighted preedit range and one complete pronunciation of
     * the absolute candidate must agree. Null means unsupported or unproven; never fall back to a comment.
     */
    suspend fun verifiedHighlightedReading(
        absoluteIndex: Int,
        expectedText: String,
        physicalLayoutEligible: Boolean,
    ): String? = null

    /** Pages / expands candidates (an engine without paging returns its snapshot unchanged). */
    suspend fun nextPage(): EngineSnapshot

    suspend fun previousPage(): EngineSnapshot

    /** Current state snapshot (non-suspend: the last state this backend produced). */
    fun currentSnapshot(): EngineSnapshot

    /** Releases/closes the engine; idempotent. */
    suspend fun shutdown()

    /** Stable, readable engine status summary for the about/debug page. */
    val statusSummary: String

    /** Only verified switches for the active resource/schema. False is a value, not a capability. */
    suspend fun optionStates(): List<EngineOptionState> = emptyList()

    /**
     * Whether this engine can really stop writing learning data while input continues (a genuine
     * gate, not a no-op option). Default false: the caller must then force direct input in private
     * editors instead of assuming the engine stopped learning.
     */
    val supportsLearningSuppression: Boolean get() = false

    /**
     * Stops ([suppressed] = true) or resumes writing learning data for the input that follows, without
     * changing what composes or commits.
     *
     * Returns true only when the engine proved the requested state; false means it cannot stop
     * learning right now, and the caller must not send composition keys in a private editor. The
     * default never claims suppression.
     */
    suspend fun setLearningSuppressed(suppressed: Boolean): Boolean = !suppressed

    /**
     * False once a commit made while learning was suppressed could not be kept out of the learning
     * data. The caller must then stop composing in the private editor; the flag resets on the next
     * [setLearningSuppressed].
     */
    val learningSuppressionIntact: Boolean get() = true

    /** Changes a switch and refreshes composition/candidates without committing input. */
    suspend fun setOption(
        name: String,
        value: Boolean,
    ): EngineSnapshot = throw EngineBackendError.OperationFailed("current input scheme does not support option $name")

    /**
     * Composition display request: when it returns true and a composition is active, the snapshot
     * must carry the highlighted candidate's trusted spelling. [InputSession] installs it for the
     * actual current layout, and it is evaluated inside the same engine call.
     */
    var spellingDemand: (() -> Boolean)?

    /** Read-only spelling report reachable for the last `abc` segment; null when unprovable. */
    suspend fun ambiguitySpellings(): AmbiguitySpellingsReport? = null

    /** Rewrites the tail segment to a proven spelling (compare-and-swap; revalidated engine-side). */
    suspend fun applyAmbiguitySpelling(
        expectedInput: String,
        segment: IntRange,
        option: String,
    ): AmbiguityApplyOutcome = AmbiguityApplyOutcome.UNSUPPORTED

    /** Restores a locked tail segment to the original reduced code (compare-and-swap). */
    suspend fun restoreAmbiguitySpelling(
        expectedInput: String,
        segment: IntRange,
        expectedOption: String,
        reducedCode: String,
    ): AmbiguityApplyOutcome = AmbiguityApplyOutcome.UNSUPPORTED

    companion object {
        /** Reserved key values understood by [processKey]. */
        const val BACKSPACE_KEY = "\u0008"
        const val SPACE_KEY = " "
        const val RETURN_KEY = "\n"
        const val ESC_KEY = "Esc"

        /** Moves the highlight to the first candidate of the page. */
        const val HOME_KEY = "Home"
    }
}
