package app.songyan.ime.ime

// The editor side of a Delete swipe: the text before the caret is selected one user-perceived
// character at a time, and the release deletes exactly that selection in one edit. The selection is
// the user's preview of what will go, so it is shown by the host's own highlight.

/** How much text before the caret a swipe can reach; the read happens once, when the swipe begins. */
internal const val SWIPE_WINDOW = 512

/** A late selection report of the swipe's own edits is still recognised for this long after it ended. */
private const val SWIPE_ECHO_MS = 1_000L
private const val NANOS_PER_MS = 1_000_000L

/** Whether a swipe could start now. No editor round trip: it only reads what the keyboard already knows. */
internal fun EditorConnection.swipeSelectAvailable(): Boolean =
    connection() != null &&
        !keyEventEditor &&
        !composing &&
        selection.known &&
        selection.start == selection.end

/** Starts a swipe at the caret; false when there is nothing to select (the swipe then does nothing). */
internal fun EditorConnection.swipeSelectBegin(): Boolean {
    swipeSelectAbandon()
    if (!swipeSelectAvailable()) return false
    val ic = connection() ?: return false
    val before = ic.getTextBeforeCursor(SWIPE_WINDOW, 0)?.toString()
    if (before.isNullOrEmpty()) return false
    swipeBefore = before
    swipeAnchor = selection.start
    swipeUnits = 0
    selectionAnchor = -1
    return true
}

/** Selects [count] characters before the anchor, as far as the text reaches. */
internal fun EditorConnection.swipeSelectTo(count: Int) {
    if (swipeAnchor < 0) return
    val ic = connection() ?: return swipeSelectAbandon()
    val units = EditorConnection.clusterUnits(swipeBefore, count, fromEnd = true)
    if (units == swipeUnits) return
    if (ic.setSelection(swipeAnchor - units, swipeAnchor)) {
        selection.moved(swipeAnchor - units, swipeAnchor)
        swipeUnits = units
        forgetText()
    }
}

/**
 * Ends a swipe: [delete] removes the selected characters, otherwise the caret goes back where the
 * swipe began. A selection the host has changed since is only collapsed, never deleted.
 */
internal fun EditorConnection.swipeSelectEnd(delete: Boolean) {
    if (swipeAnchor < 0) return
    val anchor = swipeAnchor
    val units = swipeUnits
    swipeSelectAbandon(keepEcho = true)
    val ic = connection() ?: return
    if (units == 0) return
    val start = anchor - units
    swipeEchoFinal = start
    if (delete) {
        var removed = false
        ic.beginBatchEdit()
        try {
            if (ic.getSelectedText(0)?.length == units) removed = ic.commitText("", 1)
        } finally {
            ic.endBatchEdit()
        }
        if (removed) {
            BackspaceTrace.record(BackspaceTrace.Event.EDITOR_SWIPE_DELETED)
            selection.moved(start, start)
            forgetText()
            return
        }
    }
    if (ic.setSelection(anchor, anchor)) {
        selection.moved(anchor, anchor)
        forgetText()
    }
}

/** Forgets the swipe without touching the editor: the selection is no longer the keyboard's to manage. */
internal fun EditorConnection.swipeSelectAbandon(keepEcho: Boolean = false) {
    if (keepEcho && swipeAnchor >= 0) {
        swipeEchoAnchor = swipeAnchor
        swipeEchoReach = swipeBefore.length
        swipeEchoUntil = System.nanoTime() / NANOS_PER_MS + SWIPE_ECHO_MS
    } else {
        swipeEchoUntil = 0L
    }
    swipeAnchor = -1
    swipeBefore = ""
    swipeUnits = 0
}

/**
 * Whether a selection report is the editor catching up with the swipe's own edits (its intermediate
 * selections, or the caret it started from). A host reports asynchronously, so a late report of an
 * earlier step must not be taken for the user moving the selection.
 */
internal fun EditorConnection.isSwipeEcho(
    newStart: Int,
    newEnd: Int,
): Boolean {
    val anchor: Int
    val reach: Int
    when {
        swipeAnchor >= 0 -> {
            anchor = swipeAnchor
            reach = swipeBefore.length
        }
        System.nanoTime() / NANOS_PER_MS < swipeEchoUntil -> {
            anchor = swipeEchoAnchor
            reach = swipeEchoReach
        }
        else -> return false
    }
    if (newStart == newEnd) return newEnd == anchor || (swipeAnchor < 0 && newEnd == swipeEchoFinal)
    return newEnd == anchor && newStart >= anchor - reach
}
