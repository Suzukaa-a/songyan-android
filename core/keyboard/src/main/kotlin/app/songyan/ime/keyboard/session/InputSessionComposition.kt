package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendError
import app.songyan.ime.keyboard.engine.EngineSnapshot
import kotlinx.coroutines.CancellationException

// Composition confirmation, candidate deletion/confirmation and discard for [InputSession], split out to
// keep the session file small. Bodies are moved verbatim; the public entry points stay on the class.

internal suspend fun InputSession.commitCompositionImpl(): Boolean {
    if (!inputAvailable()) return false
    if (!snapshot.isComposing) return dismissPrediction()
    val target = confirmationTarget?.invoke()
    if (target != null) {
        // A reading filter decides what is committed: its first match, never the engine highlight.
        // Choosing a part of the composition leaves the rest, which cancels the confirming key.
        return target != InputSession.NO_TARGET &&
            confirmCandidateAbsoluteImpl(target) == InputSession.CandidateConfirmationResult.COMPLETED
    }
    return try {
        val delivered: Boolean
        if (snapshot.candidates.isEmpty()) {
            val raw = snapshot.rawInput
            val parts = confirmedTextAndRawTail()
            val letters = exactTokenLetters()
            var next = engine.processKey(EngineBackend.RETURN_KEY)
            if (letters.isNotEmpty()) {
                if (next.committed == raw) {
                    next = next.copy(committed = raw.map { letters[it] ?: it }.joinToString(""))
                } else if (parts != null && next.committed == parts.first + parts.second) {
                    next =
                        next.copy(
                            committed = parts.first + parts.second.map { letters[it] ?: it }.joinToString(""),
                        )
                }
            }
            delivered = apply(next)
        } else {
            val index = snapshot.highlightedIndex
            delivered =
                if (index >= 0 && snapshot.candidates.any { it.index == index }) {
                    apply(engine.selectCandidate(index))
                } else {
                    apply(engine.selectCandidate(0))
                }
        }
        if (snapshot.isComposing) {
            onError?.invoke(EngineBackendError.OperationFailed("composition not finished"))
            return false
        }
        delivered
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onError?.invoke(error)
        false
    }
}

/**
 * The converted text before the highlighted segment and the raw input after it, read from the
 * preedit: everything before [EngineSnapshot.preeditSelStart] is confirmed text, and the rest
 * counts as raw only when it is literally the tail of the raw input.
 */
internal fun InputSession.confirmedTextAndRawTail(): Pair<String, String>? {
    val bytes = snapshot.preedit.toByteArray(Charsets.UTF_8)
    val start = snapshot.preeditSelStart
    if (start <= 0 || start >= bytes.size) return null
    val confirmed = String(bytes, 0, start, Charsets.UTF_8)
    val tail = String(bytes, start, bytes.size - start, Charsets.UTF_8)
    if (!snapshot.rawInput.endsWith(tail)) return null
    if (tail.length >= snapshot.rawInput.length) return null
    return confirmed to tail
}

/**
 * Exact token -> letter, when the running schema's alphabet takes the layout's exact tokens (a
 * generated adapter) and the engine is reading Chinese input.
 */
internal suspend fun InputSession.exactTokenLetters(): Map<Char, Char> {
    val fold = currentLayout.compactFold
    if (mode != InputMode.CHINESE || snapshot.isAsciiMode || fold == null || fold.exactTokens.isEmpty()) {
        return emptyMap()
    }
    val alphabet = engine.spellerAlphabet(snapshot.schemaID) ?: return emptyMap()
    // exactTokens is letter -> token; the engine reads the token as that letter.
    return fold.exactTokens.entries
        .filter { (_, token) -> alphabet.contains(token) }
        .associate { (letter, token) -> token to letter }
}

internal suspend fun InputSession.deleteCandidateImpl(
    absoluteIndex: Int,
    text: String,
): Boolean {
    if (!inputAvailable() || absoluteIndex < 0 || text.isEmpty()) return false
    return try {
        val result = engine.deleteCandidate(absoluteIndex) ?: return false
        apply(result)
        val size = snapshot.pageSize
        val stillThere =
            if (supportsReadOnlyBrowsing && size > 0) {
                engine.readCandidates(absoluteIndex, 1).firstOrNull()?.text == text
            } else {
                result.candidates.any { it.text == text }
            }
        !stillThere
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onError?.invoke(error)
        false
    }
}

internal suspend fun InputSession.confirmCandidateAbsoluteImpl(index: Int): InputSession.CandidateConfirmationResult {
    if (!inputAvailable()) return InputSession.CandidateConfirmationResult.FAILED
    if (!snapshot.isComposing && !snapshot.isPrediction) return InputSession.CandidateConfirmationResult.FAILED
    if (onDispatchCommit == null && onCommitted == null) return InputSession.CandidateConfirmationResult.FAILED
    return try {
        val result = engine.selectCandidateAbsolute(index)
        val delivered = apply(result)
        when {
            result.isComposing -> InputSession.CandidateConfirmationResult.PARTIAL
            delivered -> InputSession.CandidateConfirmationResult.COMPLETED
            else -> InputSession.CandidateConfirmationResult.FAILED
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onError?.invoke(error)
        InputSession.CandidateConfirmationResult.FAILED
    }
}

internal suspend fun InputSession.discardCompositionImpl(): Boolean {
    if (retired) return false
    if (!snapshot.isComposing && inputUnavailableReason == null) {
        // A fresh session (new editor) has not observed the composition the engine may still
        // own from the previous editor; clearing the host text without clearing it would leave
        // the stale composition behind.
        val engineState = catchingNonCancellation { engine.currentSnapshot() }.getOrNull()
        if (engineState == null || !engineState.isComposing) return true
    }
    return try {
        val updated = engine.cancelComposition().copy(committed = "")
        apply(updated)
        if (snapshot.isComposing) {
            onError?.invoke(EngineBackendError.OperationFailed("composition still active after cancel"))
            return false
        }
        true
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onError?.invoke(error)
        false
    }
}
