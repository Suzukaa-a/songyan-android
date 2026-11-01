package app.songyan.ime.rime

/**
 * Direct JNI surface over the synced `rb_*` / `rls_*` C bridge.
 *
 * Every function here is a single native call that must happen on the engine's one
 * dispatcher thread; nothing in this file is safe to call from the main thread. All
 * strings cross the boundary once, and lazily allocated native memory is released
 * inside the same call, so no librime-owned pointer is retained by Kotlin.
 */
internal object RimeNative {
    private var loaded = false
    private var loadError: String? = null

    /** Name of the single native library built by native/android (all deps static). */
    private const val LIBRARY = "songyan_rime"

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        loadError?.let { return false }
        try {
            System.loadLibrary(LIBRARY)
            loaded = true
            return true
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message ?: "cannot load lib$LIBRARY.so"
            return false
        }
    }

    /** Why loading failed, or null when the library is available. */
    fun loadFailure(): String? {
        ensureLoaded()
        return if (loaded) null else loadError
    }

    // ---- engine lifetime ---------------------------------------------------

    external fun nativeEngineCreate(
        sharedDataDir: String,
        userDataDir: String,
        appName: String,
        appVersion: String?,
    ): Long

    external fun nativeEngineCreatePrebuilt(
        sharedDataDir: String,
        userDataDir: String,
        appName: String,
        appVersion: String?,
        compiledDir: String,
    ): Long

    external fun nativeEngineDestroy(engine: Long)

    external fun nativeEngineReady(engine: Long): Boolean

    external fun nativeEngineLastError(engine: Long): String?

    external fun nativeEngineVersion(engine: Long): String?

    external fun nativeComponentAvailable(name: String): Boolean

    external fun nativeLastDeployState(engine: Long): Int

    // ---- sessions ----------------------------------------------------------

    external fun nativeSessionCreate(engine: Long): Long

    external fun nativeSessionDestroy(
        engine: Long,
        session: Long,
    )

    // ---- input -------------------------------------------------------------

    external fun nativeProcessKey(
        engine: Long,
        session: Long,
        keycode: Int,
        mask: Int,
    ): Boolean

    external fun nativeCommitComposition(
        engine: Long,
        session: Long,
    ): Boolean

    external fun nativeSelectCandidateOnPage(
        engine: Long,
        session: Long,
        index: Int,
    ): Boolean

    external fun nativeDeleteCandidate(
        engine: Long,
        session: Long,
        index: Int,
    ): Boolean

    external fun nativeReadCandidates(
        session: Long,
        fromIndex: Int,
        count: Int,
    ): Array<String?>?

    /** Replaces the raw input of [session] without key processing (word-completion lookups only). */
    external fun nativeSetInput(
        engine: Long,
        session: Long,
        input: String,
    ): Boolean

    external fun nativeSelectCandidate(
        engine: Long,
        session: Long,
        index: Int,
    ): Boolean

    external fun nativeChangePage(
        engine: Long,
        session: Long,
        backward: Boolean,
    ): Boolean

    external fun nativeClearComposition(
        engine: Long,
        session: Long,
    ): Boolean

    external fun nativeSimulateKeySequence(
        engine: Long,
        session: Long,
        sequence: String,
    ): Boolean

    external fun nativeSetOption(
        engine: Long,
        session: Long,
        option: String,
        value: Boolean,
    ): Boolean

    external fun nativeGetOption(
        engine: Long,
        session: Long,
        option: String,
    ): Boolean

    // ---- schema ------------------------------------------------------------

    external fun nativeSelectSchema(
        engine: Long,
        session: Long,
        schemaId: String,
    ): Boolean

    external fun nativeCurrentSchema(
        engine: Long,
        session: Long,
    ): String?

    // ---- state -------------------------------------------------------------

    /**
     * Fills [ints] (see [SNAPSHOT_INT_COUNT]) and returns the text fields, or null when the
     * state cannot be read. [withSyllables] folds the highlighted-syllables read into the same
     * call so it always describes the candidate this snapshot highlights.
     */
    external fun nativeSnapshot(
        engine: Long,
        session: Long,
        ints: IntArray,
        withSyllables: Boolean,
    ): Array<String?>?

    /** Decodes [bytes] with the bridge's strict UTF-8 reader; for tests only. */
    external fun nativeUtf8Probe(bytes: ByteArray): String?

    // ---- maintenance -------------------------------------------------------

    external fun nativeSyncUserData(engine: Long): Boolean

    external fun nativeStartMaintenance(
        engine: Long,
        full: Boolean,
    ): Boolean

    external fun nativeJoinMaintenance(engine: Long)

    // ---- schema configuration ---------------------------------------------

    external fun nativeSchemaValues(
        schemaId: String,
        keys: Array<String>,
    ): Array<String?>?

    external fun nativeSchemaStringList(
        schemaId: String,
        key: String,
    ): Array<String?>?

    external fun nativeSchemaList(): Array<String?>?

    // ---- spelling / ambiguity / grammar ------------------------------------

    external fun nativeSpellingReset()

    external fun nativeSpellingDecodeCount(): Long

    external fun nativeHighlightedSpelling(session: Long): String?

    external fun nativeHighlightedSyllablesJson(session: Long): String?

    external fun nativeDisplayContextJson(session: Long): String?

    external fun nativeRewriteVerificationJson(session: Long): String?

    external fun nativeContextMetadataJson(session: Long): String?

    external fun nativeSyllableDisplayMapJson(session: Long): String?

    external fun nativeCandidateSyllablesJson(
        session: Long,
        index: Int,
    ): String?

    external fun nativeAmbiguitySpellingsJson(
        session: Long,
        maxItems: Int,
    ): String?

    external fun nativeApplyAmbiguitySpelling(
        session: Long,
        expectedInput: String,
        segmentStart: Int,
        segmentEnd: Int,
        spelling: String,
    ): Int

    external fun nativeRestoreAmbiguitySpelling(
        session: Long,
        expectedInput: String,
        segmentStart: Int,
        segmentEnd: Int,
        expectedSpelling: String,
        reducedCode: String,
    ): Int

    external fun nativeGrammarDiagnosticsReset(enabled: Boolean)

    external fun nativeGrammarDiagnosticsJson(): String?

    external fun nativeGrammarSessionLoaded(
        session: Long,
        modelPath: String,
    ): Boolean

    // ---- levers (learning backup) ------------------------------------------

    external fun nativeLeversAvailable(): Boolean

    external fun nativeLeversPrepareInstallation(): Boolean

    external fun nativeUserDictList(): Array<String?>?

    external fun nativeBackupUserDict(name: String): Boolean

    external fun nativeRestoreUserDict(file: String): Boolean

    external fun nativeUserDataSyncDir(): String?

    external fun nativeDeploySchema(file: String): Boolean

    /**
     * Decodes the flat array produced by rime_jni.cc `nativeSchemaValues`.
     *
     * The bridge emits a `(key, value)` pair **only for keys that exist**, in request
     * order, so the array is sparse: it must be decoded by the returned key. Reading it
     * by request position would attribute a present key's value to an earlier missing
     * key. A trailing unpaired element (a truncated array) is ignored rather than read
     * as a value, and null entries are skipped.
     */
    fun decodeSchemaValues(flat: Array<String?>): Map<String, String> {
        val values = LinkedHashMap<String, String>()
        var index = 0
        while (index + 1 < flat.size) {
            val key = flat[index]
            val value = flat[index + 1]
            if (key != null && value != null) values[key] = value
            index += 2
        }
        return values
    }

    /** Length of the primitive array [nativeSnapshot] fills. */
    const val SNAPSHOT_INT_COUNT = 13

    private const val STRING_HEADER = 7

    /**
     * Decodes the arrays produced by rime_jni.cc `SnapshotToArray`.
     *
     * [ints]: isComposing, isAsciiMode, isFullShape, isAsciiPunct, preeditCursor,
     * preeditSelStart, preeditSelEnd, pageNo, pageSize, isLastPage, highlightedIndex,
     * labelCount, candidateCount. [strings]: schemaId, schemaName, input, preedit,
     * commitText, selectKeys, highlighted-syllables JSON, the labels, then `(text, comment)` per
     * candidate. Undeclared select keys/labels stay null so the front end never fabricates
     * the default 1..9 keys. A string array shorter than the declared counts is a bridge bug:
     * the missing candidates are dropped, never invented.
     */
    fun decodeSnapshot(
        ints: IntArray,
        strings: Array<String?>,
    ): RimeSnapshot {
        val labelCount = ints[11].coerceIn(0, maxOf(0, strings.size - STRING_HEADER))
        val candidatesIndex = STRING_HEADER + labelCount
        val candidateCount = ints[12].coerceIn(0, maxOf(0, (strings.size - candidatesIndex) / 2))
        val selectKeys = strings.getOrNull(5)?.map { it.toString() }.orEmpty()
        val selectLabels = ArrayList<String>(labelCount)
        for (i in 0 until labelCount) {
            selectLabels.add(strings[STRING_HEADER + i].orEmpty())
        }
        val candidates = ArrayList<RimeCandidate>(candidateCount)
        for (i in 0 until candidateCount) {
            candidates.add(
                RimeCandidate(
                    text = strings[candidatesIndex + i * 2].orEmpty(),
                    comment = strings[candidatesIndex + i * 2 + 1],
                    selectKey = selectKeys.getOrNull(i),
                    selectLabel = selectLabels.getOrNull(i),
                ),
            )
        }
        return RimeSnapshot(
            schemaId = strings.getOrNull(0).orEmpty(),
            schemaName = strings.getOrNull(1).orEmpty(),
            isComposing = ints[0] != 0,
            isAsciiMode = ints[1] != 0,
            isFullShape = ints[2] != 0,
            isAsciiPunct = ints[3] != 0,
            input = strings.getOrNull(2).orEmpty(),
            preedit = strings.getOrNull(3).orEmpty(),
            preeditCursor = ints[4],
            preeditSelStart = ints[5],
            preeditSelEnd = ints[6],
            pageIndex = ints[7],
            pageSize = ints[8],
            isLastPage = ints[9] != 0,
            highlightedIndex = ints[10],
            candidates = candidates,
            commitText = strings.getOrNull(4).orEmpty(),
            highlightedSyllables = strings.getOrNull(6)?.let { RimeHighlightedSyllables.fromJson(it) },
        )
    }
}
