package app.songyan.ime.ime.panels

import android.view.View

/**
 * The keyboard-side seam for the quick-content panels.
 *
 * The panels are self-contained views; the keyboard only has to place one over the key area,
 * tell it to hide, and report that state so back navigation closes the panel before the keyboard.
 * Keeping the seam this small means the keyboard's own layout code does not grow a panel
 * implementation; `KeyboardController` can satisfy it in a handful of lines.
 */
interface QuickContentHost {
    /** Shows [panel] instead of the key area. Only one panel is visible at a time. */
    fun show(panel: View)

    /** Hides the visible panel, if any, and restores the key area. */
    fun hide()

    /**
     * Ends a closing panel's visual exit before another surface, such as the keyboard menu,
     * covers the same area.
     */
    fun settle()

    /** Whether a panel currently covers the key area. */
    val isShowing: Boolean
}
