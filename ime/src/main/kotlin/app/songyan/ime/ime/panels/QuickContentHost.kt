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
     * Ends a closing panel's exit at once, so the key area is back before the keyboard covers it
     * with something else; otherwise the exit's end would bring the key area back over it.
     */
    fun settle()

    /** Whether a panel currently covers the key area. */
    val isShowing: Boolean
}
