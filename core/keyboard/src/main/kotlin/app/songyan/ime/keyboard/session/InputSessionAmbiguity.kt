package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.AmbiguityApplyOutcome
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.ReadingCandidate
import app.songyan.ime.keyboard.engine.ReadingRailRead
import app.songyan.ime.keyboard.engine.ReadingRailRequest
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

/**
 * [ambiguitySpellingsImpl] and [readCandidatesWithReadingsImpl] in one engine read, with their
 * gates: a spelling failure is reported and read as unprovable, a reading failure stays a failure.
 */
internal suspend fun InputSession.readReadingRailImpl(request: ReadingRailRequest): ReadingRailRead {
    if (!compactSpellingLayout) {
        return ReadingRailRead(
            spellings = if (request.spellings) Result.success(null) else null,
            readings = if (request.readCount > 0) Result.success(null) else null,
            page = if (request.pageCount > 0) Result.success(null) else null,
        )
    }
    val spellings = request.spellings && inputAvailable()
    val read = engine.readReadingRail(request.copy(spellings = spellings))
    val report =
        read.spellings?.getOrElse { error ->
            onError?.invoke(error as? Exception ?: throw error)
            null
        }
    return ReadingRailRead(
        spellings = if (request.spellings) Result.success(report) else null,
        readings = read.readings,
        page = read.page,
    )
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
