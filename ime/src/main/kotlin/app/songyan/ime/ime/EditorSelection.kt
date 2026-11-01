package app.songyan.ime.ime

/**
 * Where the keyboard expects the editor's selection and composing region to be after its own
 * edits, so a selection report can be told apart from a cursor the user moved.
 *
 * `onUpdateSelection` arrives asynchronously, often several edits late while typing fast. A late
 * report of the keyboard's own earlier edit must never end the composition the user is typing now,
 * and a report that only lacks the composing region (`candidatesStart == -1`, which some editors
 * always send) is not proof that the composition ended. Pure value logic; -1 means unknown.
 */
class EditorSelection {
    var start = UNKNOWN
        private set
    var end = UNKNOWN
        private set

    /** Start of the composing region the keyboard last wrote; [UNKNOWN] when not composing. */
    var composingStart = UNKNOWN
        private set
    var composingLength = 0
        private set

    val composingEnd: Int get() = if (composingStart < 0) UNKNOWN else composingStart + composingLength

    val known: Boolean get() = start >= 0 && end >= 0

    /** The editor's own initial selection; nothing the keyboard wrote survives into a new editor. */
    fun reset(
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        start = selectionStart.coerceAtLeast(UNKNOWN)
        end = selectionEnd.coerceAtLeast(UNKNOWN)
        composingStart = UNKNOWN
        composingLength = 0
    }

    fun forget() = reset(UNKNOWN, UNKNOWN)

    /**
     * Records `commitText` / `setComposingText` / an emptied composition, in the order the
     * connection applies them: every commit replaces the composing region (or the selection), and a
     * non-empty [preedit] becomes the new composing region at the cursor.
     */
    fun afterUpdate(
        commits: List<String>,
        preedit: String,
        wasComposing: Boolean,
    ) {
        if (commits.isEmpty() && preedit.isEmpty() && !wasComposing) return
        var cursor =
            when {
                wasComposing && composingStart >= 0 -> composingStart
                known && !wasComposing -> minOf(start, end)
                else -> UNKNOWN
            }
        for (text in commits) {
            if (cursor >= 0) cursor += text.length
        }
        if (preedit.isNotEmpty()) {
            composingStart = cursor
            composingLength = preedit.length
            if (cursor >= 0) cursor += preedit.length
        } else {
            composingStart = UNKNOWN
            composingLength = 0
        }
        start = cursor
        end = cursor
    }

    /** The keyboard moved the cursor itself (a known target), or caused an edit it cannot measure. */
    fun moved(
        selectionStart: Int,
        selectionEnd: Int,
    ) {
        start = selectionStart
        end = selectionEnd
    }

    /** The editor reported where its composing region really is; that beats the keyboard's estimate. */
    fun reportedComposing(
        candidatesStart: Int,
        candidatesEnd: Int,
    ) {
        if (composingStart == UNKNOWN && composingLength == 0) return
        if (candidatesStart < 0 || candidatesEnd < candidatesStart) return
        composingStart = candidatesStart
        composingLength = candidatesEnd - candidatesStart
    }

    /** A collapsed cursor moved by [delta] UTF-16 units; unknown stays unknown. */
    fun shifted(delta: Int) {
        if (!known) return
        val cursor = (minOf(start, end) + delta).coerceAtLeast(0)
        start = cursor
        end = cursor
    }

    /**
     * Whether a report is the late echo of the keyboard's own edits rather than the user moving the
     * selection (same rule as AOSP LatinIME `RichInputConnection.isBelatedExpectedUpdate`).
     */
    fun isExpected(
        oldStart: Int,
        oldEnd: Int,
        newStart: Int,
        newEnd: Int,
    ): Boolean {
        if (!known) return false
        if (start == newStart && end == newEnd) return true
        // The old values match what the keyboard expected and an endpoint moved: someone other
        // than the keyboard moved it.
        if (start == oldStart && end == oldEnd && (oldStart != newStart || oldEnd != newEnd)) return false
        // The editor is catching up: a cursor reported between where it was and where the
        // keyboard's edits will leave it.
        return newStart == newEnd &&
            (newStart - oldStart).toLong() * (start - newStart) >= 0 &&
            (newEnd - oldEnd).toLong() * (end - newEnd) >= 0
    }

    companion object {
        const val UNKNOWN = -1

        /**
         * Where a selection endpoint lands once the composing region `[regionStart, regionEnd)` is
         * removed: after it shifts left by its length, inside it collapses to its start.
         */
        fun afterRemoving(
            position: Int,
            regionStart: Int,
            regionEnd: Int,
        ): Int =
            when {
                position >= regionEnd -> position - (regionEnd - regionStart)
                position > regionStart -> regionStart
                else -> position
            }
    }
}
