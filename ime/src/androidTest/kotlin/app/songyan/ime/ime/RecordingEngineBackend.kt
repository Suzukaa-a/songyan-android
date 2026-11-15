package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor

/**
 * A stand-in engine for tests about the keyboard surface, not about input: it records every key
 * it receives. With [composes] each key becomes a one-candidate composition ("去") that a pick
 * commits; without it every answer is the idle snapshot.
 */
internal class RecordingEngineBackend(
    private val composes: Boolean = true,
) : EngineBackend {
    val keys = mutableListOf<String>()
    private var snapshot = EngineSnapshot()
    override val kind = EngineBackendKind.DEMO_SIMULATOR
    override val inputUnavailableReason: String? = null
    override val availableSchemas = emptyList<KeyboardSchemaDescriptor>()
    override val statusSummary = "test"
    override var spellingDemand: (() -> Boolean)? = null

    override suspend fun setup(
        sharedDirectory: String,
        userDirectory: String,
    ) = Unit

    override suspend fun selectSchema(schemaID: String) = Unit

    override suspend fun processKey(keyValue: String): EngineSnapshot {
        keys += keyValue
        if (!composes) return EngineSnapshot()
        snapshot =
            EngineSnapshot(
                rawInput = keyValue,
                preedit = keyValue,
                isComposing = true,
                candidates = listOf(Candidate(0, "去")),
                highlightedIndex = 0,
            )
        return snapshot
    }

    override suspend fun cancelComposition(): EngineSnapshot {
        snapshot = EngineSnapshot()
        return snapshot
    }

    override suspend fun selectCandidate(index: Int): EngineSnapshot {
        snapshot = EngineSnapshot()
        return if (composes) snapshot.copy(committed = "去") else snapshot
    }

    override suspend fun nextPage() = snapshot

    override suspend fun previousPage() = snapshot

    override fun currentSnapshot() = snapshot

    override suspend fun shutdown() = Unit
}
