package app.songyan.ime.layout

import kotlin.math.abs

object KeyboardCursorDragPolicy {
    fun starts(
        settingOn: Boolean,
        isComposing: Boolean,
        ownsSidewaysGesture: Boolean,
        isSpace: Boolean,
        isDelete: Boolean,
        dxDp: Float,
        dyDp: Float,
    ): Boolean {
        // Letter swipes and kana flicks lock at 18 dp, before dwell or velocity can distinguish
        // typing from a caret drag. Only gesture ownership can preserve those typing gestures.
        if (!settingOn || isComposing || ownsSidewaysGesture || isSpace || isDelete) return false
        return abs(dxDp) >= 14f && abs(dxDp) > abs(dyDp)
    }
}
