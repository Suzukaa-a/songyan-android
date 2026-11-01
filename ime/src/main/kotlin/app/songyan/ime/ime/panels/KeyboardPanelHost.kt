package app.songyan.ime.ime.panels

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import app.songyan.ime.ime.ImeMotion
import app.songyan.ime.ime.KeyboardPalette

/**
 * The [QuickContentHost] for a keyboard whose key area lives in a vertical container.
 *
 * A panel replaces the key surface without resizing the keyboard window: the container always
 * keeps its own content height, so the editor never scrolls under the user's text. The panel takes
 * the candidate bar (with its reading row) and the key area together and starts at the window's top,
 * also while a composition is running: the candidates are not usable under a panel, and the
 * composition stays in the editor and is confirmed by whatever the panel inserts.
 */
class KeyboardPanelHost(
    private val container: LinearLayout,
    private val keyArea: View,
    /** The candidate bar above [keyArea]; hidden while a panel is open. */
    private val candidateBar: View? = null,
) : QuickContentHost {
    private var panel: View? = null

    /** A panel that is animating out; it no longer counts as showing. */
    private var leaving: View? = null

    // What the key area and the bar showed before a panel covered them: the keyboard hides the bar
    // itself on some pages (the dial pad), and closing a panel must not bring it back there.
    private var keyAreaVisibility = View.VISIBLE
    private var candidateBarVisibility = View.VISIBLE

    /**
     * The panel stopped covering the key area. Every close path (its own close button, a phrase
     * insert, a settings row) runs through [hide], so the keyboard hears about it exactly once.
     */
    var onHidden: () -> Unit = {}

    override val isShowing: Boolean get() = panel != null

    /** The height the panel takes: the candidate bar and the key area. */
    private fun surfaceHeight(): Int {
        val barHeight =
            candidateBar?.height?.takeIf { it > 0 }
                ?: fallbackBarHeight()
        val keyHeight =
            keyArea.height.takeIf { it > 0 }
                ?: keyArea.measuredHeight.takeIf { it > 0 }
                ?: fallbackKeyHeight()
        return keyHeight + barHeight
    }

    /** The design heights, for a panel opened before the keyboard's first layout pass. */
    private fun fallbackKeyHeight(): Int {
        val tokens = KeyboardPalette.from(container.context).tokens
        return ((KEY_ROWS * tokens.rowHeightDp + tokens.keyAreaBottomGapDp + tokens.keyAreaExtraBottomDp) * density())
            .toInt()
    }

    private fun fallbackBarHeight(): Int =
        (KeyboardPalette.from(container.context).tokens.candidateBarHeightDp * density()).toInt()

    private fun density(): Float = container.resources.displayMetrics.density

    override fun show(panel: View) {
        if (this.panel === panel) return
        finishLeaving()
        if (this.panel == null) {
            keyAreaVisibility = keyArea.visibility
            candidateBarVisibility = candidateBar?.visibility ?: View.GONE
        }
        // Replacing one panel with another is not a close: the old one leaves without an animation
        // and without reporting a hidden panel, because the key area is about to be covered again.
        this.panel?.let { remove(it) }
        this.panel = null
        (panel as? QuickContentPanelView)?.onClose = { hide() }
        keyArea.visibility = View.GONE
        candidateBar?.visibility = View.GONE
        container.addView(
            panel,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, surfaceHeight()),
        )
        this.panel = panel
        if (ImeMotion.enabled) {
            panel.alpha = 0f
            panel.translationY = slidePx()
            panel
                .animate()
                .alpha(1f)
                .translationY(0f)
                // A platform animator already scales itself by the system animator duration scale.
                .setDuration(ImeMotion.PANEL_MS)
                .setInterpolator(ImeMotion.standard)
                .start()
        } else {
            panel.alpha = 1f
            panel.translationY = 0f
        }
    }

    override fun hide() {
        val current = panel ?: return
        panel = null
        if (!ImeMotion.enabled) {
            remove(current)
            onHidden()
            return
        }
        leaving = current
        current
            .animate()
            .alpha(0f)
            .translationY(slidePx())
            .setDuration(ImeMotion.PANEL_MS)
            .setInterpolator(ImeMotion.standard)
            .withEndAction {
                if (leaving === current) {
                    leaving = null
                    remove(current)
                }
            }.start()
        onHidden()
    }

    override fun settle() = finishLeaving()

    /** A panel replaced before its exit finished is removed at once, not animated twice. */
    private fun finishLeaving() {
        val current = leaving ?: return
        leaving = null
        current.animate().cancel()
        remove(current)
    }

    private fun remove(panel: View) {
        container.removeView(panel)
        keyArea.visibility = keyAreaVisibility
        candidateBar?.visibility = candidateBarVisibility
    }

    private fun slidePx(): Float = SLIDE_DP * container.resources.displayMetrics.density

    private companion object {
        /** Small enough to read as the panel settling in, not as the keyboard moving. */
        const val SLIDE_DP = 6f

        /** Key rows of the keyboard, for the height budget used before the first layout pass. */
        const val KEY_ROWS = 4
    }
}
