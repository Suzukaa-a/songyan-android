package app.songyan.ime.ime

import android.os.SystemClock
import app.songyan.ime.keyboard.layout.KeyAction
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.keyboard.session.InputSession
import app.songyan.ime.layout.LayoutPageTarget

/**
 * The space key. After an English word, a second space within [doubleSpaceWindowMs] replaces
 * the first with a period and a space; any other key, a caret move or a different editor cancels it.
 * [armedAt] is when the previous key was a delivered space, as taken at the start of this dispatch.
 */
internal suspend fun KeyboardController.typeSpace(
    active: InputSession,
    armedAt: Long,
) {
    val eligible =
        doubleSpacePeriodEnabled &&
            DoubleSpacePeriod.allowedIn(policy) &&
            active.mode == InputMode.ENGLISH &&
            !active.snapshot.isComposing
    val now = SystemClock.uptimeMillis()
    if (eligible &&
        DoubleSpacePeriod.withinWindow(armedAt, now, doubleSpaceWindowMs) &&
        DoubleSpacePeriod.replaceable(callbacks.contextBeforeCursor(DoubleSpacePeriod.CONTEXT_LIMIT)) &&
        callbacks.replaceBeforeCursor(" ", ". ")
    ) {
        noteCommitForEnglishWord(". ")
        return
    }
    val delivered = deliveredCommits
    active.tap(KeyAction.Space)
    if (eligible && deliveredCommits != delivered) doubleSpaceArmedAt = now
    if (page != null && typedOnSymbolPage && !policy.numeric && deliveredCommits != delivered) {
        if (!endLayoutVisit(LayoutPageTarget.LETTERS)) openPage(LayoutPageTarget.LETTERS)
    }
}
