package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.AmbiguityApplyOutcome
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.ReadingCandidate
import kotlinx.coroutines.CancellationException

// Read-only candidate browsing and compact-spelling ambiguity operations for [InputSession], split out to
// keep the session file small. Bodies are moved verbatim; the public entry points stay on the class.

internal suspend fun InputSession.readCandidatePageImpl(pageIndex: Int): List<Candidate> {
    val size = snapshot.pageSize
    if (!supportsReadOnlyBrowsing || size <= 0 || pageIndex < 0) return emptyList()
    return engine.readCandidates(fromIndex = pageIndex * size, count = size)
}

internal suspend fun InputSession.ambiguitySpellingsImpl(): AmbiguitySpellingsReport? {
    if (!compactSpellingLayout || !inputAvailable()) return null
    return try {
        engine.ambiguitySpellings()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onError?.invoke(error)
        null
    }
}

internal suspend fun InputSession.readCandidatesWithReadingsImpl(
    fromIndex: Int,
    count: Int,
): List<ReadingCandidate>? {
    if (!compactSpellingLayout) return null
    return engine.readCandidatesWithReadings(fromIndex, count)
}

internal suspend fun InputSession.applyAmbiguityOptionImpl(
    option: String,
    expectedInput: String,
    segment: IntRange,
): AmbiguityApplyOutcome {
    if (!compactSpellingLayout || !inputAvailable()) return AmbiguityApplyOutcome.UNSUPPORTED
    return try {
        val outcome = engine.applyAmbiguitySpelling(expectedInput, segment, option)
        if (outcome == AmbiguityApplyOutcome.APPLIED) apply(engine.currentSnapshot())
        outcome
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onError?.invoke(error)
        AmbiguityApplyOutcome.FAILED
    }
}

internal suspend fun InputSession.restoreAmbiguityImpl(
    expectedInput: String,
    segment: IntRange,
    expectedOption: String,
    reducedCode: String,
): AmbiguityApplyOutcome {
    if (!compactSpellingLayout || !inputAvailable()) return AmbiguityApplyOutcome.UNSUPPORTED
    return try {
        val outcome = engine.restoreAmbiguitySpelling(expectedInput, segment, expectedOption, reducedCode)
        if (outcome == AmbiguityApplyOutcome.APPLIED) apply(engine.currentSnapshot())
        outcome
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        onError?.invoke(error)
        AmbiguityApplyOutcome.FAILED
    }
}
