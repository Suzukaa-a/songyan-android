package app.songyan.ime.keyboard

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor

/**
 * A configurable in-memory engine for the session tests: it mirrors the parts of librime the
 * session depends on (key accumulation, candidate list, prediction after a commit) so the real
 * production functions in InputSession are exercised, not a mock of the session itself.
 */
open class StubEngine(
    private val vocabulary: Map<String, List<String>> = DEFAULT_VOCABULARY,
) : EngineBackend {
    override val kind: EngineBackendKind = EngineBackendKind.DEMO_SIMULATOR
    override var inputUnavailableReason: String? = null
    override var availableSchemas: List<KeyboardSchemaDescriptor> = emptyList()
    override var spellingDemand: (() -> Boolean)? = null

    var schemaID: String = "stub"
    var asciiMode: Boolean = false
    var escapes: Int = 0
    var current = EngineSnapshot()

    /** Text committed by the last operation, delivered through [EngineSnapshot.committed]. */
    private var committed = ""
    private var input = ""

    override suspend fun setup(
        sharedDirectory: String,
        userDirectory: String,
    ) = Unit

    override suspend fun selectSchema(schemaID: String) {
        this.schemaID = schemaID
        input = ""
        current = produce()
    }

    override suspend fun processKey(keyValue: String): EngineSnapshot {
        committed = ""
        when (keyValue) {
            EngineBackend.BACKSPACE_KEY -> if (input.isNotEmpty()) input = input.dropLast(1)
            EngineBackend.ESC_KEY -> {
                escapes += 1
                input = ""
                predictions = emptyList()
            }
            EngineBackend.SPACE_KEY, EngineBackend.RETURN_KEY -> {
                if (input.isNotEmpty()) {
                    val target = vocabulary.entries.firstOrNull { it.key.startsWith(input) }
                    if (target != null) commit(target.value.first()) else commit(input)
                } else {
                    committed = if (keyValue == EngineBackend.SPACE_KEY) " " else "\n"
                }
            }
            else -> {
                predictions = emptyList()
                input += keyValue
            }
        }
        current = produce()
        return current
    }

    override suspend fun selectCandidate(index: Int): EngineSnapshot {
        committed = ""
        val matches = matches()
        if (predictions.isNotEmpty()) {
            commit(predictions[index])
        } else {
            val match = matches.getOrNull(index) ?: throw IllegalStateException("no candidate $index")
            commit(match)
        }
        current = produce()
        return current
    }

    override suspend fun nextPage(): EngineSnapshot = current

    override suspend fun previousPage(): EngineSnapshot = current

    override fun currentSnapshot(): EngineSnapshot = current

    override suspend fun shutdown() = Unit

    override val statusSummary: String get() = "stub"

    /** A post-commit prediction list, opened by [openPredictionAfterCommit]. */
    var predictionsAfterCommit: Map<String, List<String>> = emptyMap()
    private var predictions: List<String> = emptyList()

    private fun commit(text: String) {
        committed = text
        input = ""
        predictions = predictionsAfterCommit[text] ?: emptyList()
    }

    private fun matches(): List<String> {
        if (input.isEmpty()) return emptyList()
        val exact = vocabulary.entries.filter { it.key.startsWith(input) }.map { it.value.first() }
        return if (exact.isEmpty()) listOf(input) else exact
    }

    private fun produce(): EngineSnapshot {
        val committedText = committed
        committed = ""
        if (predictions.isNotEmpty()) {
            return EngineSnapshot(
                schemaID = schemaID,
                committed = committedText,
                candidates = predictions.mapIndexed { index, text -> Candidate(index, text) },
                isPrediction = true,
            )
        }
        val candidates = matches().mapIndexed { index, text -> Candidate(index, text) }
        return EngineSnapshot(
            schemaID = schemaID,
            committed = committedText,
            candidates = candidates,
            preedit = input,
            rawInput = input,
            isComposing = input.isNotEmpty(),
            highlightedIndex = if (candidates.isEmpty()) -1 else 0,
            pageSize = if (candidates.isEmpty()) 0 else candidates.size,
            preeditSelEnd = input.toByteArray(Charsets.UTF_8).size,
            preeditCaretByte = input.toByteArray(Charsets.UTF_8).size,
            isAsciiMode = asciiMode,
        )
    }

    companion object {
        val DEFAULT_VOCABULARY: Map<String, List<String>> =
            mapOf(
                "nihao" to listOf("你好"),
                "zhongguo" to listOf("中国"),
                "nih" to listOf("你"),
                "wo" to listOf("我"),
            )
    }
}
