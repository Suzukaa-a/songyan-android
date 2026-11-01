package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.AmbiguityApplyOutcome
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendError
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.engine.ReadingCandidate

/**
 * Scripted engine for the ambiguity session contract: a fixed report, scripted apply/restore
 * outcomes, call recording, and a spelling cache that follows the production rule (decoded when a
 * snapshot is produced while the demand gate is open, then only mapped by [currentSnapshot]).
 */
class ScriptedAmbiguityEngine : EngineBackend {
    override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
    override val inputUnavailableReason: String? = null
    override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
    override var spellingDemand: (() -> Boolean)? = null
    override val statusSummary: String get() = "scripted"

    var snapshot =
        EngineSnapshot(
            candidates = listOf(Candidate(0, "你")),
            preedit = "ug",
            rawInput = "ug",
            isComposing = true,
        )
    var report: AmbiguitySpellingsReport? = null
    var applyOutcome = AmbiguityApplyOutcome.APPLIED
    var restoreOutcome = AmbiguityApplyOutcome.APPLIED
    var postApplyInput = "9084"
    var rejectSchemaIDs = emptySet<String>()
    var failSchemaSelect = false
    var commitSucceeds = true

    var ambiguityCalls = 0
        private set
    var readingCalls = 0
        private set
    var schemaSelectCount = 0
        private set
    val applied = mutableListOf<Triple<String, IntRange, String>>()
    val restored = mutableListOf<List<Any>>()

    override suspend fun setup(
        sharedDirectory: String,
        userDirectory: String,
    ) = Unit

    override suspend fun validateSchema(schemaID: String) {
        if (schemaID in rejectSchemaIDs) throw EngineBackendError.UnsupportedSchema("spy rejects $schemaID")
    }

    override suspend fun selectSchema(schemaID: String) {
        if (failSchemaSelect) throw EngineBackendError.OperationFailed("spy selectSchema failed $schemaID")
        schemaSelectCount += 1
    }

    override suspend fun processKey(keyValue: String): EngineSnapshot = snapshot

    override suspend fun selectCandidate(index: Int): EngineSnapshot {
        // Production contract: a confirmed candidate ends the composition. A failed commit leaves it composing.
        if (!commitSucceeds) return snapshot
        snapshot = EngineSnapshot(committed = "你")
        return snapshot
    }

    override suspend fun nextPage(): EngineSnapshot = snapshot

    override suspend fun previousPage(): EngineSnapshot = snapshot

    override fun currentSnapshot(): EngineSnapshot = snapshot.copy(committed = "")

    override suspend fun shutdown() = Unit

    override suspend fun ambiguitySpellings(): AmbiguitySpellingsReport? {
        ambiguityCalls += 1
        return report
    }

    override suspend fun readCandidatesWithReadings(
        fromIndex: Int,
        count: Int,
    ): List<ReadingCandidate> {
        readingCalls += 1
        return listOf(ReadingCandidate(fromIndex, "你"))
    }

    override suspend fun applyAmbiguitySpelling(
        expectedInput: String,
        segment: IntRange,
        option: String,
    ): AmbiguityApplyOutcome {
        applied += Triple(expectedInput, segment, option)
        if (applyOutcome == AmbiguityApplyOutcome.APPLIED) {
            snapshot = snapshot.copy(preedit = postApplyInput, rawInput = postApplyInput)
        }
        return applyOutcome
    }

    override suspend fun restoreAmbiguitySpelling(
        expectedInput: String,
        segment: IntRange,
        expectedOption: String,
        reducedCode: String,
    ): AmbiguityApplyOutcome {
        restored += listOf(expectedInput, segment, expectedOption, reducedCode)
        if (restoreOutcome == AmbiguityApplyOutcome.APPLIED) {
            snapshot = snapshot.copy(preedit = "ug", rawInput = "ug")
        }
        return restoreOutcome
    }
}
