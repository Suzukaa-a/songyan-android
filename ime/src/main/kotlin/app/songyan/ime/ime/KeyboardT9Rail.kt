package app.songyan.ime.ime

import app.songyan.ime.candidate.AmbiguitySpellingPicker
import app.songyan.ime.keyboard.display.ReadingInputCoverage
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.ReadingCandidate
import app.songyan.ime.keyboard.engine.SpellingRailChoices
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.keyboard.support.T9PunctuationCycle
import app.songyan.ime.layout.CompactSpellingLayout
import kotlinx.coroutines.CancellationException

/**
 * An idle tap on the 9-key first key. The leading rail types the first three quick symbols, so
 * this key cycles the rest: a repeat inside [T9PunctuationCycle.INTERVAL] replaces the symbol the
 * previous tap typed (never appends), and the engine/editor context decides whether that write
 * actually landed.
 */
internal suspend fun KeyboardController.handleT9FirstKey(symbol: String) {
    val active = session
    val revision = active?.compositionRevision ?: 0
    val marksAscii = asciiMarksNow
    val context = callbacks.contextBeforeCursor(T9PunctuationCycle.CONTEXT_LIMIT)
    // The shared cycle is expressed in seconds; uptimeMillis is milliseconds.
    val now = uptimeSeconds()
    val decision = t9FirstKey.tap(false, t9QuickSymbols, marksAscii, context, now, revision)
    if (decision !is T9FirstKeyState.Decision.Cycle) return
    val edit = decision.edit
    if (edit.replacing != null) callbacks.delete()
    callbacks.commit(edit.text, false)
    // The commit was dispatched: some InputConnection implementations (Compose text fields)
    // return false while still accepting the text, so the attempt is the write proof here. The
    // tap-side document check still refuses to replace a symbol that is not at the cursor.
    t9FirstKey.recordAccepted(edit.text, true, uptimeSeconds(), revision)
}

/** Seconds since boot: the unit the punctuation cycle's 0.8 s interval is expressed in. */
internal fun KeyboardController.uptimeSeconds(): Double = android.os.SystemClock.uptimeMillis() / 1000.0

internal fun KeyboardController.rememberReadings(entries: List<ReadingCandidate>) {
    for (entry in entries) {
        if (!entry.readingsComplete) continue
        for (path in entry.readingPaths.orEmpty()) {
            if (path.size != entry.text.length) continue
            entry.text.forEachIndexed { index, character ->
                val known = knownReadings.getOrPut(character) { ArrayList() }
                if (path[index] !in known) known += path[index]
            }
        }
    }
    // Bounded: the map only has to help the next few compositions.
    if (knownReadings.size > MAX_KNOWN_READINGS) knownReadings.clear()
}

/**
 * Whether the readings and spelling choices have anything to say right now: a Chinese composition on
 * the letters page of the nine-key layout (its rail) or of a 14 / 18-key layout (the reading row, or
 * the inline readings of the candidate row).
 */
internal fun KeyboardController.railEligible(active: InputSession?): Boolean =
    active != null &&
        layoutKind.showsReadings &&
        page == null &&
        active.compactSpellingLayout &&
        active.mode == InputMode.CHINESE &&
        active.snapshot.isComposing &&
        !active.snapshot.isAsciiMode

/** The items in display order; the nine-key rail shows the window [railScroll] picks, the row and inline readings all. */
internal fun KeyboardController.railItems(): List<RailItem> = T9RailPolicy.items(railChoices)

internal fun KeyboardController.clearRail() {
    if (!railChoices.isEmpty ||
        readingFilter.revision != -1 ||
        ambiguityPicker.phase != AmbiguitySpellingPicker.Phase.IDLE
    ) {
        railChoices = SpellingRailChoices()
        readingFilter.reset(-1)
        ambiguityPicker.clear()
    }
    railRevision = -1
    railScroll = 0
    candidatesFiltered = false
    pageReadings = emptyList()
}

/** What the typed 9-key input can be read as: the keys each letter is on, for [input]. */
internal fun KeyboardController.readingCoverage(input: String): ReadingInputCoverage? {
    val keys = readingKeyMaps.getOrPut(layoutKind) { CompactSpellingLayout.readingKeyMap(mainDefinition()) }
    return ReadingInputCoverage.of(input, keys, exactTokenLetters())
}

/** Exact-pick token to letter for the running merged-key layout; empty on the phone keypad. */
internal fun KeyboardController.exactTokenLetters(): Map<Char, Char> =
    mergedSwipe
        ?.takeIf { it.useExactTokens }
        ?.fold
        ?.exactTokens
        ?.entries
        ?.associate { (letter, token) -> token to letter }
        .orEmpty()

/** Re-queries the engine-backed rail once per composition revision, never per frame. */
internal fun KeyboardController.scheduleReadingRail(
    active: InputSession?,
    snapshot: EngineSnapshot,
) {
    if (page != null || active == null || !snapshot.isComposing) {
        clearRail()
        return
    }
    if (!railEligible(active)) return
    if (railRevision == active.compositionRevision) return
    enqueue { refreshReadingRail(active) }
}

/** How many readings the first read takes, and the bounded step each engine hop reads. */
private const val READING_PREFETCH = 16
private const val READING_QUANTUM = 8

/** A filtered read keeps scanning until this many matches exist, but never past [READING_SCAN_LIMIT]. */
private const val FILTERED_ROW_TARGET = 16
private const val READING_SCAN_LIMIT = 96

internal suspend fun KeyboardController.refreshReadingRail(active: InputSession) {
    if (!railEligible(active)) return
    val revision = active.compositionRevision
    if (railRevision == revision) return
    railRevision = revision
    val snapshot = active.snapshot
    if (readingFilter.revision != revision) {
        readingFilter.reset(revision)
        railScroll = 0
    }
    readingFilter.setCoverage(readingCoverage(snapshot.rawInput))
    refreshAmbiguity(active, snapshot, revision)
    loadReadings(active, revision)
    // The highlighted candidate may sit past the loaded readings: read its own page too, so its
    // reading (not the commonest syllable of its keys) spells the field.
    val pageStart = if (snapshot.pageSize > 0) snapshot.pageIndex * snapshot.pageSize else 0
    val pageReads =
        if (pageStart >= readingFilter.nextIndex && snapshot.pageSize > 0) {
            active.readCandidatesWithReadings(pageStart, snapshot.pageSize).orEmpty()
        } else {
            emptyList()
        }
    if (active.compositionRevision != revision) return
    rememberReadings(readingFilter.entries + pageReads)
    pageReadings = readingFilter.entries + pageReads
    publishRail(active, revision)
}

/** The spelling report of the tail segment; a lock in force keeps its restore token until the input moves on. */
private suspend fun KeyboardController.refreshAmbiguity(
    active: InputSession,
    snapshot: EngineSnapshot,
    revision: Int,
) {
    val lock = ambiguityPicker.lock
    if (ambiguityPicker.phase == AmbiguitySpellingPicker.Phase.LOCKED && lock != null) {
        if (lock.rewrittenInput != snapshot.rawInput) ambiguityPicker.clear()
        return
    }
    val report = active.ambiguitySpellings()
    if (active.compositionRevision != revision) return
    ambiguityPicker.update(report, revision, isEligible = true)
}

/**
 * Reads real candidates with their proven readings. While nothing is chosen the first few are
 * enough to offer readings; a chosen reading keeps scanning (in bounded hops, so a key typed
 * meanwhile never waits long) until a row of matches exists or the list ends.
 */
private suspend fun KeyboardController.loadReadings(
    active: InputSession,
    revision: Int,
) {
    while (readingFilter.hasMore && !readingFilter.readFailed) {
        val from = readingFilter.nextIndex
        val enough =
            if (readingFilter.isFiltering) {
                readingFilter.matches.size >= FILTERED_ROW_TARGET || from >= READING_SCAN_LIMIT
            } else {
                from >= READING_PREFETCH
            }
        if (enough || active.compositionRevision != revision) return
        val page =
            try {
                active.readCandidatesWithReadings(from, READING_QUANTUM)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                readingFilter.failed()
                return
            } ?: run {
                // The engine cannot prove readings: there is nothing more to ask for.
                readingFilter.append(emptyList(), READING_QUANTUM, revision)
                return
            }
        if (active.compositionRevision != revision) return
        readingFilter.append(page, READING_QUANTUM, revision)
    }
}

/** Resolves the rail against the same evidence taps are validated against, and repaints on a change. */
internal fun KeyboardController.publishRail(
    active: InputSession,
    revision: Int,
) {
    val snapshot = active.snapshot
    val resolved =
        SpellingRailChoices.resolve(
            eligible = railEligible(active),
            input = snapshot.rawInput,
            revision = revision,
            report = ambiguityPicker.report,
            reportRevision = ambiguityPicker.revision,
            lockedOption = ambiguityPicker.lock?.takeIf { ambiguityPicker.canUndoLock }?.chosenOption,
            lockedInput = ambiguityPicker.lock?.rewrittenInput,
            readings = readingFilter,
        )
    val marked = compositionText(active, snapshot)
    if (resolved != railChoices || marked != lastMarked || candidatesFiltered != readingFilterShowing(active)) {
        railChoices = resolved
        railScroll = T9RailPolicy.clampScroll(T9RailPolicy.items(resolved).size, railScroll)
        render()
    }
}

/** The candidates the reading filter leaves, while a reading is chosen for the live composition. */
internal fun KeyboardController.readingFilterShowing(active: InputSession?): Boolean =
    active != null &&
        railEligible(active) &&
        readingFilter.isFiltering &&
        readingFilter.revision == active.compositionRevision

/**
 * What a rail tap does, bound to the evidence the item was drawn from: a spelling must still be one
 * the live report offers, a reading must still extend the chosen readings. Anything stale is dropped.
 */
internal suspend fun KeyboardController.applyRailChoice(
    position: Int,
    label: String,
) {
    val active = session.takeIf { ready } ?: return
    if (!railEligible(active)) return
    val item = T9RailPolicy.items(railChoices).getOrNull(railScroll + position)
    if (item == null || item.label != label || item.selected) return
    val revision = active.compositionRevision
    when (item.kind) {
        RailKind.LOCK -> selectAmbiguityOption(active, item.label)
        RailKind.READING -> {
            if (readingFilter.revision != revision || item.label !in readingFilter.options) return
            readingFilter.select(item.label)
            railScroll = 0
            loadReadings(active, revision)
            publishRail(active, revision)
            render()
        }
        else -> Unit
    }
}

/** Vertical scroll of the rail by whole slots. */
internal fun KeyboardController.scrollRail(steps: Int) {
    val next = T9RailPolicy.clampScroll(railItems().size, railScroll + steps)
    if (next == railScroll) return
    railScroll = next
    render()
}

/** Applies a spelling option the engine proved; the lock is recorded so Delete can restore the original input. */
private suspend fun KeyboardController.selectAmbiguityOption(
    active: InputSession,
    option: String,
) {
    val report = ambiguityPicker.report
    if (report == null ||
        ambiguityPicker.phase != AmbiguitySpellingPicker.Phase.AVAILABLE ||
        !ambiguityPicker.accepts(option, active.compositionRevision, active.snapshot.rawInput)
    ) {
        ambiguityPicker.fail(AmbiguitySpellingPicker.Problem.STALE)
        flashProblem()
        railRevision = -1
        render()
        return
    }
    val originalInput = report.revisionInput
    val segment = report.segment
    val outcome = active.applyAmbiguityOption(option, originalInput, segment)
    if (outcome.isSuccess) {
        val rewritten = active.snapshot.rawInput
        val suffix = originalInput.toByteArray(Charsets.UTF_8).size - (segment.last + 1)
        val end = maxOf(segment.first, rewritten.toByteArray(Charsets.UTF_8).size - suffix)
        ambiguityPicker.markLocked(option, originalInput, segment, rewritten, segment.first until end)
    } else {
        ambiguityPicker.fail(AmbiguitySpellingPicker.problemOf(outcome) ?: AmbiguitySpellingPicker.Problem.FAILED)
        flashProblem()
    }
    railRevision = -1
    render()
}

/**
 * Undoes the lock: the locked segment goes back to the code that was typed. A failure keeps the
 * lock and says so, so the next key never lands after an alias and produces a mixed code.
 */
internal suspend fun KeyboardController.undoAmbiguityLock(active: InputSession): Boolean {
    val lock = ambiguityPicker.lock
    if (lock == null || !railEligible(active)) {
        ambiguityPicker.clear()
        return false
    }
    val original = lock.originalInput.toByteArray(Charsets.UTF_8)
    val reduced =
        String(
            original,
            lock.originalSegment.first,
            lock.originalSegment.last + 1 - lock.originalSegment.first,
            Charsets.UTF_8,
        )
    val outcome = active.restoreAmbiguity(lock.rewrittenInput, lock.rewrittenSegment, lock.chosenOption, reduced)
    railRevision = -1
    if (outcome.isSuccess) {
        ambiguityPicker.markUnlocked()
        render()
        return true
    }
    ambiguityPicker.markRestoreFailed(AmbiguitySpellingPicker.Problem.RESTORE_FAILED)
    flashProblem()
    render()
    return false
}

/** Delete while a rail choice is in force undoes the newest one first. True when it did. */
internal suspend fun KeyboardController.undoRailChoice(active: InputSession): Boolean {
    if (!railEligible(active)) return false
    if (readingFilter.isFiltering && readingFilter.revision == active.compositionRevision) {
        readingFilter.undoSelection()
        railScroll = 0
        publishRail(active, active.compositionRevision)
        render()
        return true
    }
    if (ambiguityPicker.phase == AmbiguitySpellingPicker.Phase.LOCKED) {
        undoAmbiguityLock(active)
        return true
    }
    return false
}

/** Before another code key: a lock in force is restored first, and the key is typed only on success. */
internal suspend fun KeyboardController.restoreLockBeforeInput(active: InputSession): Boolean {
    if (ambiguityPicker.phase != AmbiguitySpellingPicker.Phase.LOCKED) return true
    if (!railEligible(active)) {
        ambiguityPicker.clear()
        return true
    }
    return undoAmbiguityLock(active)
}

private fun KeyboardController.flashProblem() {
    val text =
        when (ambiguityPicker.problem) {
            AmbiguitySpellingPicker.Problem.STALE -> R.string.ime_ambiguity_stale
            AmbiguitySpellingPicker.Problem.UNSUPPORTED -> R.string.ime_ambiguity_unsupported
            AmbiguitySpellingPicker.Problem.INVALID -> R.string.ime_ambiguity_invalid
            AmbiguitySpellingPicker.Problem.ROLLBACK_UNVERIFIED -> R.string.ime_ambiguity_rollback
            AmbiguitySpellingPicker.Problem.RESTORE_FAILED -> R.string.ime_ambiguity_restore_failed
            AmbiguitySpellingPicker.Problem.FAILED, AmbiguitySpellingPicker.Problem.TRUNCATED, null ->
                R.string.ime_ambiguity_failed
        }
    candidates.flash(context.getString(text))
}

/** The candidate a confirming key commits while a reading is chosen: the first match, else nothing. */
internal fun KeyboardController.confirmationTargetOf(active: InputSession): Int? {
    if (!readingFilterShowing(active)) return null
    return readingFilter.matches.firstOrNull()?.index ?: InputSession.NO_TARGET
}
