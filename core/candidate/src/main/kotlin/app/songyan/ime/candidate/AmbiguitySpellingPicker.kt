package app.songyan.ime.candidate

import app.songyan.ime.keyboard.engine.AmbiguityApplyOutcome
import app.songyan.ime.keyboard.engine.AmbiguitySpellingsReport

/**
 * The state model of the composition disambiguation picker. It does not reuse the reading filter.
 *
 * - Consumes only engine-proven [AmbiguitySpellingsReport]s: `options` holds only lockable entries
 *   and a button is never built for anything else; `allSpellings` is diagnostics. The report's
 *   meaning is schema-agnostic (pinyin, double pinyin, shape codes, zhuyin, dialects), so this model
 *   makes no pinyin assumptions.
 * - Binds the report's input, its tail segment's raw byte range and the composition revision, and
 *   rejects a tap whose evidence is stale.
 * - Single lock: records the original whole input, the original reduced span, the chosen option, the
 *   rewritten whole input and the rewritten segment, so undoing restores precisely.
 * - Four explicit phases (idle, available, locked, error); a failure is never silent and never
 *   swallows input.
 *
 * It never touches the engine; engine calls and side effects belong to the keyboard controller.
 */
class AmbiguitySpellingPicker {
    enum class Phase { IDLE, AVAILABLE, LOCKED, ERROR }

    /** Why the picker has nothing to offer or an action failed; the keyboard words it. */
    enum class Problem {
        /** The engine truncated the reachable spellings, so they are not a complete choice set. */
        TRUNCATED,
        STALE,
        UNSUPPORTED,
        INVALID,
        FAILED,
        ROLLBACK_UNVERIFIED,
        RESTORE_FAILED,
    }

    /** The lock record. [rewrittenSegment] is the segment the engine actually wrote (canonical or alias). */
    data class Lock(
        val originalInput: String,
        val originalSegment: IntRange,
        val chosenOption: String,
        val rewrittenInput: String,
        val rewrittenSegment: IntRange,
    )

    var phase: Phase = Phase.IDLE
        private set

    /** The report currently available for display and selection. */
    var report: AmbiguitySpellingsReport? = null
        private set

    /** Every reachable spelling, for diagnostics; never a source of buttons. */
    var allSpellings: List<String> = emptyList()
        private set
    var lock: Lock? = null
        private set

    /** The latest rejection reason, if any. */
    var problem: Problem? = null
        private set

    /** The composition revision bound at the last refresh. */
    var revision: Int = 0
        private set

    /** Whether there is a choice bar to show: two or more lockable options, or a lock to undo. */
    val showsPicker: Boolean get() = phase == Phase.AVAILABLE || phase == Phase.LOCKED

    /** Once locked the undo entry stays reachable, even if the segment is no longer ambiguous. */
    val canUndoLock: Boolean get() = phase == Phase.LOCKED && lock != null

    /**
     * Whether the lock still applies to [rawInput]. The criterion is the rewritten input: only while
     * it equals the real raw input has the opaque token not been confirmed or overwritten, so
     * restoring is allowed.
     */
    fun lockMatches(rawInput: String): Boolean = lock?.rewrittenInput == rawInput

    /**
     * Binding check before applying an option: the composition revision and raw input must match
     * those at refresh time, otherwise the tap is stale and never applied.
     */
    fun accepts(
        option: String,
        revision: Int,
        input: String,
    ): Boolean {
        val current = report ?: return false
        if (phase != Phase.AVAILABLE || option !in current.options) return false
        return this.revision == revision && current.revisionInput == input
    }

    /** Refreshes with the latest report. Clears when [isEligible] is false (not Chinese, not composing, 26 keys). */
    fun update(
        fresh: AmbiguitySpellingsReport?,
        revision: Int,
        isEligible: Boolean,
    ) {
        this.revision = revision
        if (!isEligible) {
            clear()
            this.revision = revision
            return
        }
        // Once locked, a follow-up report may legitimately be missing, a singleton or otherwise not
        // ambiguous any more because the internal canonical/alias did its job. It must never
        // discard the only restore token.
        if (phase == Phase.LOCKED && lock != null) {
            if (fresh != null) allSpellings = fresh.allSpellings
            report = null
            return
        }
        if (fresh == null) {
            clear()
            this.revision = revision
            return
        }
        allSpellings = fresh.allSpellings
        if (!fresh.isComplete) {
            report = null
            lock = null
            phase = Phase.IDLE
            problem = Problem.TRUNCATED
            return
        }
        // Fewer than two lockable options is unambiguous: nothing to show, diagnostics are kept.
        if (!fresh.isAmbiguous || !fresh.isLockable) {
            report = null
            lock = null
            phase = Phase.IDLE
            problem = null
            return
        }
        this.report = fresh
        phase = Phase.AVAILABLE
        problem = null
    }

    /** Records a successful selection, after the apply check succeeded. */
    fun markLocked(
        option: String,
        originalInput: String,
        originalSegment: IntRange,
        rewrittenInput: String,
        rewrittenSegment: IntRange,
    ) {
        lock = Lock(originalInput, originalSegment, option, rewrittenInput, rewrittenSegment)
        report = null
        phase = Phase.LOCKED
        problem = null
    }

    /** The selection failed: enter the error phase and keep no tappable fake button. */
    fun fail(reason: Problem) {
        report = null
        lock = null
        phase = Phase.ERROR
        problem = reason
    }

    /** Undo failed: keep the lock (a later ordinary key must still restore first) and record why. */
    fun markRestoreFailed(reason: Problem) {
        problem = reason
    }

    /** After a successful undo: back to unlocked; the caller then refreshes. */
    fun markUnlocked() {
        lock = null
        report = null
        phase = Phase.IDLE
        problem = null
    }

    /** Layout, schema, mode, commit or clear changes: reset completely. */
    fun clear() {
        phase = Phase.IDLE
        report = null
        allSpellings = emptyList()
        lock = null
        problem = null
    }

    companion object {
        /** The problem an unsuccessful engine outcome stands for. */
        fun problemOf(outcome: AmbiguityApplyOutcome): Problem? =
            when (outcome) {
                AmbiguityApplyOutcome.APPLIED -> null
                AmbiguityApplyOutcome.STALE -> Problem.STALE
                AmbiguityApplyOutcome.UNSUPPORTED -> Problem.UNSUPPORTED
                AmbiguityApplyOutcome.INVALID -> Problem.INVALID
                AmbiguityApplyOutcome.FAILED -> Problem.FAILED
                AmbiguityApplyOutcome.FAILED_ROLLBACK_UNVERIFIED -> Problem.ROLLBACK_UNVERIFIED
            }
    }
}
