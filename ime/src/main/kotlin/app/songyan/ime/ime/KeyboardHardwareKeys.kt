package app.songyan.ime.ime

import android.view.KeyEvent
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.session.InputMode
import app.songyan.ime.keyboard.session.InputSession
import kotlinx.coroutines.CancellationException

/**
 * Runs one hardware key on the operation worker, in order with soft keys. The engine decides what
 * the key does; what it leaves, with nothing composing, goes on to the app as the original event.
 * A press the keyboard took is matched by swallowing its release, and a forwarded press by
 * forwarding its release, so the app never sees half a key.
 */
internal suspend fun KeyboardController.runHardwareKey(
    event: KeyEvent,
    stroke: HardwareKeyMap.Stroke?,
) {
    ImePerf.keyDispatchStart()
    try {
        routeHardwareKey(event, stroke)
    } finally {
        ImePerf.keyDispatched()
    }
}

private suspend fun KeyboardController.routeHardwareKey(
    event: KeyEvent,
    stroke: HardwareKeyMap.Stroke?,
) {
    // Never forward into an editor that replaced the one the key was typed for.
    if (!fieldStillCurrent()) return
    // Keys wait for the session while it is prepared, so an unready session here means the input
    // finished: there is no editor left to forward to.
    if (!ready && pendingBackend != null) return
    val active = session.takeIf { ready }
    val up = event.action == KeyEvent.ACTION_UP
    val downSwallowed = up && swallowedHardwareDowns.remove(event.keyCode)
    if (stroke == null || active == null || hardwareBypass) {
        if (!downSwallowed) callbacks.forwardKey(event)
        return
    }
    if (!up) {
        enterHardwareTyping(active)
        kana12Reset()
        doubleSpaceArmedAt = DoubleSpacePeriod.NOT_ARMED
        candidates.dismissPopup()
        if (!restoreLockBeforeInput(active)) {
            swallowedHardwareDowns += event.keyCode
            return
        }
    }
    if (active.layout.keyFold != KeyboardLayoutKind.KeyFold.IDENTITY) {
        // The full keyboard was refused: the engine would read folded codes, not letters.
        if (!downSwallowed) callbacks.forwardKey(event)
        return
    }
    val consumed = active.rawKey(stroke.keycode, stroke.modifiers)
    if (consumed == null) {
        if (!downSwallowed) callbacks.forwardKey(event)
        return
    }
    if (up) {
        if (!downSwallowed && fieldStillCurrent()) callbacks.forwardKey(event)
    } else if (consumed || active.snapshot.isComposing) {
        swallowedHardwareDowns += event.keyCode
    } else {
        swallowedHardwareDowns -= event.keyCode
        if (fieldStillCurrent()) callbacks.forwardKey(event)
    }
    resetEnglishWord()
    enforceLostLearningProtection(active)
}

/**
 * The first hardware key of an attach: the session leaves the keyboard's own English mode for
 * Rime's ASCII mode (so the scheme's switch key works), and runs the input method on the full
 * keyboard for a visit that is not stored as the user's layout.
 */
internal suspend fun KeyboardController.enterHardwareTyping(active: InputSession) {
    if (hardwareTyping) return
    hardwareTyping = true
    try {
        if (active.mode == InputMode.ENGLISH && !policy.ascii && !privateDirectInput) {
            active.setMode(InputMode.CHINESE)
            active.setOption("ascii_mode", true)
            symbolMarksOverride = null
        }
        if (active.layout != KeyboardLayoutKind.QWERTY26) {
            active.selectLayout(KeyboardLayoutKind.QWERTY26, preferredSchema)
            syncSelection(active)
            updateSwipe(active)
            visitingLayout = layoutKind != preferredLayout
            syncMenu()
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        callbacks.error(error)
    }
    render()
}
