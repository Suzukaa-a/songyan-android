package app.songyan.ime.layout

import kotlin.math.abs

object KeyboardCursorDragPolicy {
    fun starts(
        settingOn: Boolean,
        isComposing: Boolean,
        isIdle: Boolean,
        ownsSidewaysGesture: Boolean,
        isSpace: Boolean,
        isDelete: Boolean,
        dxDp: Float,
        dyDp: Float,
    ): Boolean {
        // Letter swipes and kana flicks lock at 18 dp, before dwell or velocity can distinguish
        // typing from a caret drag. Only gesture ownership can preserve those typing gestures.
        // Caret dragging is only an idle fallback: holds, callouts and panels must finish their
        // own interaction before an unclaimed slide can move text's insertion point.
        if (!settingOn || isComposing || !isIdle || ownsSidewaysGesture || isSpace || isDelete) return false
        return abs(dxDp) >= 14f && abs(dxDp) > abs(dyDp)
    }
}
