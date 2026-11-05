package app.songyan.ime.ime

import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import androidx.core.widget.EdgeEffectCompat
import app.songyan.ime.ime.CandidateBarView.CharacterPick
import app.songyan.ime.ime.CandidateBarView.Choice
import app.songyan.ime.ime.CandidateBarView.Control
import app.songyan.ime.ime.CandidateBarView.Entry
import app.songyan.ime.ime.CandidateBarView.Press
import app.songyan.ime.keyboard.engine.Candidate
import kotlin.math.abs

internal fun CandidateBarView.choose(
    entry: Entry,
    revision: Int,
) {
    onCandidate(choiceOf(entry, revision))
}

/**
 * The engine's paged list is addressed by absolute index wherever the candidate sits; a row that
 * does not page keeps its page-local index.
 */
internal fun CandidateBarView.choiceOf(
    entry: Entry,
    revision: Int,
): Choice = Choice(entry.localIndex, entry.text, revision, if (pages.paged) entry.absoluteIndex else -1, entry.comment)

internal fun CandidateBarView.activateControl(localIndex: Int) {
    when (controls.getOrNull(localIndex)) {
        Control.EXPAND -> setExpanded(true)
        Control.BACK -> setExpanded(false)
        Control.BACKSPACE -> onBackspace(snapshot.isComposing)
        Control.RETYPE -> onRetype()
        null -> Unit
    }
}

internal fun CandidateBarView.commitPopupCharacter(index: Int) {
    val open = popup ?: return
    val text = open.chars.getOrNull(index) ?: return
    dismissPopup()
    onCandidateCharacter(CharacterPick(text, open.text, listRevision))
}

internal fun CandidateBarView.onDown(event: MotionEvent): Boolean {
    ui.removeCallbacks(repeatBackspace)
    gestureX = event.x
    gestureY = event.y
    scrollingGesture = false
    popupGestureSlid = false
    velocity?.recycle()
    velocity = VelocityTracker.obtain().also { it.addMovement(event) }
    if (menu != null) {
        menuDown = menu?.rect?.contains(event.x, event.y) == true
        if (menuDown) onPressFeedback()
        invalidate()
        return true
    }
    if (popup != null) {
        popupDown = popupAt(event.x, event.y)
        if (popupDown >= 0) onPressFeedback()
        down = -1
        invalidate()
        return true
    }
    // A touch on a flinging row stops it, and is not a press on whatever slid under the finger.
    val flinging = !scroller.isFinished
    if (flinging) scroller.abortAnimation()
    // A pull still showing absorbs this touch too.
    val catchesPull = EdgeEffectCompat.getDistance(startEdge) > 0f || EdgeEffectCompat.getDistance(endEdge) > 0f
    val hit = hitTest(event.x, event.y)
    // Only the moving candidates consume a touch to stop motion; fixed controls and the
    // independent readings section must respond immediately, even during a fling or edge pull.
    val catchesMotion =
        (flinging || catchesPull) &&
            viewport.contains(event.x.toInt(), event.y.toInt()) &&
            !inReadings(event.x, event.y)
    if (!catchesMotion) releaseEdges()
    downRevision = listRevision
    down = -1
    downControl = -1
    if (hit in 0 until entries.size && !catchesMotion) {
        down = hit
        val entry = entries[hit]
        pressed = Press(hit, entry.absoluteIndex, entry.text, listRevision, event.x, event.y, entry.comment)
        ui.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
    } else if (hit >= readingBase) {
        downReading = hit - readingBase
        if (readings[downReading].selected) downReading = -1
    } else if (hit in entries.size until entries.size + controls.size) {
        downControl = hit - entries.size
    }
    if (down >= 0 || downReading >= 0 || downControl >= 0) onPressFeedback()
    if (controls.getOrNull(downControl) == Control.BACKSPACE) {
        backspaceStartedComposing = snapshot.isComposing
        onBackspace(backspaceStartedComposing)
        if (canRepeatBackspace) ui.postDelayed(repeatBackspace, ViewConfiguration.getLongPressTimeout().toLong())
    }
    // A new touch may try a read that failed before, once.
    pages.resume()
    scrollingReadings = false
    scrollingGesture = catchesMotion
    if (scrollingGesture) lastTouch = if (expanded) event.y else event.x
    invalidate()
    return true
}

internal fun CandidateBarView.onMove(event: MotionEvent): Boolean {
    velocity?.addMovement(event)
    if (menu != null) return true
    if (popup != null) {
        // A stationary hold must not type: only a deliberate slide selects a character.
        val slid = abs(event.x - gestureX) > touchSlop || abs(event.y - gestureY) > touchSlop
        if (!popupOpenedByGesture || popupGestureSlid || slid) {
            if (slid) popupGestureSlid = true
            val hit = popupAt(event.x, event.y)
            if (hit != popupDown) {
                popupDown = hit
                invalidate()
            }
        }
        return true
    }
    if (controls.getOrNull(downControl) == Control.BACKSPACE &&
        hitTest(event.x, event.y) != entries.size + downControl
    ) {
        cancelPress()
        invalidate()
        return true
    }
    val position = if (expanded) event.y else event.x
    if (scrollingGesture) {
        if (scrollingReadings) dragReadings(lastTouch - position) else dragBy(lastTouch - position)
        lastTouch = position
        return true
    }
    // The readings section scrolls on its own, without moving the candidates.
    if (abs(event.x - gestureX) > touchSlop && inReadings(gestureX, gestureY) && readingMaxScroll > 0f) {
        cancelPress()
        scrollingGesture = true
        scrollingReadings = true
        parent?.requestDisallowInterceptTouchEvent(true)
        lastTouch = gestureX
        dragReadings(lastTouch - position)
        lastTouch = position
        return true
    }
    // A slide along the scroll axis that starts on the candidates scrolls them instead of
    // committing the chip it started on.
    val travelled = if (expanded) abs(event.y - gestureY) else abs(event.x - gestureX)
    val fromViewport = viewport.contains(gestureX.toInt(), gestureY.toInt()) && !inReadings(gestureX, gestureY)
    if (travelled > touchSlop && fromViewport && (maxScroll() > 0f || canOverscroll())) {
        cancelPress()
        scrollingGesture = true
        parent?.requestDisallowInterceptTouchEvent(true)
        lastTouch = if (expanded) gestureY else gestureX
        dragBy(lastTouch - position)
        lastTouch = position
        return true
    }
    val press = pressed ?: return true
    if (abs(event.x - press.downX) > touchSlop || abs(event.y - press.downY) > touchSlop) cancelPress()
    return true
}

/** Whether a point lies in the inline readings' section of the collapsed row. */
internal fun CandidateBarView.inReadings(
    x: Float,
    y: Float,
): Boolean = !expanded && readingViewport.contains(x.toInt(), y.toInt())

/** Moves the readings section by [delta] px, within its own extent; the candidates stay put. */
internal fun CandidateBarView.dragReadings(delta: Float) {
    val next = (readingScroll + delta).coerceIn(0f, readingMaxScroll)
    if (next == readingScroll) return
    readingScroll = next
    invalidate()
}

/** A page that fits still gives under the finger: the edge effect shows it was understood. */
internal fun CandidateBarView.canOverscroll(): Boolean = snapshot.candidates.isNotEmpty() && contentExtent > 0f

internal fun CandidateBarView.onUp(event: MotionEvent): Boolean {
    ui.removeCallbacks(longPress)
    ui.removeCallbacks(repeatBackspace)
    velocity?.addMovement(event)
    if (menu != null) {
        val open = menu
        val inside = open?.rect?.contains(event.x, event.y) == true
        if (menuDown && inside && open != null) requestDelete(open.entry, open.revision) else dismissPopup()
        menuDown = false
        finishTouch()
        return true
    }
    if (popup != null) {
        val hit = popupAt(event.x, event.y)
        val open = popup
        when {
            // A hold that only revealed the characters types nothing: the strip closes and
            // leaves the word's menu, so no later touch is ever taken for a stray pick.
            popupOpenedByGesture && !popupGestureSlid -> {
                dismissPopup()
                if (open != null && open.revision == listRevision) showMenu(open.entry, open.revision)
            }
            popupDown >= 0 && hit >= 0 && hit == popupDown -> {
                popupOpenedByGesture = false
                popupDown = -1
                commitPopupCharacter(hit)
            }
            else -> dismissPopup()
        }
        popupDown = -1
        down = -1
        finishTouch()
        return true
    }
    if (scrollingGesture && scrollingReadings) {
        scrollingGesture = false
        scrollingReadings = false
        cancelPress()
        finishTouch()
        return true
    }
    if (scrollingGesture) {
        scrollingGesture = false
        val tracker = velocity
        tracker?.computeCurrentVelocity(1000, maxFling.toFloat())
        val speed = if (expanded) tracker?.yVelocity ?: 0f else tracker?.xVelocity ?: 0f
        cancelPress()
        releaseEdges()
        startFling(speed)
        finishTouch()
        return true
    }
    val press = pressed
    val control = downControl
    val reading = downReading
    pressed = null
    downControl = -1
    downReading = -1
    val hit = hitTest(event.x, event.y)
    beginFade(down, control)
    if (reading >= 0) {
        if (hit == readingBase + reading) readings.getOrNull(reading)?.let { onReading(reading, it.label) }
    } else if (press != null && hit >= 0 && hit < entries.size) {
        val choice = candidateChoice(press, hit)
        if (choice != null) {
            ImePerf.commitStart()
            onCandidate(choice)
        }
    } else if (control >= 0 && hit == entries.size + control && controls.getOrNull(control) != Control.BACKSPACE) {
        // Delete already acted on DOWN; lifting never adds another deletion.
        // Expansion is bound to the same list revision as a selection.
        if (downRevision == listRevision) activateControl(control)
    }
    down = -1
    finishTouch()
    return true
}

internal fun CandidateBarView.finishTouch() {
    recycleVelocity()
    invalidate()
    performClick()
}

internal fun CandidateBarView.recycleVelocity() {
    velocity?.recycle()
    velocity = null
}

/**
 * The identity the release commits: the pressed candidate while the list it was drawn in still
 * shows it under the finger, otherwise (collapsed only) the same text on the engine's new page. The
 * cell index alone is never trusted. The expanded grid commits nothing once its list was replaced:
 * the word may not be on the engine's current page at all.
 */
internal fun CandidateBarView.candidateChoice(
    press: Press,
    upCell: Int,
): Choice? {
    val resolved =
        CandidateRelease.resolve(
            CandidateRelease.Pressed(press.cell, press.absoluteIndex, press.text, press.revision),
            upCell,
            listRevision,
            listedAt = { cell -> entries.getOrNull(cell)?.let { Candidate(it.absoluteIndex, it.text) } },
            page = snapshot.candidates,
        ) ?: return null
    if (resolved.fromList) return choiceOf(entries[upCell], listRevision)
    if (expanded) return null
    return Choice(resolved.index, resolved.text, listRevision, comment = press.comment)
}
