package app.songyan.ime.rime

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.File

/**
 * Kotlin wrapper over the synced C bridge (`native/src/rime_bridge.h`).
 *
 * # Ownership and threading
 * librime is process-global and the bridge requires every call to be serialized, so
 * one [RimeEngine] is active per process and every native call runs on the engine's
 * process-wide engine thread ([RimeDispatcher]). All public entry points except [close] are `suspend`
 * functions that switch onto that thread, so callers never block the main thread.
 *
 * # Memory and state rules enforced here
 * * the bridge copies librime's strings into one snapshot inside a single native
 *   call, so [RimeSnapshot] is safe to hold across later key presses;
 * * reading a snapshot consumes librime's pending commit exactly once, so only the
 *   snapshot returned by the triggering action carries its [RimeSnapshot.commitText];
 * * a failed snapshot read marks the engine as needing recovery: the composition is
 *   cleared and the engine read again before anything else acts on it, so a
 *   possibly-consumed commit is never delivered twice and input never stays dead.
 */
class RimeEngine private constructor(
    private val dispatcher: RimeDispatcher,
    val configuration: Configuration,
) : Closeable {
    /**
     * Directories and identity handed to librime.
     *
     * [compiledDir] is the immutable deployed build directory: when it is set the
     * engine never runs maintenance, and a missing or mismatched schema fails start
     * instead of compiling dictionaries in the IME process.
     */
    data class Configuration(
        /** Read-only Rime data directory (default.yaml, *.schema.yaml, ...). */
        val sharedDataDir: File,
        /** Per-device writable directory (build/, *.userdb/, sync/...). */
        val userDataDir: File,
        /** librime app_name; it must start with "rime." so old logs are recycled. */
        val appName: String = DEFAULT_APP_NAME,
        /** librime distribution_version recorded in installation.yaml. */
        val appVersion: String? = null,
        /** Immutable deployed build directory; null means the engine deploys itself. */
        val compiledDir: File? = null,
    )

    internal var engine: Long = 0
    internal var session: Long = 0

    /** Read-only lookup session of [wordCompletions]; never the keyboard's own session. */
    private var wordSession: Long = 0
    private var wordSchemaId = ""
    internal var snapshotRecoveryRequired = false

    /** Test-only: the next this many snapshot reads are discarded as if the bridge failed them. */
    private var failedSnapshotReads = 0
    private var explicitlySetOptions: MutableSet<String> = linkedSetOf()
    private val mutableStatus = MutableStateFlow<RimeStatus>(RimeStatus.Stopped)
    private val mutableSnapshot = MutableStateFlow(RimeSnapshot.EMPTY)

    /** Lifecycle state the IME observes before it claims it can take input. */
    val status: StateFlow<RimeStatus> = mutableStatus.asStateFlow()

    /** Last trusted snapshot, updated by every action and by [readSnapshot]. */
    val snapshot: StateFlow<RimeSnapshot> = mutableSnapshot.asStateFlow()

    /** Whether the engine is initialized and has a live session. */
    suspend fun isRunning(): Boolean = dispatcher.onEngine { engine != 0L && session != 0L }

    /** librime version, or "-" before start. */
    suspend fun version(): String =
        dispatcher.onEngine {
            if (engine == 0L) "-" else RimeNative.nativeEngineVersion(engine) ?: "-"
        }

    /** Current schema id, or null when idle/unknown. */
    suspend fun currentSchemaId(): String? = dispatcher.onEngine { readCurrentSchemaId().ifEmpty { null } }

    /** Runtime registry lookup after start, not a symbol-name heuristic. */
    suspend fun componentAvailable(name: String): Boolean =
        dispatcher.onEngine { RimeNative.nativeComponentAvailable(name) }

    /** Components the built-in Basic package relies on, probed in the live registry. */
    suspend fun availableComponents(): Map<String, Boolean> =
        dispatcher.onEngine {
            REQUIRED_COMPONENTS.associateWith { RimeNative.nativeComponentAvailable(it) }
        }

    /** Deployment state observed so far. */
    suspend fun lastDeployState(): RimeDeployState = dispatcher.onEngine { deployState() }

    /** Candidates actually decoded since the last reset; 0 for layouts that never ask. */
    suspend fun spellingDecodeCount(): Long = dispatcher.onEngine { RimeNative.nativeSpellingDecodeCount() }

    /** Enabled schema list as the engine reports it (id and display name). */
    suspend fun schemas(): List<RimeSchema> =
        dispatcher.onEngine {
            val flat = RimeNative.nativeSchemaList() ?: return@onEngine emptyList()
            flat.toList().chunked(2).mapNotNull { pair ->
                val id = pair.getOrNull(0).orEmpty()
                if (id.isEmpty()) null else RimeSchema(id = id, name = pair.getOrNull(1).orEmpty().ifEmpty { id })
            }
        }

    // ---- sessions ----------------------------------------------------------

    /** Creates the input session, or returns the live one. */
    suspend fun createSession(): Long =
        dispatcher.onEngine {
            requireEngine()
            if (session != 0L) return@onEngine session
            val created = RimeNative.nativeSessionCreate(engine)
            if (created == 0L) {
                throw RimeException(RimeException.Kind.INITIALIZATION_FAILED, "cannot create session")
            }
            session = created
            created
        }

    /** Destroys a session; safe when none exists. */
    suspend fun destroySession(id: Long = session): Unit =
        dispatcher.onEngine {
            if (engine == 0L) return@onEngine
            if (id != 0L) RimeNative.nativeSessionDestroy(engine, id)
            if (id == session) session = 0L
        }

    // ---- input -------------------------------------------------------------

    private var learningSuppressed = false

    @Volatile
    internal var learningSuppressionIntact = true
        private set

    /** The backend probes the schema before enabling rollback; the policy lives with native commits. */
    internal suspend fun setLearningSuppressed(suppressed: Boolean) =
        dispatcher.onEngine {
            learningSuppressed = suppressed
            learningSuppressionIntact = true
        }

    /** Feeds one key; the result says whether the engine consumed it. */
    suspend fun processKey(key: RimeKey): RimeActionResult =
        dispatcher.onEngine(onLost = { keepUndelivered(it.snapshot.commitText) }) {
            requireReadableSession()
            val consumed = RimeNative.nativeProcessKey(engine, session, key.keycode, key.modifiers)
            RimeActionResult(consumed, actionSnapshot())
        }

    /**
     * Sends a key the engine is expected not to handle, and reports whether it stayed unhandled.
     *
     * librime keeps the user-dictionary update of a commit in an open transaction until the next
     * translation or unhandled key: an unhandled BackSpace within a few seconds drops it (librime's
     * own "forget the word I just committed"), any other unhandled key writes it. When the key was
     * handled after all, the state it produced is returned so the caller can publish it.
     */
    suspend fun sendUnhandledKey(key: RimeKey): RimeActionResult? =
        dispatcher.onEngine(onLost = { keepUndelivered(it?.snapshot?.commitText) }) {
            if (!canAct()) return@onEngine null
            val consumed = RimeNative.nativeProcessKey(engine, session, key.keycode, key.modifiers)
            // An unhandled key leaves the state untouched, so no read is spent on it.
            if (!consumed) return@onEngine RimeActionResult(false, mutableSnapshot.value)
            RimeActionResult(true, actionSnapshot())
        }

    /** Feeds a whole key sequence (librime's simulate_key_sequence grammar). */
    suspend fun processKeySequence(sequence: String): RimeActionResult =
        dispatcher.onEngine(onLost = { keepUndelivered(it.snapshot.commitText) }) {
            requireReadableSession()
            val consumed = RimeNative.nativeSimulateKeySequence(engine, session, sequence)
            RimeActionResult(consumed, actionSnapshot())
        }

    /** Moves the composition caret by [offset] input characters, stopping at either end. */
    suspend fun moveCaret(offset: Int): RimeActionResult =
        dispatcher.onEngine(onLost = { keepUndelivered(it.snapshot.commitText) }) {
            requireReadableSession()
            val moved = RimeNative.nativeMoveCaret(engine, session, offset)
            RimeActionResult(moved, actionSnapshot())
        }

    /** Commits the current composition (the Return-key path). */
    suspend fun commitComposition(): RimeActionResult =
        dispatcher.onEngine(onLost = { keepUndelivered(it.snapshot.commitText) }) {
            requireReadableSession()
            val committed = RimeNative.nativeCommitComposition(engine, session)
            RimeActionResult(committed, actionSnapshot())
        }

    // ---- candidates and paging --------------------------------------------

    /** Selects a candidate on the current page (0-based); null when refused. */
    suspend fun selectCandidate(index: Int): RimeSnapshot? =
        dispatcher.onEngine(onLost = { keepUndelivered(it?.commitText) }) {
            if (!canAct() || index < 0) return@onEngine null
            if (!RimeNative.nativeSelectCandidateOnPage(engine, session, index)) return@onEngine null
            snapshotOnEngine()
        }

    /** Selects a candidate by absolute index across pages (0-based); null when refused. */
    suspend fun selectCandidateAbsolute(index: Int): RimeSnapshot? =
        dispatcher.onEngine(onLost = { keepUndelivered(it?.commitText) }) {
            if (!canAct() || index < 0) return@onEngine null
            if (!RimeNative.nativeSelectCandidate(engine, session, index)) return@onEngine null
            snapshotOnEngine()
        }

    /**
     * Asks librime to forget the candidate at the absolute menu index (a learned word is marked
     * deleted in the user dictionary); returns the state afterwards, or null when the engine refused.
     */
    suspend fun deleteCandidate(index: Int): RimeSnapshot? =
        dispatcher.onEngine(onLost = { keepUndelivered(it?.commitText) }) {
            if (!canAct() || index < 0) return@onEngine null
            if (!RimeNative.nativeDeleteCandidate(engine, session, index)) return@onEngine null
            snapshotOnEngine()
        }

    suspend fun pageForward(): RimeSnapshot? =
        dispatcher.onEngine(onLost = { keepUndelivered(it?.commitText) }) { changePageOnEngine(false) }

    suspend fun pageBackward(): RimeSnapshot? =
        dispatcher.onEngine(onLost = { keepUndelivered(it?.commitText) }) { changePageOnEngine(true) }

    // ---- editing -----------------------------------------------------------

    /** Clears the current composition without committing; best effort. */
    suspend fun clearComposition(): Boolean =
        dispatcher.onEngine {
            if (!canAct()) return@onEngine false
            RimeNative.nativeClearComposition(engine, session)
            mutableSnapshot.value =
                mutableSnapshot.value.copy(
                    isComposing = false,
                    input = "",
                    preedit = "",
                    candidates = emptyList(),
                    commitText = "",
                )
            true
        }

    /**
     * Cancels the composition and returns a validated post-state, or throws.
     *
     * Unlike [clearComposition] this never substitutes success: a refused clear or an
     * unreadable post-state throws, because a synthetic "not composing" answer would
     * let the caller clear document text the engine never released.
     */
    suspend fun cancelComposition(): RimeSnapshot =
        dispatcher.onEngine {
            cancelCompositionOnEngine()
        }

    // ---- options and schema ------------------------------------------------

    suspend fun setOption(
        name: String,
        value: Boolean,
    ): Boolean =
        dispatcher.onEngine {
            if (!canAct()) return@onEngine false
            val ok = RimeNative.nativeSetOption(engine, session, name, value)
            if (ok) explicitlySetOptions.add(name)
            ok
        }

    suspend fun option(name: String): Boolean =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine false
            RimeNative.nativeGetOption(engine, session, name)
        }

    /** Activates a schema; false when the engine refuses (unknown or unusable schema). */
    suspend fun selectSchema(schemaId: String): Boolean =
        dispatcher.onEngine {
            if (!canAct()) return@onEngine false
            RimeNative.nativeSelectSchema(engine, session, schemaId)
        }

    // ---- state snapshots ---------------------------------------------------

    /**
     * Reads the current state, consuming any pending commit once.
     *
     * When the read fails the last trusted snapshot is returned without a commit and
     * the engine is marked for recovery; the caller is never told that a possibly
     * changed on-screen state is current.
     */
    suspend fun readSnapshot(): RimeSnapshot =
        dispatcher.onEngine(onLost = { keepUndelivered(it.commitText) }) { snapshotOrLastTrusted() }

    /**
     * Asked on the engine thread for every snapshot; when true the snapshot also carries the
     * highlighted syllables, so the front end needs no second engine hop for them.
     */
    @Volatile
    var spellingDemand: (() -> Boolean)? = null

    /** True when a snapshot read failed and the next action (or a cancel) has not recovered yet. */
    suspend fun requiresSnapshotRecovery(): Boolean = dispatcher.onEngine { snapshotRecoveryRequired }

    /** Makes the next [count] snapshot reads fail after the native read ran; for recovery tests. */
    internal suspend fun failNextSnapshotReads(count: Int) = dispatcher.onEngine { failedSnapshotReads = count }

    // ---- spelling and ambiguity -------------------------------------------

    /** Trusted spelling of the highlighted candidate, or null when unprovable. */
    suspend fun highlightedSpelling(): String? =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine null
            RimeNative.nativeHighlightedSpelling(session)
        }

    /** Canonical syllable tokens of the highlighted candidate. */
    suspend fun highlightedSyllables(): RimeHighlightedSyllables? =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine null
            RimeNative.nativeHighlightedSyllablesJson(session)?.let { RimeHighlightedSyllables.fromJson(it) }
        }

    /** Absolute pronunciations of one candidate; null when unproven. */
    suspend fun candidateSyllables(index: Int): RimeCandidateSyllables? =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine null
            RimeNative
                .nativeCandidateSyllablesJson(session, index)
                ?.let { RimeCandidateSyllables.fromJson(it) }
        }

    /**
     * Reads a run of the current segment's candidates by absolute index without moving the page,
     * the highlight or the selection. A short or empty list is the real end; null means the engine
     * could not answer. With [includeReadings] each entry carries its proven pronunciations.
     */
    suspend fun readCandidates(
        fromIndex: Int,
        count: Int,
        includeReadings: Boolean = false,
    ): List<RimeReadCandidate>? =
        dispatcher.onEngine {
            if (!canAct() || fromIndex < 0 || count <= 0) return@onEngine null
            readCandidatesOnEngine(session, fromIndex, count, includeReadings)
        }

    /**
     * Candidate texts for [prefix] looked up on [schemaId] in a second, read-only session (at most
     * [limit]). Null means the schema cannot be selected or the engine is not running.
     *
     * The lookup session never predicts: librime-predict keeps per-database state shared between
     * sessions, so a second predicting session would disturb the keyboard's own predictions.
     */
    suspend fun wordCompletions(
        prefix: String,
        schemaId: String,
        limit: Int,
    ): List<String>? =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L || schemaId.isEmpty() || limit <= 0) return@onEngine null
            if (wordSession == 0L || wordSchemaId != schemaId) {
                destroyWordSession()
                val created = RimeNative.nativeSessionCreate(engine)
                if (created == 0L) return@onEngine null
                val selected =
                    RimeNative.nativeSelectSchema(engine, created, schemaId) &&
                        RimeNative.nativeCurrentSchema(engine, created) == schemaId
                if (!selected) {
                    RimeNative.nativeSessionDestroy(engine, created)
                    return@onEngine null
                }
                RimeNative.nativeSetOption(engine, created, PREDICTION_OPTION, false)
                RimeNative.nativeClearComposition(engine, created)
                wordSession = created
                wordSchemaId = schemaId
            }
            try {
                if (prefix.isEmpty()) return@onEngine emptyList()
                if (!RimeNative.nativeSetInput(engine, wordSession, prefix)) return@onEngine null
                RimeNative.nativeReadCandidates(wordSession, 0, limit)?.toList()?.chunked(2)?.mapNotNull {
                    it.firstOrNull()?.takeIf { text -> text.isNotEmpty() }
                } ?: emptyList()
            } finally {
                RimeNative.nativeClearComposition(engine, wordSession)
            }
        }

    /**
     * Schemas a completion session may use for [sourceSchemaId], read from its own configuration:
     * each `table_translator@ns` it mounts (as `ns` and as that namespace's dictionary), then the
     * source itself. Ids that are not selectable schemas simply fail in [wordCompletions].
     */
    suspend fun wordCompletionSchemas(sourceSchemaId: String): List<String> =
        dispatcher.onEngine {
            if (sourceSchemaId.isEmpty()) return@onEngine emptyList()
            val chain = ArrayList<String>()
            for (translator in readSchemaStringList(sourceSchemaId, "engine/translators").orEmpty()) {
                if (!translator.startsWith(TABLE_TRANSLATOR_PREFIX)) continue
                val namespace = translator.removePrefix(TABLE_TRANSLATOR_PREFIX)
                val dictionaryKey = "$namespace/dictionary"
                val dictionary = readSchemaValues(sourceSchemaId, listOf(dictionaryKey))?.get(dictionaryKey)
                for (id in listOfNotNull(namespace, dictionary)) {
                    if (id.isNotEmpty() && id != sourceSchemaId && id !in chain) chain += id
                }
            }
            chain += sourceSchemaId
            chain
        }

    /** Drops the lookup session of [wordCompletions]; the next query recreates it. */
    fun releaseWordCompletionsAsync() = dispatcher.post { destroyWordSession() }

    internal fun destroyWordSession() {
        if (wordSession != 0L && engine != 0L) RimeNative.nativeSessionDestroy(engine, wordSession)
        wordSession = 0L
        wordSchemaId = ""
    }

    /** Read-only ambiguity report for the current tail `abc` segment. */
    suspend fun ambiguitySpellings(maxItems: Int = RimeAmbiguitySpellings.DEFAULT_LIMIT): RimeAmbiguitySpellings? =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine null
            RimeAmbiguityBridge.report(session, maxItems)
        }

    /** Capability-gated rewrite of the tail segment; never commits and never selects. */
    suspend fun applyAmbiguitySpelling(
        expectedInput: String,
        segmentStart: Int,
        segmentEnd: Int,
        spelling: String,
    ): RimeAmbiguityApplyResult =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine RimeAmbiguityApplyResult.FAILED
            RimeAmbiguityBridge.apply(session, expectedInput, segmentStart, segmentEnd, spelling)
        }

    /** Symmetric unlock: puts the reduced code back under the same CAS gating. */
    suspend fun restoreAmbiguitySpelling(
        expectedInput: String,
        segmentStart: Int,
        segmentEnd: Int,
        expectedSpelling: String,
        reducedCode: String,
    ): RimeAmbiguityApplyResult =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine RimeAmbiguityApplyResult.FAILED
            RimeAmbiguityBridge.restore(session, expectedInput, segmentStart, segmentEnd, expectedSpelling, reducedCode)
        }

    /** The active schema's declared display context (capability and source facts). */
    suspend fun displayContextJson(): String? =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine null
            RimeNative.nativeDisplayContextJson(session)
        }

    /** Read-only verification of the active schema's rewrite capability. */
    suspend fun rewriteVerification(): RimeRewriteVerification =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine RimeRewriteVerification.Unavailable
            val json =
                RimeNative.nativeRewriteVerificationJson(session)
                    ?: return@onEngine RimeRewriteVerification.Unavailable
            RimeRewriteVerification.fromJson(json)
        }

    /** Read-only page-slot select metadata for cross-page browsing. */
    suspend fun pageSelectMetadata(): RimePageSelectMetadata? =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine null
            RimeNative.nativeContextMetadataJson(session)?.let { RimePageSelectMetadata.fromJson(it) }
        }

    /** A config scalar of one deployed schema, or null when absent. */
    suspend fun schemaValue(
        schemaId: String,
        key: String,
    ): String? =
        dispatcher.onEngine {
            readSchemaValues(schemaId, listOf(key))?.get(key)
        }

    /** Config scalars of one deployed schema, read with a single schema open. */
    suspend fun schemaValues(
        schemaId: String,
        keys: List<String>,
    ): Map<String, String>? = dispatcher.onEngine { readSchemaValues(schemaId, keys) }

    /** A config scalar list of one deployed schema; null when absent or not a list. */
    suspend fun schemaStringList(
        schemaId: String,
        key: String,
    ): List<String>? = dispatcher.onEngine { readSchemaStringList(schemaId, key) }

    // ---- maintenance (deployment process only) -----------------------------

    /** Runs librime's native user-data sync, then restores a working session. */
    suspend fun syncUserData(): RimeSnapshot =
        dispatcher.onEngine {
            syncUserDataOnEngine()
        }

    /** Runs a maintenance pass; [fullCheck] recompiles changed dictionaries. */
    suspend fun redeploy(fullCheck: Boolean = true): RimeSnapshot =
        dispatcher.onEngine {
            redeployOnEngine(fullCheck)
        }

    /**
     * Compiles one schema file (with its dictionary and prism) into this engine's build directory
     * without adding it to `schema_list`, then restores the session. False when librime refused it.
     */
    suspend fun deploySchemaFile(file: File): Boolean =
        dispatcher.onEngine {
            deploySchemaFileOnEngine(file)
        }

    /**
     * The engine's projection of [schemaId]'s spellings (`rb_syllable_display_map_json`), read with
     * the schema selected in Chinese mode; the input for generating its compact companions. Null
     * when the schema cannot be selected or the projection cannot be proven. Deployment process only.
     */
    suspend fun sourceProjectionJson(schemaId: String): String? =
        dispatcher.onEngine {
            sourceProjectionJsonOnEngine(schemaId)
        }

    // ---- learning backup (levers) -----------------------------------------

    /** Whether the levers support compiled into this library is available. */
    suspend fun leversAvailable(): Boolean = dispatcher.onEngine { RimeNative.nativeLeversAvailable() }

    /** Declared user dictionaries that already have learning data on disk. */
    suspend fun userDictNames(): List<String> =
        dispatcher.onEngine { RimeNative.nativeUserDictList()?.filterNotNull().orEmpty() }

    /** Exports one user dictionary into the sync directory; the caller reads the file. */
    suspend fun backupUserDict(name: String): Boolean =
        dispatcher.onEngine {
            RimeNative.nativeLeversPrepareInstallation() && RimeNative.nativeBackupUserDict(name)
        }

    /** Merges one staged `.userdb.txt` back into the live user dictionary. */
    suspend fun restoreUserDict(path: String): Boolean =
        dispatcher.onEngine {
            RimeNative.nativeLeversPrepareInstallation() && RimeNative.nativeRestoreUserDict(path)
        }

    /**
     * Exports every user dictionary in this engine's user directory as a librime text snapshot
     * (`<sync>/<user_id>/<name>.userdb.txt`) and returns the files, or null when any export failed.
     *
     * Ends the session first: LevelDB grants one handle per directory and process, and the
     * session's translators hold theirs. The engine has no session afterwards; close it.
     */
    suspend fun exportUserDictSnapshots(): List<File>? =
        dispatcher.onEngine {
            exportUserDictSnapshotsOnEngine()
        }

    /**
     * Merges text snapshots into this engine's user dictionaries with librime's own merge (each
     * entry keeps the higher commit count and weight, so merging the same snapshot twice changes
     * nothing). Ends the session first, as [exportUserDictSnapshots] does.
     */
    suspend fun mergeUserDictSnapshots(snapshots: List<File>): Boolean =
        dispatcher.onEngine {
            mergeUserDictSnapshotsOnEngine(snapshots)
        }

    /** See [RimeLearningBackup.export]; ends the session, so close the engine afterwards. */
    internal suspend fun exportLearningData(): RimeLearningBackup.Export =
        dispatcher.onEngine { exportLearningOnEngine() }

    /** See [RimeLearningBackup.merge]; ends the session, so close the engine afterwards. */
    internal suspend fun mergeLearningData(
        snapshots: List<RimeLearningSnapshot>,
        documents: List<RimeUserDbDocument>,
    ): Unit = dispatcher.onEngine { mergeLearningOnEngine(snapshots, documents) }

    /** See [RimeLearningBackup.declaredDictionaries]. */
    internal suspend fun declaredUserDictionaries(): Set<String> =
        dispatcher.onEngine {
            requireEngine()
            declaredUserDictionariesOnEngine()
        }

    /** See [RimeLearningBackup.mergeSnapshotFiles]; ends the session, so close the engine afterwards. */
    internal suspend fun mergeSnapshotFiles(snapshots: List<RimeSnapshotFile>): Unit =
        dispatcher.onEngine { mergeSnapshotFilesOnEngine(snapshots) }

    /** Grammar diagnostics JSON (only the debug build records query samples). */
    suspend fun grammarDiagnosticsJson(): String? = dispatcher.onEngine { RimeNative.nativeGrammarDiagnosticsJson() }

    /** Whether the live session really attached the given grammar model file. */
    suspend fun grammarSessionLoaded(modelPath: String): Boolean =
        dispatcher.onEngine {
            if (engine == 0L || session == 0L) return@onEngine false
            RimeNative.nativeGrammarSessionLoaded(session, modelPath)
        }

    // ---- lifetime ----------------------------------------------------------

    /** Releases the session and the engine. Idempotent. */
    override fun close() {
        val released =
            dispatcher.onEngineBlocking {
                if (engine != 0L) {
                    destroyWordSession()
                    if (session != 0L) RimeNative.nativeSessionDestroy(engine, session)
                    session = 0L
                    RimeNative.nativeEngineDestroy(engine)
                    engine = 0L
                    RimeNative.nativeSpellingReset()
                }
                explicitlySetOptions = linkedSetOf()
                snapshotRecoveryRequired = false
            }
        released.exceptionOrNull()?.let { failure ->
            mutableStatus.value = RimeStatus.Failed(failure.message ?: "engine shutdown failed")
        }
        if (released.isSuccess) mutableStatus.value = RimeStatus.Stopped
        mutableSnapshot.value = RimeSnapshot.EMPTY
    }

    // ---- internals (engine thread only) ------------------------------------

    internal fun requireEngine() {
        if (engine == 0L) throw RimeException(RimeException.Kind.NOT_STARTED, "engine not started")
    }

    /** Whether an action may run; a pending snapshot recovery is attempted first. */
    internal fun canAct(): Boolean {
        if (engine == 0L || session == 0L) return false
        if (!snapshotRecoveryRequired) return true
        val recovered = recoverOnEngine() ?: return false
        // The action that follows publishes the next snapshot; a commit the recovery read carried
        // rides along with it instead of being dropped.
        undeliveredCommit = recovered.commitText + undeliveredCommit
        return true
    }

    /**
     * Clears the composition a failed snapshot read left behind and proves the engine readable
     * again. The composition is given up (the editor drops its marked text with the returned
     * state); refusing every later action instead would leave input dead until a restart. Null
     * when the engine is still unreadable or would not let go of the composition.
     */
    internal fun recoverOnEngine(): RimeSnapshot? {
        if (engine == 0L || session == 0L) return null
        RimeNative.nativeClearComposition(engine, session)
        snapshotRecoveryRequired = false
        val state = snapshotOnEngine() ?: return null
        if (state.isComposing || state.input.isNotEmpty()) {
            snapshotRecoveryRequired = true
            return null
        }
        return state
    }

    private fun requireReadableSession() {
        requireEngine()
        if (session == 0L) throw RimeException(RimeException.Kind.NO_SESSION, "no live session")
        if (!canAct()) {
            throw RimeException(
                RimeException.Kind.SNAPSHOT_UNAVAILABLE,
                "engine state must be recovered before further input",
            )
        }
    }

    internal fun requireMaintainable(operation: String) {
        requireEngine()
        if (configuration.compiledDir != null) {
            throw RimeException(
                RimeException.Kind.OPERATION_FAILED,
                "$operation is disabled for a prebuilt engine; run deployment in the deployment process",
            )
        }
    }

    /** The state after an action; an unreadable one is recovered at once (the composition is cleared). */
    internal fun actionSnapshot(): RimeSnapshot =
        snapshotOnEngine()
            ?: recoverOnEngine()
            ?: throw RimeException(
                RimeException.Kind.SNAPSHOT_UNAVAILABLE,
                "engine state unreadable after the action",
            )

    private fun snapshotOrLastTrusted(): RimeSnapshot {
        val fresh = snapshotOnEngine()
        if (fresh != null) return fresh
        val last = mutableSnapshot.value
        return if (last.commitText.isEmpty()) last else last.copy(commitText = "")
    }

    private fun changePageOnEngine(backward: Boolean): RimeSnapshot? {
        if (!canAct()) return null
        RimeNative.nativeChangePage(engine, session, backward)
        return snapshotOnEngine()
    }

    internal fun snapshotOnEngine(): RimeSnapshot? {
        if (engine == 0L || session == 0L || snapshotRecoveryRequired) return null
        val ints = IntArray(RimeNative.SNAPSHOT_INT_COUNT)
        val read = RimeNative.nativeSnapshot(engine, session, ints, spellingDemand?.invoke() == true)
        val fields =
            if (failedSnapshotReads > 0) {
                failedSnapshotReads--
                null
            } else {
                read
            }
        if (fields == null) {
            if (learningSuppressed) {
                // Snapshot construction may have consumed the commit already. Drop its pending
                // learning before recovery clears the context, and stop private composition.
                learningSuppressionIntact = false
                runCatching {
                    RimeNative.nativeProcessKey(
                        engine,
                        session,
                        RimeKey.BACKSPACE.keycode,
                        RimeKey.BACKSPACE.modifiers,
                    )
                }
            }
            snapshotRecoveryRequired = true
            return null
        }
        val decoded = RimeNative.decodeSnapshot(ints, fields)
        // Roll back learning before returning across the cancellable dispatcher boundary. The
        // caller may disappear, but onLost must retain only the text, never its pending learning.
        if (learningSuppressed && decoded.commitText.isNotEmpty()) {
            val rolledBack =
                !decoded.isComposing &&
                    runCatching {
                        !RimeNative.nativeProcessKey(
                            engine,
                            session,
                            RimeKey.BACKSPACE.keycode,
                            RimeKey.BACKSPACE.modifiers,
                        )
                    }.getOrDefault(false)
            learningSuppressionIntact = learningSuppressionIntact && rolledBack
        }
        mutableSnapshot.value = decoded.copy(commitText = "")
        if (undeliveredCommit.isEmpty()) return decoded
        val withEarlier = decoded.copy(commitText = undeliveredCommit + decoded.commitText)
        undeliveredCommit = ""
        return withEarlier
    }

    /**
     * A commit librime already made whose caller was cancelled before receiving it; the next
     * snapshot carries it, so the text still reaches the editor. Engine thread only.
     */
    private var undeliveredCommit: String = ""

    private fun keepUndelivered(text: String?) {
        if (text.isNullOrEmpty()) return
        dispatcher.post { undeliveredCommit += text }
    }

    internal fun readCurrentSchemaId(): String {
        if (engine == 0L || session == 0L) return ""
        return RimeNative.nativeCurrentSchema(engine, session) ?: ""
    }

    internal fun deployState(): RimeDeployState =
        RimeDeployState.entries.getOrElse(RimeNative.nativeLastDeployState(engine)) {
            RimeDeployState.NONE
        }

    internal fun capturedOptions(): List<Pair<String, Boolean>> =
        (explicitlySetOptions + PRESERVED_OPTIONS).sorted().map { name ->
            name to RimeNative.nativeGetOption(engine, session, name)
        }

    internal fun releaseSession() {
        destroyWordSession()
        if (session != 0L) RimeNative.nativeSessionDestroy(engine, session)
        session = 0L
    }

    // ---- construction ------------------------------------------------------

    private fun start(): RimeEngine {
        mutableStatus.value = RimeStatus.Starting
        val failure = RimeNative.loadFailure()
        if (failure != null) {
            mutableStatus.value = RimeStatus.Failed(failure)
            throw RimeException(RimeException.Kind.INITIALIZATION_FAILED, failure)
        }
        if (!configuration.sharedDataDir.isDirectory) {
            fail("shared data directory missing: ${configuration.sharedDataDir.path}")
        }
        configuration.compiledDir?.let { validatePrebuiltDirectory(it, ::fail) }
        if (!configuration.userDataDir.exists() && !configuration.userDataDir.mkdirs()) {
            fail("cannot create user data directory: ${configuration.userDataDir.path}")
        }
        // A full deployment (no compiled directory) may compile for minutes; opening a prebuilt
        // build must be quick. A handle that arrives after the wait gave up is destroyed on the
        // engine thread, so the process-wide engine slot is free for the next attempt.
        val created = createEngineHandle(dispatcher, configuration)
        created.exceptionOrNull()?.let { cause ->
            fail(cause.message ?: "engine creation failed")
        }
        val handle = created.getOrThrow()
        if (handle == 0L) fail("engine creation returned no handle")
        engine = handle
        val ready =
            dispatcher
                .onEngineBlocking { RimeNative.nativeEngineReady(handle) }
                .getOrDefault(false)
        if (!ready) {
            val detail =
                dispatcher
                    .onEngineBlocking { RimeNative.nativeEngineLastError(handle) }
                    .getOrNull() ?: "engine not ready"
            dispatcher.onEngineBlocking { RimeNative.nativeEngineDestroy(handle) }
            engine = 0L
            fail(detail)
        }
        val createdSession =
            dispatcher
                .onEngineBlocking { RimeNative.nativeSessionCreate(handle) }
                .getOrDefault(0L)
        if (createdSession == 0L) {
            dispatcher.onEngineBlocking { RimeNative.nativeEngineDestroy(handle) }
            engine = 0L
            fail("cannot create session")
        }
        session = createdSession
        val schema =
            dispatcher
                .onEngineBlocking {
                    RimeNative.nativeCurrentSchema(handle, createdSession)
                }.getOrNull()
                .orEmpty()
        if (configuration.compiledDir != null && (schema.isEmpty() || schema == ".default")) {
            releaseAll()
            fail("prebuilt default schema unavailable; republish from the app")
        }
        val version =
            dispatcher
                .onEngineBlocking { RimeNative.nativeEngineVersion(handle) }
                .getOrNull() ?: "-"
        mutableStatus.value = RimeStatus.Ready(version)
        return this
    }

    private fun fail(detail: String): Nothing {
        mutableStatus.value = RimeStatus.Failed(detail)
        throw RimeException(RimeException.Kind.INITIALIZATION_FAILED, detail)
    }

    private fun releaseAll() {
        dispatcher.onEngineBlocking {
            if (engine != 0L) {
                destroyWordSession()
                if (session != 0L) RimeNative.nativeSessionDestroy(engine, session)
                session = 0L
                RimeNative.nativeEngineDestroy(engine)
                engine = 0L
            }
        }
    }

    companion object {
        /** librime app_name; the "rime." prefix makes librime recycle old log files. */
        const val DEFAULT_APP_NAME = "rime.songyan"

        /** librime version the synced bridge sources are built against (from native/VERSIONS.env). */
        const val EXPECTED_RIME_VERSION = BuildConfig.LIBRIME_VERSION

        /** librime's `UserDb::snapshot_extension()`. */
        const val USERDB_SNAPSHOT_EXTENSION = ".userdb.txt"

        /** librime's user-dictionary directory extension (`UserDb::Component::extension()`). */
        const val USERDB_EXTENSION = ".userdb"

        /** Components the built-in Basic package requires from the static plugins. */
        val REQUIRED_COMPONENTS =
            listOf(
                "lua_processor",
                "lua_translator",
                "lua_filter",
                "lua_segmentor",
                "simplifier",
                "grammar",
                "t9_processor",
                "predictor",
                "predict_translator",
            )

        internal const val ASCII_MODE = "ascii_mode"

        /** The switch librime-predict's `predictor` consults on every context update. */
        private const val PREDICTION_OPTION = "prediction"
        private const val TABLE_TRANSLATOR_PREFIX = "table_translator@"

        /** Options preserved across a maintenance pass, as the shared keyboard expects. */
        private val PRESERVED_OPTIONS =
            listOf(
                "ascii_mode",
                "full_shape",
                "simplification",
                "ascii_punct",
            )

        /**
         * Deploys into [userDataDir] and opens the engine, used by the deployment
         * process. Compiling dictionaries can be slow; call off the main thread.
         */
        suspend fun create(
            sharedDataDir: File,
            userDataDir: File,
            appName: String = DEFAULT_APP_NAME,
            appVersion: String? = null,
        ): RimeEngine =
            open(
                Configuration(
                    sharedDataDir = sharedDataDir,
                    userDataDir = userDataDir,
                    appName = appName,
                    appVersion = appVersion,
                ),
            )

        /**
         * Opens an already deployed build without any maintenance, used by the IME
         * process. A missing or mismatched build fails here instead of deploying.
         */
        suspend fun createPrebuilt(
            sharedDataDir: File,
            userDataDir: File,
            appName: String = DEFAULT_APP_NAME,
            appVersion: String? = null,
            compiledDir: File,
        ): RimeEngine =
            open(
                Configuration(
                    sharedDataDir = sharedDataDir,
                    userDataDir = userDataDir,
                    appName = appName,
                    appVersion = appVersion,
                    compiledDir = compiledDir,
                ),
            )

        /** Every engine of the process runs on the one shared engine thread. */
        private fun open(configuration: Configuration): RimeEngine =
            RimeEngine(RimeDispatcher.shared(), configuration).start()
    }
}
