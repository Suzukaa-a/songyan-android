package app.songyan.ime.ime

import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import app.songyan.ime.ime.KeyboardView.Companion.BACKSPACE_FIRST_DELAY_MS
import app.songyan.ime.ime.KeyboardView.Companion.BACKSPACE_REPEAT_MS
import app.songyan.ime.ime.KeyboardView.Companion.CURSOR_START_DP
import app.songyan.ime.ime.KeyboardView.Companion.DEFAULT_LONG_PRESS_DELAY_S
import app.songyan.ime.ime.KeyboardView.Companion.FLICK_DISTANCE_DP
import app.songyan.ime.ime.KeyboardView.Companion.FLICK_KEY_SHARE
import app.songyan.ime.ime.KeyboardView.Companion.FLICK_RETURN_DP
import app.songyan.ime.ime.KeyboardView.Companion.NO_POINTER
import app.songyan.ime.ime.KeyboardView.Companion.PREVIEW_GROW_DP
import app.songyan.ime.ime.KeyboardView.Companion.PREVIEW_MAX_CHARS
import app.songyan.ime.ime.KeyboardView.Companion.SWIPE_PREVIEW_DP
import app.songyan.ime.ime.KeyboardView.Companion.TOUCH_SLOP_DP
import app.songyan.ime.ime.KeyboardView.Press
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutOption
import app.songyan.ime.layout.LetterSwipeTracker
import kotlin.math.abs
import kotlin.math.max

/** The key tick for a captured press; fired once when the press is accepted. */
internal fun KeyboardView.feedback(
    snapshot: KeyPressSnapshot,
    kind: FeedbackKind = FeedbackKind.PRESS,
) {
    onFeedback(PressFeedback.tick(snapshot), kind)
}

/**
 * Runs a captured press: the action is the one that was under the finger at ACTION_DOWN.
 * Feedback already fired at the accepted DOWN, so nothing here ticks again.
 */
internal fun KeyboardView.activate(snapshot: KeyPressSnapshot) {
    val rail = snapshot.railLabel
    if (rail != null) {
        // An engine-backed rail choice: a lock option or a candidate reading. One already applied
        // is shown for reference only; Delete undoes it.
        if (!snapshot.railApplied) onRailChoice(snapshot.railPosition, rail)
        return
    }
    val action = snapshot.action
    if (action.type == LayoutActionKind.SHIFT) {
        val now = SystemClock.uptimeMillis()
        if (now - lastShift < 350) {
            onDoubleShift()
            lastShift = 0
            return
        }
        lastShift = now
    }
    onAction(action)
}

internal fun KeyboardView.handleDown(
    event: MotionEvent,
    pointerIndex: Int,
) {
    val x = event.getX(pointerIndex)
    val y = event.getY(pointerIndex)
    // A new finger takes the surface over: an open popup is cancelled and its highlight dropped.
    if (popupKey >= 0) dismissPopup(animated = true)
    stopAccessibilityRepeat()
    val slot = KeyGeometry.hit(hitBounds, x, y)
    if (slot < 0) return
    rollOver()
    ImePerf.keyDown()
    val pointerId = event.getPointerId(pointerIndex)
    val press =
        Press(
            pointerId = pointerId,
            snapshot = keySnapshot(slot),
            startedAt = SystemClock.uptimeMillis(),
            startX = x,
            startY = y,
            key = slot,
            lastX = x,
        )
    val codes = press.snapshot.swipeCodes
    if (codes != null && codes.size > 1) press.tracker = LetterSwipeTracker(codes.size)
    pointers.put(pointerId, press)
    // Accepted press: the tick is synchronous with the touch, not with the release.
    feedback(press.snapshot)
    if (press.snapshot.isBackspace) {
        // Delete acts on the press itself, as in AOSP LatinIME and Gboard: the tick and the deletion
        // arrive together, and nothing the finger does afterwards (a roll, a slide, a second
        // finger, a cancel) can take the deletion back. Holding repeats it; the release adds nothing.
        press.startedComposing = composing()
        BackspaceTrace.record(BackspaceTrace.Event.TOUCH_DOWN_DELETE, press.startedComposing)
        activate(press.snapshot)
    }
    showPreview(press)
    postLongPress(press)
    invalidateKey(slot)
}

/**
 * The magnified preview of a tapped character key, above the finger. Off in an editor that must
 * not echo what is typed, and for keys whose face is not a character (Shift, Return, rails).
 */
internal fun KeyboardView.showPreview(press: Press) {
    val layer = popupLayer ?: return
    if (!keyPreviewEnabled || previewSuppressed) return
    val index = press.key
    val key = keys.getOrNull(index) ?: return
    if (index in railIndices) return
    val type = key.action.type
    if (type != LayoutActionKind.CODE && type != LayoutActionKind.LITERAL) return
    val label = labels.getOrNull(index)?.takeIf { it.isNotEmpty() && it.length <= PREVIEW_MAX_CHARS } ?: return
    val slot = bounds.getOrNull(index) ?: return
    updateLayerOffset()
    val cap = toLayer(capBounds(slot))
    val width = max(metrics.popupCellWidthDp * density, cap.right - cap.left + 2 * PREVIEW_GROW_DP * density)
    val height = metrics.popupCellHeightDp * density
    layer.showPreview(
        label,
        PopupGeometry.preview(cap, width, height, layer.width.toFloat(), 0f, PopupGeometry.GAP_DP * density / 2),
    )
}

/** The drawn keycap inside its slot, in this view's coordinates. */
internal fun KeyboardView.capBounds(slot: KeyBounds): KeyBounds =
    KeyBounds(slot.left + insetX, slot.top + insetY, slot.right - insetX, slot.bottom - insetY)

/** Where this view sits in the popup layer, so key bounds can be handed over in layer coordinates. */
internal fun KeyboardView.updateLayerOffset() {
    val layer = popupLayer
    if (layer == null) {
        layerOffset[0] = 0
        layerOffset[1] = 0
        return
    }
    getLocationInWindow(ownLocation)
    layer.getLocationInWindow(layerLocation)
    layerOffset[0] = ownLocation[0] - layerLocation[0]
    layerOffset[1] = ownLocation[1] - layerLocation[1]
}

internal fun KeyboardView.toLayer(bounds: KeyBounds): KeyBounds =
    KeyBounds(
        bounds.left + layerOffset[0],
        bounds.top + layerOffset[1],
        bounds.right + layerOffset[0],
        bounds.bottom + layerOffset[1],
    )

internal fun KeyboardView.fromLayer(bounds: KeyBounds): KeyBounds =
    KeyBounds(
        bounds.left - layerOffset[0],
        bounds.top - layerOffset[1],
        bounds.right - layerOffset[0],
        bounds.bottom - layerOffset[1],
    )

/**
 * Typing with two thumbs: a second finger landing commits the key the first finger still holds,
 * so fast overlapping taps (a letter then space, a letter then Return) type in the order they
 * were pressed. Keys already in a gesture (holding, sliding, choosing an alternate) are left to it.
 */
internal fun KeyboardView.rollOver() {
    for (i in pointers.size() - 1 downTo 0) {
        val press = pointers.valueAt(i)
        if (press.held || press.moved || press.tracker?.choice != null) continue
        if (popupKey >= 0 && popupOwner == press.pointerId) continue
        press.task?.let { timers.removeCallbacks(it) }
        fadeOut(press, SystemClock.uptimeMillis())
        pointers.removeAt(i)
        invalidateKey(press.key)
        if (press.snapshot.isBackspace) {
            // Already deleted at its DOWN: the second finger only ends its hold.
            BackspaceTrace.record(BackspaceTrace.Event.TOUCH_ENDED_BY_SECOND_FINGER)
        } else {
            activate(press.snapshot)
        }
        onReleaseFeedback()
    }
}

internal fun KeyboardView.postLongPress(press: Press) {
    val delay =
        if (press.snapshot.isBackspace) {
            max(
                BACKSPACE_FIRST_DELAY_MS,
                systemLongPressMs(),
            )
        } else {
            longPressDelayMs()
        }
    val task =
        object : Runnable {
            override fun run() {
                if (press.moved || press.tracker?.choice != null) return
                if (pointers.get(press.pointerId) !== press) return
                beginLongPress(press)
            }
        }
    press.task = task
    timers.postDelayed(task, delay)
}

/**
 * The hold that opens alternates, caret mode or menus. Follows the system's touch & hold delay
 * unless the user picked their own, see [HoldDelay.resolve].
 */
internal fun KeyboardView.longPressDelayMs(): Long =
    HoldDelay.resolve(
        longPressOverrideMs,
        systemLongPressMs(),
        layout?.effectiveLongPressDelay ?: DEFAULT_LONG_PRESS_DELAY_S,
    )

/** The key hold delay the user can choose, and how it combines with the system's and the layout's. */
object HoldDelay {
    const val MIN_MS = 150
    const val MAX_MS = 1000
    const val STEP_MS = 50

    /** Snaps a stored value to the range and the step; null stays "follow the system". */
    fun clamp(value: Int?): Int? = value?.let { (it.coerceIn(MIN_MS, MAX_MS) - MIN_MS) / STEP_MS * STEP_MS + MIN_MS }

    /**
     * No choice: never shorter than the system's touch & hold delay, which users lengthen in the
     * accessibility settings; a layout may ask for longer. A choice replaces both, because it is
     * the user's explicit wish and may be shorter than either.
     */
    fun resolve(
        customMs: Int?,
        systemMs: Long,
        layoutSeconds: Double,
    ): Long = clamp(customMs)?.toLong() ?: max(systemMs, (layoutSeconds * 1000).toLong())
}

internal fun KeyboardView.systemLongPressMs(): Long = ViewConfiguration.getLongPressTimeout().toLong()

internal fun KeyboardView.beginLongPress(press: Press) {
    when {
        press.snapshot.isBackspace -> {
            press.held = true
            // A hold that began on a composition stops once that composition is gone: the next
            // deletes would eat text that was already committed, which needs a new hold. The DOWN
            // may itself have deleted its last letter.
            if (press.startedComposing && !composing()) {
                BackspaceTrace.record(BackspaceTrace.Event.TOUCH_HOLD_STOPPED_COMPOSITION_GONE)
                return
            }
            // The DOWN deleted once; the hold's first repeat lands now, then one per tick.
            BackspaceTrace.record(BackspaceTrace.Event.TOUCH_HOLD_START, composing())
            feedback(press.snapshot, FeedbackKind.REPEAT)
            activate(press.snapshot)
            scheduleRepeat(press, press.startedComposing)
        }
        press.snapshot.isSpace -> {
            // Holding the space bar enters caret mode; the cue appears before the first step.
            press.held = true
            press.cursorEntered = true
            feedback(press.snapshot, FeedbackKind.HOLD)
            setCursorDragging(true)
        }
        press.snapshot.action.type == LayoutActionKind.LANGUAGE_SWITCH -> {
            press.held = true
            popupLayer?.hidePreview(0)
            feedback(press.snapshot, FeedbackKind.HOLD)
            onLanguageMenu()
        }
        press.snapshot.action.type == LayoutActionKind.GLOBE -> {
            // A tap goes to the next input method; a hold asks which one.
            press.held = true
            popupLayer?.hidePreview(0)
            feedback(press.snapshot, FeedbackKind.HOLD)
            onPickInputMethod()
        }
        press.snapshot.isSymbolPanelKey -> {
            press.held = true
            popupLayer?.hidePreview(0)
            feedback(press.snapshot, FeedbackKind.HOLD)
            onSymbolPanel()
        }
        press.snapshot.longPress.isNotEmpty() -> {
            press.held = true
            feedback(press.snapshot, FeedbackKind.HOLD)
            openPopup(press)
        }
    }
}

/** One delete per repeat tick, bound to the same pointer and its captured action. */
internal fun KeyboardView.scheduleRepeat(
    press: Press,
    startedComposing: Boolean,
) {
    val repeat =
        object : Runnable {
            override fun run() {
                if (pointers.get(press.pointerId) !== press) return
                if (startedComposing && !composing()) {
                    BackspaceTrace.record(BackspaceTrace.Event.TOUCH_HOLD_STOPPED_COMPOSITION_GONE)
                    return
                }
                BackspaceTrace.record(BackspaceTrace.Event.TOUCH_REPEAT, startedComposing)
                feedback(press.snapshot, FeedbackKind.REPEAT)
                activate(press.snapshot)
                timers.postDelayed(this, BACKSPACE_REPEAT_MS)
            }
        }
    press.task = repeat
    timers.postDelayed(repeat, BACKSPACE_REPEAT_MS)
}

internal fun KeyboardView.openPopup(press: Press) {
    val options = press.snapshot.longPress
    if (options.isEmpty()) return
    val slot = bounds.getOrNull(press.key) ?: return
    popupKey = press.key
    popupOwner = press.pointerId
    showPopup(slot, options)
    onPopupChanged()
}

/**
 * Fixes the popup's geometry (it never moves while open, so the finger does not chase it) and
 * hands it to the layer. The first alternate sits right above the key, so resting on the key
 * and lifting commits it.
 */
internal fun KeyboardView.showPopup(
    slot: KeyBounds,
    options: List<LayoutOption>,
) {
    popupOptions = options
    popupIndex = 0
    popupLayer?.hidePreview(0)
    updateLayerOffset()
    val layer = popupLayer
    val layerWidth = layer?.width?.toFloat()?.takeIf { it > 0f } ?: (width.toFloat() + layerOffset[0])
    val geometry =
        PopupGeometry.alternates(
            anchor = toLayer(capBounds(slot)),
            count = options.size,
            cellWidth = metrics.popupCellWidthDp * density,
            cellHeight = metrics.popupCellHeightDp * density,
            layerWidth = layerWidth,
            layerTop = 0f,
            gap = PopupGeometry.GAP_DP * density,
        )
    popupBounds = fromLayer(geometry.bounds)
    popupMirrored = geometry.mirrored
    layer?.open(options.map { it.label }, geometry, 0)
    invalidateKey(popupKey)
}

internal fun KeyboardView.handleMove(event: MotionEvent) {
    for (i in 0 until event.pointerCount) {
        val press = pointers.get(event.getPointerId(i)) ?: continue
        val x = event.getX(i)
        val y = event.getY(i)
        val tracker = press.tracker
        if (tracker != null) {
            val changed =
                tracker.update(
                    ((x - press.startX) / density).toDouble(),
                    ((y - press.startY) / density).toDouble(),
                )
            if (tracker.choice != null) press.moved = true
            if (changed) updateSwipePreview(press)
            continue
        }
        if (press.snapshot.railPosition >= 0) {
            dragRail(press, y)
            continue
        }
        val flickDigit = flickDigit(press.snapshot)
        if (flickDigit != null && popupKey < 0 && !press.held) {
            flick(press, flickDigit, y)
        } else if (flickDigit == null && popupKey < 0 && !press.held) {
            flickOption(press, y)
        }
        when {
            popupKey >= 0 && popupOwner == press.pointerId -> {
                val next = popupSelection(press, x, y)
                if (next != popupIndex) {
                    // Moving onto another alternate ticks, so a thumb covering the popup can count cells.
                    if (next >= 0) feedback(press.snapshot, FeedbackKind.TICK)
                    popupIndex = next
                    popupLayer?.select(next)
                }
            }
            press.snapshot.isSpace -> dragCursor(press, x, y, event.eventTime)
            else -> {
                val slop = TOUCH_SLOP_DP * density
                val past = abs(x - press.startX) > slop || abs(y - press.startY) > slop
                // A thumb held on a key rolls and flattens past the touch slop while still on that
                // key. As with a View's long click, only leaving the key ends the hold; otherwise a
                // held switch key never asks for the input method and its release switches instead.
                val hitBox = hitBounds.getOrNull(press.key)
                val offKey = hitBox == null || !KeyGeometry.near(hitBox, x, y, slop)
                if (!press.held && !press.moved && past && offKey) {
                    press.moved = true
                    // Off Delete before the hold began: no repeat. The DOWN's delete stands.
                    if (press.snapshot.isBackspace) BackspaceTrace.record(BackspaceTrace.Event.TOUCH_LEFT_KEY)
                }
            }
        }
    }
}

/**
 * A vertical drag on the nine-key rail scrolls its choices one slot per slot-height of travel (the
 * finger moving up reveals later choices); a press that scrolled never selects on release.
 */
internal fun KeyboardView.dragRail(
    press: Press,
    y: Float,
) {
    val box = bounds.getOrNull(press.key) ?: return
    val rise = press.startY - y
    if (!press.railDragged && abs(rise) <= TOUCH_SLOP_DP * density) return
    press.railDragged = true
    press.moved = true
    val steps = (rise / (box.bottom - box.top).coerceAtLeast(1f)).toInt()
    if (steps != press.railSteps) {
        onRailScroll(steps - press.railSteps)
        press.railSteps = steps
    }
}

/** The digit a nine-key digit key types on an upward flick (its long press types it too), else null. */
internal fun KeyboardView.flickDigit(snapshot: KeyPressSnapshot): String? {
    if (!faces.phone || snapshot.action.type != LayoutActionKind.CODE) return null
    val digit = snapshot.action.value ?: return null
    return digit.takeIf { it.length == 1 && it[0] in '2'..'9' }
}

/**
 * The upward flick of a nine-key digit key (as on iOS): rising past [FLICK_DISTANCE_DP] or 40% of
 * the key arms the digit, shown above the key; coming back down disarms it and the release is a tap.
 */
internal fun KeyboardView.flick(
    press: Press,
    digit: String,
    y: Float,
) {
    val rise = press.startY - y
    val box = bounds.getOrNull(press.key)
    val threshold = max(FLICK_DISTANCE_DP * density, (box?.let { it.bottom - it.top } ?: 0f) * FLICK_KEY_SHARE)
    flickVoid(press, rise, threshold, KeyFlick.eligible(press.snapshot, true, false))
    if (!press.risen && rise >= threshold) {
        press.risen = true
        press.moved = true
        popupLayer?.hidePreview(0)
        showFlickPreview(press, digit)
    } else if (press.risen && rise < FLICK_RETURN_DP * density) {
        press.risen = false
        press.moved = false
        popupLayer?.hideSwipe()
    }
}

/**
 * The vertical flick of a character key: up arms its first long-press option, down its second.
 * Same threshold and disarm rule as the digit flick; the release types the option's own action.
 */
internal fun KeyboardView.flickOption(
    press: Press,
    y: Float,
) {
    val rise = press.startY - y
    val box = bounds.getOrNull(press.key)
    val threshold = max(FLICK_DISTANCE_DP * density, (box?.let { it.bottom - it.top } ?: 0f) * FLICK_KEY_SHARE)
    val back = FLICK_RETURN_DP * density
    if (press.flickIndex >= 0) {
        val armedUp = press.flickIndex == 0
        if ((armedUp && rise < back) || (!armedUp && -rise < back)) {
            press.flickIndex = -1
            press.moved = false
            popupLayer?.hideSwipe()
        } else {
            return
        }
    }
    val up =
        when {
            rise >= threshold -> true
            -rise >= threshold -> false
            else -> {
                flickVoid(press, rise, threshold, false)
                return
            }
        }
    val option = flickTarget(press.snapshot, up)
    if (option == null) {
        flickVoid(press, rise, threshold, KeyFlick.eligible(press.snapshot, keyFlickEnabled, false))
        return
    }
    press.flickVoid = false
    press.flickIndex = if (up) 0 else 1
    press.moved = true
    popupLayer?.hidePreview(0)
    showFlickPreview(press, option.label)
}

/**
 * A flick-capable key dragged up or down past [threshold] with nothing to type that way voids the
 * press, so the key under the lifted finger is not typed instead; coming back near the key undoes
 * it. The nine-key digit flick only types upward, so its downward flick voids too.
 */
internal fun KeyboardView.flickVoid(
    press: Press,
    rise: Float,
    threshold: Float,
    eligible: Boolean,
) {
    val digitUp = press.risen || (flickDigit(press.snapshot) != null && rise >= threshold)
    when {
        eligible && !digitUp && abs(rise) >= threshold -> {
            press.flickVoid = true
            press.moved = true
        }
        press.flickVoid && abs(rise) < FLICK_RETURN_DP * density -> {
            press.flickVoid = false
            press.moved = false
        }
    }
}

internal fun KeyboardView.flickTarget(
    snapshot: KeyPressSnapshot,
    up: Boolean,
    merged: Boolean = false,
): LayoutOption? = KeyFlick.option(snapshot, up, keyFlickEnabled, flickDigit(snapshot) != null, merged)

internal fun KeyboardView.showFlickPreview(
    press: Press,
    digit: String,
) {
    val layer = popupLayer ?: return
    val slot = bounds.getOrNull(press.key) ?: return
    updateLayerOffset()
    val size = SWIPE_PREVIEW_DP * density
    val cap = toLayer(capBounds(slot))
    layer.showSwipe(digit, PopupGeometry.preview(cap, size, size, layer.width.toFloat(), 0f, 4 * density))
}

/** The swipe letter of a merged key, above the key while the finger has picked a side. */
internal fun KeyboardView.updateSwipePreview(press: Press) {
    val layer = popupLayer ?: return
    val choice = press.tracker?.choice
    val label = choice?.let { swipeLetters.getOrNull(press.key)?.getOrNull(it) }
    val slot = bounds.getOrNull(press.key)
    if (label == null || slot == null) {
        layer.hideSwipe()
        return
    }
    updateLayerOffset()
    val size = SWIPE_PREVIEW_DP * density
    val cap = toLayer(capBounds(slot))
    layer.showSwipe(label, PopupGeometry.preview(cap, size, size, layer.width.toFloat(), 0f, 4 * density))
}

/**
 * Space-bar caret drag: [CursorDrag] decides how far the finger must travel per character, less
 * while the drag is fast. The first move of a drag that did not start as a hold gives the
 * same "entered caret mode" haptic a hold gives; every moved character ticks, at most once per 30 ms.
 */
internal fun KeyboardView.dragCursor(
    press: Press,
    x: Float,
    y: Float,
    eventTime: Long,
) {
    // A hold that only jitters is still a space: the drag starts after a deliberate sideways move,
    // while a slide off the bar (any direction past the touch slop) cancels it as before.
    val sideways = abs(x - press.startX) > CURSOR_START_DP * density
    if (!sideways) {
        val slop = TOUCH_SLOP_DP * density
        if (!press.held && abs(y - press.startY) > slop) press.moved = true
        return
    }
    press.moved = true
    if (!press.cursorEntered) {
        press.cursorEntered = true
        press.lastX = press.startX
        feedback(press.snapshot, FeedbackKind.HOLD)
    }
    setCursorDragging(true)
    // Speed comes from the events' own times: when they were handled says nothing about the finger.
    if (press.cursorSampleAt == 0L) {
        press.cursorSampleX = press.lastX
        press.cursorSampleAt = eventTime
    }
    press.cursorFast =
        CursorDrag.fast(press.cursorFast, abs(x - press.cursorSampleX) / density, eventTime - press.cursorSampleAt)
    press.cursorSampleX = x
    press.cursorSampleAt = eventTime
    val now = SystemClock.uptimeMillis()
    val stepPx = (if (press.cursorFast) CursorDrag.FAST_STEP_DP else CursorDrag.STEP_DP) * density
    val travel = x - press.lastX
    val steps = CursorDrag.wholeSteps(travel, stepPx)
    if (steps == 0) return
    onCursor(steps)
    press.lastX += steps * stepPx
    if (CursorDrag.tickDue(now, lastTick)) {
        lastTick = now
        feedback(press.snapshot, FeedbackKind.TICK)
    }
}

internal fun KeyboardView.handleUp(
    event: MotionEvent,
    pointerIndex: Int,
) {
    val pointerId = event.getPointerId(pointerIndex)
    val press = pointers.get(pointerId)
    if (press != null) {
        press.task?.let { timers.removeCallbacks(it) }
        val x = event.getX(pointerIndex)
        val y = event.getY(pointerIndex)
        val now = SystemClock.uptimeMillis()
        if (popupKey >= 0 && popupOwner == pointerId) {
            finishPopup(press, x, y)
        } else {
            val choice = press.tracker?.choice
            val codes = press.snapshot.swipeCodes
            when {
                choice != null && codes != null -> {
                    codes.getOrNull(choice)?.let { onAction(LayoutAction(LayoutActionKind.CODE, it)) }
                }
                press.flickIndex >= 0 ->
                    press.snapshot.longPress
                        .getOrNull(press.flickIndex)
                        ?.let { onAction(it.action) }
                press.risen -> flickDigit(press.snapshot)?.let { onAction(LayoutAction.literal(it)) }
                press.railDragged -> Unit
                press.flickVoid -> Unit
                // Delete already acted at its DOWN (and on every repeat); the release adds nothing.
                press.snapshot.isBackspace -> Unit
                !press.held && !press.moved -> activate(press.snapshot)
                // Holding space only arms caret mode; a hold that never moved the caret is
                // still a space.
                press.held && !press.moved && press.snapshot.isSpace -> activate(press.snapshot)
                !press.held && !press.snapshot.isSpace -> {
                    val target = KeyGeometry.hit(hitBounds, x, y)
                    when {
                        // Lifted on the key it pressed: that key was meant, whatever it does, as a
                        // View's click survives drift that stays inside it. Without this a function
                        // key (Shift, Return, a page key) ticked on the press and then did nothing.
                        target >= 0 && target == press.key -> activate(press.snapshot)
                        // A drifted finger still activates the key it is over, whatever it types.
                        target >= 0 && SlideRecovery.accepts(keys[target].action) -> activate(keySnapshot(target))
                    }
                }
            }
        }
        fadeOut(press, now)
        if (press.snapshot.isSpace) setCursorDragging(false)
        if (press.held || !press.moved || press.tracker?.choice != null) onReleaseFeedback()
        pointers.remove(pointerId)
        popupLayer?.hidePreview(KeyMotion.PREVIEW_HOLD_MS)
        popupLayer?.hideSwipe()
        invalidateKey(press.key)
    }
    performClick()
}

internal fun KeyboardView.popupSelection(
    press: Press,
    x: Float,
    y: Float,
): Int =
    PopupSelection.index(
        popupBounds,
        popupOptions.size,
        press.startX,
        press.startY,
        x,
        y,
        popupIndex,
        density,
        popupMirrored,
    )

/** Commits the selected alternate; a release after a slide-out or a pull down cancels. */
internal fun KeyboardView.finishPopup(
    press: Press,
    x: Float,
    y: Float,
) {
    val option = popupOptions.getOrNull(popupSelection(press, x, y))
    dismissPopup(animated = true)
    if (option != null) onAction(option.action)
    press.held = true
}

/** Ends the popup: the view forgets it at once, the layer fades it out (or drops it) on its own. */
internal fun KeyboardView.dismissPopup(animated: Boolean) {
    if (popupKey < 0) return
    val wasAccessible = popupOwner == NO_POINTER
    popupLayer?.close(animated)
    clearPopupState()
    if (wasAccessible) {
        // The node loses its per-alternate custom actions; services must re-read it.
        sendAccessibilityEvent(AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
    }
    onPopupChanged()
}

internal fun KeyboardView.clearPopupState() {
    popupKey = -1
    popupOwner = NO_POINTER
    popupIndex = -1
    popupMirrored = false
    if (popupOptions.isNotEmpty()) popupOptions = emptyList()
}

internal fun KeyboardView.clearPopup() {
    val wasOpen = popupKey >= 0
    popupLayer?.close(false)
    clearPopupState()
    if (wasOpen) onPopupChanged()
}

internal fun KeyboardView.cancelTouches() {
    val now = SystemClock.uptimeMillis()
    for (i in 0 until pointers.size()) {
        val press = pointers.valueAt(i)
        press.task?.let { timers.removeCallbacks(it) }
        fadeOut(press, now)
        if (press.snapshot.isBackspace) BackspaceTrace.record(BackspaceTrace.Event.TOUCH_CANCELLED)
    }
    pointers.clear()
    clearPopup()
    popupLayer?.hidePreview(0)
    popupLayer?.hideSwipe()
    setCursorDragging(false)
    stopAccessibilityRepeat()
    invalidate()
}

/**
 * Starts the release fade of [press]'s key. A page key switches the page as it fires, before this
 * runs: its slot then holds a key of the new page, which must not light up.
 */
internal fun KeyboardView.fadeOut(
    press: KeyboardView.Press,
    at: Long,
) {
    if (press.key in releases.indices && identities.getOrNull(press.key) == press.snapshot.identity) {
        releases[press.key] = at
    }
}
