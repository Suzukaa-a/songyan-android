package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.keyboard.support.ABCSuggestionSlots
import app.songyan.ime.keyboard.support.EnglishSuggestionShaping
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ABC page word guessing: completions of the word being typed come from the current input scheme's
// own English translator (a read-only second engine session), next-word guesses from the built-in
// table. Chinese input never pays for any of it: the probe and the queries only run in English mode.

/** Typing pauses this long before a completion is asked for, so fast typing never queues engine work. */
private const val COMPLETION_DEBOUNCE_MS = 30L

/** A prefix every English dictionary answers; used to see whether the scheme mounts one at all. */
private const val PROBE_PREFIX = "th"

/** Suggestions are an English-mode feature and never appear where the editor asked for none. */
internal fun KeyboardController.englishSuggestionsActive(active: InputSession?): Boolean =
    active != null &&
        active.mode == InputMode.ENGLISH &&
        !policy.suppressesPrediction &&
        !privateDirectInput &&
        panelSearch == null

/**
 * What the suggestion row shows for [snapshot]: the engine's own predictions right after an engine
 * commit (the most recent context), otherwise the completions or next-word guesses. Null when the
 * row stays the toolbar. [EnglishSuggestionRow.fromEngine] rows keep the engine's candidate indices.
 */
internal fun KeyboardController.englishSuggestionRow(
    active: InputSession?,
    snapshot: EngineSnapshot,
): EnglishSuggestionRow? {
    if (!englishSuggestionsActive(active) || snapshot.isComposing) return null
    val slots = ABCSuggestionSlots.enginePredictions(snapshot, EnglishSuggestionShaping.LIMIT)
    if (slots.isNotEmpty()) {
        val cands = slots.map { (index, text) -> Candidate(index = index, text = text) }
        return EnglishSuggestionRow(snapshot.copy(candidates = cands), fromEngine = true)
    }
    if (englishSuggestions.isEmpty()) return null
    val cands = englishSuggestions.mapIndexed { index, text -> Candidate(index = index, text = text) }
    val row =
        EngineSnapshot(
            isPrediction = true,
            candidates = cands,
            pageSize = cands.size,
            isLastPage = true,
        )
    return EnglishSuggestionRow(row, fromEngine = false)
}

internal class EnglishSuggestionRow(
    val snapshot: EngineSnapshot,
    val fromEngine: Boolean,
)

/**
 * Asks the engine which schema (if any) offers English completions for the current source scheme.
 * Probed on English entry only: the probe opens a second engine session that Chinese-only use of the
 * keyboard should never pay for. A failed probe is not evidence of "unsupported": the next entry asks
 * again.
 */
internal fun KeyboardController.probeEnglishSuggestions(active: InputSession) {
    val source = active.selectedSchemaID
    if (source.isEmpty() || englishProbeSource == source) return
    englishProbeSource = source
    val backend = active.engine
    scope.launch {
        val chosen =
            try {
                backend.wordCompletionSchemas(source).firstOrNull { schema ->
                    val words = backend.wordCompletions(PROBE_PREFIX, schema, EnglishSuggestionShaping.SCAN)
                    EnglishSuggestionShaping.englishWords(words.orEmpty()).size >= 2
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (englishProbeSource == source) englishProbeSource = null
                return@launch
            }
        enqueue {
            if (session?.engine !== backend || session?.selectedSchemaID != source) {
                if (englishProbeSource == source) englishProbeSource = null
                return@enqueue
            }
            englishWordSchemaId = chosen
            scheduleEnglishSuggestions()
        }
    }
}

/** A commit reached the editor: the word being typed or the finished word changes. */
internal fun KeyboardController.noteCommitForEnglishWord(text: String) {
    val active = session
    if (!englishSuggestionsActive(active)) {
        resetEnglishWord()
        return
    }
    englishWords.note(text)
    scheduleEnglishSuggestions()
}

/** The caret moved, the document changed or the editor restarted: forget the word and its context. */
internal fun KeyboardController.resetEnglishWord() {
    doubleSpaceArmedAt = DoubleSpacePeriod.NOT_ARMED
    englishDocumentGeneration++
    englishWords.reset()
    englishQueryJob?.cancel()
    englishQueryJob = null
    if (englishSuggestions.isNotEmpty()) {
        englishSuggestions = emptyList()
        render()
    }
}

/** One character was deleted before the caret. */
internal fun KeyboardController.noteBackspaceForEnglishWord() {
    if (!englishSuggestionsActive(session)) return
    englishWords.backspace()
    scheduleEnglishSuggestions()
}

/** Next-word guesses, capitalized when the host armed a capital for the position (sentence start). */
private fun KeyboardController.nextWordSuggestions(active: InputSession): List<String> {
    val previous = englishWords.previous ?: return emptyList()
    val capital = active.shiftState.isActive
    return englishNextWords.predictions(previous, EnglishSuggestionShaping.LIMIT).map {
        if (capital) EnglishSuggestionShaping.capitalizeFirst(it) else it
    }
}

/** Recomputes what the row offers after the word or its context changed. */
internal fun KeyboardController.scheduleEnglishSuggestions() {
    englishQueryJob?.cancel()
    englishQueryJob = null
    val active = session.takeIf { ready }
    if (!englishSuggestionsActive(active) || active == null) {
        setEnglishSuggestions(emptyList())
        return
    }
    val word = englishWords.word
    if (word.isEmpty()) {
        setEnglishSuggestions(nextWordSuggestions(active))
        return
    }
    val schema = englishWordSchemaId
    if (schema == null) {
        setEnglishSuggestions(emptyList())
        return
    }
    val backend = active.engine
    val generation = englishDocumentGeneration
    // The previous row stays until the new one is ready: one swap, never a blank in between.
    englishQueryJob =
        scope.launch {
            delay(COMPLETION_DEBOUNCE_MS)
            val words =
                try {
                    backend.wordCompletions(word.lowercase(), schema, EnglishSuggestionShaping.SCAN)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    null
                }
            enqueue {
                if (session?.engine !== backend ||
                    englishDocumentGeneration != generation ||
                    englishWords.word != word
                ) {
                    return@enqueue
                }
                val shaped =
                    EnglishSuggestionShaping.shape(
                        EnglishSuggestionShaping.englishWords(words.orEmpty()),
                        word,
                    )
                setEnglishSuggestions(shaped)
            }
        }
}

private fun KeyboardController.setEnglishSuggestions(value: List<String>) {
    if (value == englishSuggestions) return
    englishSuggestions = value
    render()
}

/**
 * Takes the suggestion the user tapped. A completion replaces the word being typed (and adds a space);
 * a next-word guess is inserted as is. Engine predictions never come here: they go through the
 * candidate path, which selects them in the engine.
 */
internal suspend fun KeyboardController.selectEnglishSuggestion(text: String) {
    val active = session.takeIf { ready } ?: return
    if (text !in englishSuggestions || !englishSuggestionsActive(active)) return
    val typed = englishWords.word
    if (typed.isEmpty()) {
        active.insertLiteral("$text ")
        return
    }
    val context = callbacks.contextBeforeCursor(typed.length + 1)
    if (context == null || !context.endsWith(typed)) {
        resetEnglishWord()
        return
    }
    callbacks.deleteBefore(typed.length)
    englishWords.reset()
    active.insertLiteral("$text ")
}
