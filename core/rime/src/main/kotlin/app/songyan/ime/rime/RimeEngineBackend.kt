package app.songyan.ime.rime

import app.songyan.ime.keyboard.display.CompositionDisplayPolicy
import app.songyan.ime.keyboard.display.SchemaDisplayCapability
import app.songyan.ime.keyboard.engine.AmbiguityApplyOutcome
import app.songyan.ime.keyboard.engine.AmbiguityLockMode
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.CompositionDisplayMode
import app.songyan.ime.keyboard.engine.EncodingAdapterRegistry
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendError
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.keyboard.engine.EngineOptionState
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.engine.ReadingCandidate
import app.songyan.ime.keyboard.engine.TextSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Adapts [RimeEngine] to the keyboard's consumer contract.
 *
 * The backend translates the front end's logical keys into librime keysyms, keeps
 * the last trusted snapshot for non-suspending readers, and maps engine failures
 * onto [EngineBackendError] so callers never confuse "unavailable" with an empty
 * engine answer.
 */
class RimeEngineBackend(
    private val engine: RimeEngine,
    private val optionSupport: OptionSupport? = null,
) : EngineBackend {
    /** Where the schema switches are read from and the user's choices are kept. */
    class OptionSupport(
        /** Scopes the saved choices to the installed scheme source. */
        val resourceID: String,
        val sharedDataDir: java.io.File,
        val compiledDir: java.io.File,
        val preferences: EngineOptionPreferences,
    )

    private val optionDescriptors = java.util.concurrent.ConcurrentHashMap<String, List<EngineOptionDescriptor>>()

    private val mutableSnapshot = MutableStateFlow(EngineSnapshot())
    private var schemas: List<KeyboardSchemaDescriptor> = emptyList()

    override val kind: EngineBackendKind = EngineBackendKind.RIME

    override val inputUnavailableReason: String?
        get() = (engine.status.value as? RimeStatus.Failed)?.reason

    override var spellingDemand: (() -> Boolean)? = null
        set(value) {
            field = value
            engine.spellingDemand = value
        }

    override suspend fun setup(
        sharedDirectory: String,
        userDirectory: String,
    ) {
        // The engine is created with its directories by RimeEngine.createPrebuilt;
        // the backend only needs the schema list the running engine reports.
        refreshSchemas()
        mutableSnapshot.value = readEngineSnapshot()
    }

    override suspend fun selectSchema(schemaID: String) {
        if (!engine.selectSchema(schemaID)) {
            throw EngineBackendError.UnsupportedSchema(schemaID)
        }
        refreshSchemas()
        mutableSnapshot.value = readEngineSnapshot()
    }

    override var encodingAdapters: List<KeyboardResourceSet.EncodingAdapter> = emptyList()
        private set

    override suspend fun validateSchema(schemaID: String) {
        if (schemas.none { it.id == schemaID } && encodingAdapters.none { it.schemaID == schemaID }) {
            throw EngineBackendError.UnsupportedSchema(schemaID)
        }
    }

    override val availableSchemas: List<KeyboardSchemaDescriptor>
        get() = schemas

    private val alphabetCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The schema's own `speller/alphabet`, read from the deployed config. A generated compact
     * companion is not in the schema list, so the listed descriptors cannot answer for it; the
     * keyboard only sends a merged key's exact token when this alphabet declares it.
     */
    override suspend fun spellerAlphabet(schemaID: String): String? {
        if (schemaID.isEmpty()) return null
        alphabetCache[schemaID]?.let { return it }
        val value = catchingNonCancellation { engine.schemaValue(schemaID, "speller/alphabet") }.getOrNull()
        if (value.isNullOrEmpty()) return null
        alphabetCache[schemaID] = value
        return value
    }

    override suspend fun processKey(keyValue: String): EngineSnapshot {
        val key =
            RimeKey.fromKeyValue(keyValue)
                ?: throw EngineBackendError.OperationFailed("not an engine key: $keyValue")
        val result =
            try {
                engine.processKey(key)
            } catch (e: RimeException) {
                throw e.asBackendError()
            }
        val next =
            if (keyValue == EngineBackend.SPACE_KEY) commitFullyConfirmedZhuyin(result.snapshot) else result.snapshot
        val snapshot = publish(next)
        if (result.consumed || next.isComposing) return snapshot
        if (snapshot.committed.isNotEmpty()) {
            // A capital the speller refused: the editor confirmed the composition and handed the
            // letter back, so it is typed right after what was confirmed.
            return if (keyValue.length == 1 && keyValue[0] in 'A'..'Z') {
                snapshot.copy(committed = snapshot.committed + keyValue)
            } else {
                snapshot
            }
        }
        // librime left the key to the keyboard (a space or return with nothing composed, a key the
        // schema does not read): it is typed as itself, exactly once.
        val literal = literalForUnconsumed(keyValue) ?: return snapshot
        return snapshot.copy(committed = literal)
    }

    /** What an unconsumed key types; control keys other than Return type nothing. */
    private fun literalForUnconsumed(keyValue: String): String? =
        when {
            keyValue == EngineBackend.SPACE_KEY || keyValue == EngineBackend.RETURN_KEY -> keyValue
            keyValue.length == 1 && keyValue[0].code >= 0x20 && keyValue[0].code != 0x7F -> keyValue
            else -> null
        }

    private suspend fun commitFullyConfirmedZhuyin(snapshot: RimeSnapshot): RimeSnapshot {
        if (snapshot.isAsciiMode ||
            snapshot.commitText.isNotEmpty() ||
            InputLayoutPolicy.familyOf(snapshot.schemaId, alphabetCache[snapshot.schemaId].orEmpty()) !=
            InputSchemeFamily.ZHUYIN ||
            !snapshot.mapped(spelling = "", hidesNumericTransportCode = false).isFullyConfirmedComposition
        ) {
            return snapshot
        }
        // Another Space reopens a confirmed selection in fluency_editor.
        return try {
            engine.commitComposition().snapshot
        } catch (error: RimeException) {
            throw error.asBackendError()
        }
    }

    override suspend fun cancelComposition(): EngineSnapshot =
        try {
            publish(engine.cancelComposition())
        } catch (e: RimeException) {
            throw e.asBackendError()
        }

    override suspend fun selectCandidate(index: Int): EngineSnapshot {
        val snapshot =
            try {
                engine.selectCandidate(index)
            } catch (e: RimeException) {
                throw e.asBackendError()
            } ?: throw EngineBackendError.OperationFailed("candidate refused: $index")
        return publish(commitFullyConfirmedZhuyin(snapshot))
    }

    override suspend fun selectCandidateAbsolute(index: Int): EngineSnapshot {
        val snapshot =
            try {
                engine.selectCandidateAbsolute(index)
            } catch (e: RimeException) {
                throw e.asBackendError()
            } ?: throw EngineBackendError.OperationFailed("candidate refused: $index")
        return publish(commitFullyConfirmedZhuyin(snapshot))
    }

    override suspend fun deleteCandidate(index: Int): EngineSnapshot? {
        val snapshot =
            try {
                engine.deleteCandidate(index)
            } catch (e: RimeException) {
                throw e.asBackendError()
            } ?: return null
        return publish(snapshot)
    }

    override suspend fun wordCompletions(
        prefix: String,
        schemaID: String,
        limit: Int,
    ): List<String>? =
        try {
            engine.wordCompletions(prefix, schemaID, limit)
        } catch (e: RimeException) {
            throw e.asBackendError()
        }

    override suspend fun wordCompletionSchemas(sourceSchemaID: String): List<String> =
        try {
            engine.wordCompletionSchemas(sourceSchemaID)
        } catch (e: RimeException) {
            throw e.asBackendError()
        }

    override fun releaseWordCompletions() {
        engine.releaseWordCompletionsAsync()
    }

    override val supportsReadOnlyBrowsing: Boolean = true

    override suspend fun readCandidates(
        fromIndex: Int,
        count: Int,
    ): List<Candidate> {
        val read =
            try {
                engine.readCandidates(fromIndex, count)
            } catch (e: RimeException) {
                throw e.asBackendError()
            } ?: throw EngineBackendError.OperationFailed("candidate read refused: $fromIndex")
        val size = mutableSnapshot.value.pageSize
        return read.map { it.asCandidate(size) }
    }

    override suspend fun readCandidatesWithReadings(
        fromIndex: Int,
        count: Int,
    ): List<ReadingCandidate>? {
        val read =
            try {
                engine.readCandidates(fromIndex, count, includeReadings = true)
            } catch (e: RimeException) {
                throw e.asBackendError()
            } ?: return null
        return read.map {
            ReadingCandidate(
                index = it.absoluteIndex,
                text = it.text,
                readingPaths = it.readingPaths.takeIf { _ -> it.readingsComplete },
                readingsComplete = it.readingsComplete,
                comment = it.comment,
                selectKey = it.selectKey,
                selectLabel = it.selectLabel,
            )
        }
    }

    /** A read candidate numbered like a snapshot candidate: its position inside its own page. */
    private fun RimeReadCandidate.asCandidate(pageSize: Int): Candidate =
        Candidate(
            index = if (pageSize > 0) absoluteIndex % pageSize else absoluteIndex,
            text = text,
            comment = comment,
            selectKey = selectKey,
            selectLabel = selectLabel,
        )

    override suspend fun nextPage(): EngineSnapshot {
        val snapshot =
            try {
                engine.pageForward()
            } catch (e: RimeException) {
                throw e.asBackendError()
            } ?: throw EngineBackendError.OperationFailed("page forward refused")
        return publish(snapshot)
    }

    override suspend fun previousPage(): EngineSnapshot {
        val snapshot =
            try {
                engine.pageBackward()
            } catch (e: RimeException) {
                throw e.asBackendError()
            } ?: throw EngineBackendError.OperationFailed("page backward refused")
        return publish(snapshot)
    }

    override fun currentSnapshot(): EngineSnapshot = mutableSnapshot.value

    override suspend fun setOption(
        name: String,
        value: Boolean,
    ): EngineSnapshot {
        // A refused write is an error, never a successful snapshot of the old state: reporting
        // success would show the switch as applied while the engine still holds the old value.
        val descriptor = activeOptionDescriptors().firstOrNull { it.name == name }
        if (descriptor?.choiceGroup?.isNotEmpty() == true) {
            if (!value) throw EngineBackendError.OperationFailed("choose a group member instead of clearing it")
            applyChoice(descriptor)
        } else if (!engine.setOption(name, value)) {
            throw EngineBackendError.OperationFailed("option was refused: " + name)
        }
        val snapshot = publish(engine.readSnapshot())
        val support = optionSupport ?: return snapshot
        if (descriptor == null) return snapshot
        try {
            withContext(Dispatchers.IO) { support.preferences.write(descriptor, value) }
        } catch (e: java.io.IOException) {
            // The switch is live but would be lost at the next start: say so instead of pretending it was kept.
            throw EngineBackendError.OperationFailed("option applied but not saved: " + name)
        }
        return snapshot
    }

    /**
     * The active schema's switches with their live values. A saved choice is the source of truth: it is
     * pushed into the engine here, so every schema selection, start and settings-app change ends up
     * applied by the same call. A switch the user never chose keeps whatever the engine holds.
     */
    override suspend fun optionStates(): List<EngineOptionState> {
        val support = optionSupport ?: return emptyList()
        val descriptors = activeOptionDescriptors()
        if (descriptors.isEmpty()) return emptyList()
        val saved = withContext(Dispatchers.IO) { descriptors.map { support.preferences.read(it) } }
        for ((index, descriptor) in descriptors.withIndex()) {
            val wanted = saved[index] ?: continue
            if (descriptor.choiceGroup.isNotEmpty()) {
                if (wanted) applyChoice(descriptor)
            } else if (wanted != engine.option(descriptor.name)) {
                engine.setOption(descriptor.name, wanted)
            }
        }
        for (group in descriptors.filter { it.choiceGroup.isNotEmpty() }.distinctBy { it.choiceGroup }) {
            val selected = group.choiceGroup.firstOrNull { engine.option(it) } ?: group.choiceGroup.first()
            applyChoice(descriptors.first { it.name == selected })
        }
        return descriptors.map { EngineOptionState(it, engine.option(it.name)) }
    }

    private suspend fun applyChoice(descriptor: EngineOptionDescriptor) {
        // Match librime key_binder radio_select_option, including schema order and unchanged values.
        for (member in descriptor.choiceGroup) {
            val selected = member == descriptor.name
            if (engine.option(member) != selected && !engine.setOption(member, selected)) {
                throw EngineBackendError.OperationFailed("option was refused: " + member)
            }
        }
    }

    /** Switches of the running schema; a generated compact companion answers with its source schema's. */
    private suspend fun activeOptionDescriptors(): List<EngineOptionDescriptor> {
        val support = optionSupport ?: return emptyList()
        val running = mutableSnapshot.value.schemaID
        if (running.isEmpty()) return emptyList()
        val source = encodingAdapters.firstOrNull { it.schemaID == running }?.sourceSchemaID ?: running
        optionDescriptors[source]?.let { return it }
        val read =
            withContext(Dispatchers.IO) {
                SchemaOptionCapabilities.read(support.resourceID, source, support.compiledDir, support.sharedDataDir)
            }
        optionDescriptors[source] = read
        return read
    }

    override suspend fun shutdown() {
        runCatching { engine.close() }
    }

    override val statusSummary: String
        get() =
            when (val status = engine.status.value) {
                is RimeStatus.Ready -> "librime ${status.engineVersion}"
                is RimeStatus.Failed -> "engine failed: ${status.reason}"
                RimeStatus.Starting -> "starting"
                RimeStatus.Stopped -> "stopped"
            }

    /**
     * librime has no switch that stops user-dictionary writes, but it never writes a commit at once:
     * `Memory::OnCommit` opens a transaction that is written by the next translation or unhandled
     * key, and dropped by an unhandled BackSpace within three seconds (`Memory::OnUnhandledKey`,
     * librime's "forget the word I just committed"). While learning is suppressed every commit is
     * followed, in the same engine turn, by such a BackSpace, so nothing reaches the user dictionary.
     * Whether the BackSpace really went unhandled is checked every time; a schema that takes it, or a
     * commit that leaves a composition behind, ends the protection instead of learning silently.
     */
    override val supportsLearningSuppression: Boolean = true

    /** The `prediction` switch this backend turned off, to restore when learning resumes. */
    private var predictionWasOn = false

    override val learningSuppressionIntact: Boolean
        get() = engine.learningSuppressionIntact

    override suspend fun setLearningSuppressed(suppressed: Boolean): Boolean {
        if (!suppressed) {
            engine.setLearningSuppressed(false)
            if (predictionWasOn) {
                predictionWasOn = false
                catchingNonCancellation { engine.setOption(PREDICTION_OPTION, true) }
            }
            return true
        }
        engine.setLearningSuppressed(false)
        // The probe below must reach an idle engine: during a composition BackSpace edits it.
        if (mutableSnapshot.value.isComposing) return false
        // librime-predict turns every commit into a prediction composition, which would take the
        // BackSpace that drops the commit's learning.
        if (!predictionWasOn && catchingNonCancellation { engine.option(PREDICTION_OPTION) }.getOrDefault(false)) {
            if (!catchingNonCancellation { engine.setOption(PREDICTION_OPTION, false) }.getOrDefault(false)) {
                return false
            }
            predictionWasOn = true
        }
        // Writes what an earlier editor committed, so the probe cannot drop that legitimate learning.
        val flush = catchingNonCancellation { engine.sendUnhandledKey(RimeKey(RimeKeyCode.VOID_SYMBOL)) }.getOrNull()
        if (flush == null || flush.consumed) {
            flush?.let { publish(it.snapshot) }
            return false
        }
        // Proof that an idle BackSpace stays unhandled in this schema, which is what drops a commit.
        val probe = catchingNonCancellation { engine.sendUnhandledKey(RimeKey.BACKSPACE) }.getOrNull()
        if (probe == null || probe.consumed) {
            probe?.let { publish(it.snapshot) }
            return false
        }
        engine.setLearningSuppressed(true)
        return true
    }

    /**
     * Read-only ambiguity report for the tail segment. The bridge returns null when it
     * cannot prove the answer; that is passed through instead of being turned into an
     * empty (and therefore "not ambiguous") report.
     */
    override suspend fun ambiguitySpellings(): AmbiguitySpellingsReport? {
        val report =
            try {
                engine.ambiguitySpellings()
            } catch (e: RimeException) {
                throw e.asBackendError()
            } ?: return null
        val lockMode =
            when (report.lockMode) {
                RimeLockMode.NONE -> AmbiguityLockMode.NONE
                RimeLockMode.TOKEN_ALIAS -> AmbiguityLockMode.TOKEN_ALIAS
                RimeLockMode.DIRECT_TOKEN -> AmbiguityLockMode.DIRECT_TOKEN
                RimeLockMode.PREFIX_TOKEN -> AmbiguityLockMode.PREFIX_TOKEN
            }
        return AmbiguitySpellingsReport(
            revisionInput = report.revisionInput,
            segment = report.segmentStart until report.segmentEnd,
            options = report.lockable,
            allSpellings = report.spellings,
            lockMode = lockMode,
            isComplete = report.isComplete,
            prefixOptions = report.prefixOptions,
            optionSemantics = report.optionSemantics,
        )
    }

    override suspend fun applyAmbiguitySpelling(
        expectedInput: String,
        segment: IntRange,
        option: String,
    ): AmbiguityApplyOutcome =
        try {
            engine
                .applyAmbiguitySpelling(expectedInput, segment.first, segment.last + 1, option)
                .toOutcome()
                .also { republishAfterRewrite(it) }
        } catch (e: RimeException) {
            throw e.asBackendError()
        }

    override suspend fun restoreAmbiguitySpelling(
        expectedInput: String,
        segment: IntRange,
        expectedOption: String,
        reducedCode: String,
    ): AmbiguityApplyOutcome =
        try {
            engine
                .restoreAmbiguitySpelling(expectedInput, segment.first, segment.last + 1, expectedOption, reducedCode)
                .toOutcome()
                .also { republishAfterRewrite(it) }
        } catch (e: RimeException) {
            throw e.asBackendError()
        }

    /**
     * A rewrite changes the composition inside the engine, and the session reads its post-state from
     * [currentSnapshot]: without a fresh read it would keep showing (and committing against) the
     * input from before the rewrite. A refused rewrite left the engine as it was, so nothing is read.
     */
    private suspend fun republishAfterRewrite(outcome: AmbiguityApplyOutcome) {
        if (outcome == AmbiguityApplyOutcome.APPLIED || outcome == AmbiguityApplyOutcome.FAILED_ROLLBACK_UNVERIFIED) {
            readEngineSnapshot()
        }
    }

    private fun RimeAmbiguityApplyResult.toOutcome(): AmbiguityApplyOutcome =
        when (this) {
            RimeAmbiguityApplyResult.APPLIED -> AmbiguityApplyOutcome.APPLIED
            RimeAmbiguityApplyResult.STALE -> AmbiguityApplyOutcome.STALE
            RimeAmbiguityApplyResult.UNSUPPORTED -> AmbiguityApplyOutcome.UNSUPPORTED
            RimeAmbiguityApplyResult.INVALID -> AmbiguityApplyOutcome.INVALID
            RimeAmbiguityApplyResult.FAILED -> AmbiguityApplyOutcome.FAILED
            RimeAmbiguityApplyResult.FAILED_ROLLBACK_UNVERIFIED -> AmbiguityApplyOutcome.FAILED_ROLLBACK_UNVERIFIED
        }

    private suspend fun refreshSchemas() {
        // The declared alphabet is part of what the list must carry: layout availability reads it to
        // place an id the language table cannot name. [spellerAlphabet] caches each value, and this
        // runs at setup and on a schema switch only, so the key path never asks for it.
        schemas =
            SchemaDescriptors.withDeclaredAlphabets(
                engine.schemas().map { schema -> KeyboardSchemaDescriptor(id = schema.id, name = schema.name) },
            ) { schemaID -> spellerAlphabet(schemaID) }
    }

    private suspend fun readEngineSnapshot(): EngineSnapshot = publish(engine.readSnapshot())

    private suspend fun publish(snapshot: RimeSnapshot): EngineSnapshot {
        val mapped = snapshot.toEngineSnapshot(engine)
        mutableSnapshot.value = mapped
        return mapped
    }

    private fun RimeException.asBackendError(): EngineBackendError =
        when (kind) {
            RimeException.Kind.NOT_STARTED,
            RimeException.Kind.NO_SESSION,
            -> EngineBackendError.NotReady()

            RimeException.Kind.INITIALIZATION_FAILED,
            RimeException.Kind.SNAPSHOT_UNAVAILABLE,
            -> EngineBackendError.SetupFailed(message ?: "engine unavailable")

            RimeException.Kind.OPERATION_FAILED ->
                EngineBackendError.OperationFailed(
                    message ?: "engine operation failed",
                )
        }

    /**
     * The active schema's display context, read once per schema: the bridge opens the schema's
     * dictionary to answer, which is far too slow for every key.
     */
    private suspend fun displayContext(schemaID: String): RimeDisplayContext? {
        if (displayContextSchema == schemaID) return displayContextValue
        val context =
            catchingNonCancellation { engine.displayContextJson() }
                .getOrNull()
                ?.let { RimeDisplayContext.fromJson(it) }
        displayContextSchema = schemaID
        displayContextValue = context
        return context
    }

    private var displayContextSchema: String? = null
    private var displayContextValue: RimeDisplayContext? = null

    /**
     * Maps a bridge snapshot, resolving the composition display in the same engine turn as the
     * candidate page so the two always describe one state.
     *
     * A schema that declares a `keyboard_adapter` block is marked even without a display request,
     * so a declared 9-key `canonical` transport code is never echoed. The capability is consulted for
     * a decode only when the front end asked for spelling (a compact layout) and the context
     * authorizes it; a compact layout that gets no display value instead receives the highlighted
     * candidate's proven reading, never a guess.
     */
    private suspend fun RimeSnapshot.toEngineSnapshot(backend: RimeEngine): EngineSnapshot {
        var syllables = emptyList<String>()
        var spelling = ""
        var capability: SchemaDisplayCapability? = null
        var capabilityPresent = false
        var declaredMode = CompositionDisplayMode.NATIVE
        val demanded = isComposing && spellingDemand?.invoke() == true
        if (isComposing) {
            val context = displayContext(schemaId)
            capabilityPresent = context?.capabilityPresent ?: false
            declaredMode = context?.capability?.mode ?: CompositionDisplayMode.NATIVE
            if (demanded && context?.authorizedMode() != null) {
                capability = context.capability
                // Read by the engine in the same call as this snapshot (RimeEngine.spellingDemand).
                highlightedSyllables?.let {
                    syllables = it.syllables
                    spelling = it.syllables.joinToString(it.separator)
                }
            }
        }
        val base = mapped(spelling, declaredMode == CompositionDisplayMode.CANONICAL)
        val resolved = CompositionDisplayPolicy.applied(base, syllables, capability, capabilityPresent).first
        if (!demanded || resolved.highlightedDisplay.isNotEmpty()) return resolved
        val reading = verifiedReading(backend, resolved)
        return if (reading == null) resolved else resolved.copy(highlightedReading = reading)
    }

    /**
     * The highlighted candidate's reading when the engine proves both where it sits in the preedit
     * (the highlighted syllables) and that it has exactly that one complete pronunciation.
     */
    private suspend fun verifiedReading(
        backend: RimeEngine,
        snapshot: EngineSnapshot,
    ): String? {
        val local = snapshot.highlightedIndex
        if (local !in snapshot.candidates.indices) return null
        val absolute = if (snapshot.pageSize > 0) snapshot.pageIndex * snapshot.pageSize + local else local
        val highlight = catchingNonCancellation { backend.highlightedSyllables() }.getOrNull() ?: return null
        val readings = catchingNonCancellation { backend.candidateSyllables(absolute) }.getOrNull() ?: return null
        if (!readings.isComplete || readings.paths.size != 1 || readings.paths[0] != highlight.syllables) return null
        return highlight.syllables.joinToString(highlight.separator)
    }

    override suspend fun verifiedHighlightedReading(
        absoluteIndex: Int,
        expectedText: String,
        physicalLayoutEligible: Boolean,
    ): String? {
        if (!physicalLayoutEligible || expectedText.isEmpty() || absoluteIndex < 0) return null
        val current = mutableSnapshot.value
        val local = if (current.pageSize > 0) absoluteIndex - current.pageIndex * current.pageSize else absoluteIndex
        if (current.highlightedIndex != local || current.candidates.getOrNull(local)?.text != expectedText) return null
        return verifiedReading(engine, current)
    }

    private fun RimeSnapshot.mapped(
        spelling: String,
        hidesNumericTransportCode: Boolean,
    ): EngineSnapshot =
        EngineSnapshot(
            schemaID = schemaId,
            // The bridge exposes the consumed commit text, not librime's pending commit
            // preview, so the preview stays empty instead of duplicating `committed`
            // under a second name.
            isAsciiMode = isAsciiMode,
            isFullShape = isFullShape,
            isAsciiPunct = isAsciiPunct,
            preeditSelection = selectionIn(preedit, preeditSelStart, preeditSelEnd),
            committed = commitText,
            candidates =
                candidates.mapIndexed { index, candidate ->
                    Candidate(
                        index = index,
                        text = candidate.text,
                        comment = candidate.comment.orEmpty(),
                        selectKey = candidate.selectKey,
                        selectLabel = candidate.selectLabel,
                    )
                },
            preedit = preedit,
            rawInput = input,
            isComposing = isComposing,
            highlightedIndex = if (candidates.isEmpty()) -1 else highlightedIndex,
            pageIndex = pageIndex,
            pageSize = pageSize,
            isLastPage = isLastPage,
            preeditSelStart = preeditSelStart,
            preeditSelEnd = preeditSelEnd,
            preeditCaretByte = preeditCursor,
            highlightedSpelling = spelling,
            hidesNumericTransportCode = hidesNumericTransportCode,
            isPrediction = !isComposing && input.isEmpty() && candidates.isNotEmpty(),
        )

    private fun selectionIn(
        text: String,
        startByte: Int,
        endByte: Int,
    ): TextSelection {
        val start = utf8ByteOffsetToUtf16(text, startByte)
        val end = utf8ByteOffsetToUtf16(text, endByte)
        return TextSelection(start = start, length = (end - start).coerceAtLeast(0))
    }

    /** Converts a byte offset in [text]'s UTF-8 encoding to a UTF-16 index. */
    private fun utf8ByteOffsetToUtf16(
        text: String,
        byteOffset: Int,
    ): Int {
        if (byteOffset <= 0) return 0
        var bytes = 0
        var utf16 = 0
        while (utf16 < text.length && bytes < byteOffset) {
            val codePoint = text.codePointAt(utf16)
            val byteLength =
                when {
                    codePoint < 0x80 -> 1
                    codePoint < 0x800 -> 2
                    codePoint < 0x10000 -> 3
                    else -> 4
                }
            bytes += byteLength
            utf16 += Character.charCount(codePoint)
        }
        return utf16.coerceAtMost(text.length)
    }

    companion object {
        /** Convenience factory used by the app's published-engine factory. */
        suspend fun create(
            sharedDataDir: java.io.File,
            userDataDir: java.io.File,
            compiledDir: java.io.File,
            appName: String,
            appVersion: String,
            /** Keeps the user's schema switch choices; null means switches are neither shown nor kept. */
            optionPreferences: EngineOptionPreferences? = null,
            /** Scopes the saved switch choices to the installed scheme source. */
            optionResourceID: String = "",
        ): RimeEngineBackend {
            val engine =
                RimeEngine.createPrebuilt(
                    sharedDataDir = sharedDataDir,
                    userDataDir = userDataDir,
                    appName = appName,
                    appVersion = appVersion,
                    compiledDir = compiledDir,
                )
            val support =
                optionPreferences?.takeIf { optionResourceID.isNotEmpty() }?.let {
                    OptionSupport(optionResourceID, sharedDataDir, compiledDir, it)
                }
            val backend = RimeEngineBackend(engine, support)
            engine.createSession()
            backend.setup(sharedDataDir.absolutePath, userDataDir.absolutePath)
            backend.encodingAdapters =
                withContext(Dispatchers.IO) {
                    EncodingAdapterRegistry.read(compiledDir, backend.availableSchemas.map { it.id }.toSet())
                }
            return backend
        }
    }
}

/** The `librime-predict` switch; off while learning is suppressed. */
private const val PREDICTION_OPTION = "prediction"

/** [runCatching] that lets coroutine cancellation through. */
private inline fun <T> catchingNonCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Result.failure(error)
    }
