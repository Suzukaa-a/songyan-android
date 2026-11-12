// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout

/**
 * Debug-only stand-in for the keyboard window: a keyboard-sized view plus the container an
 * extension panel is added to. The instrumented test drives [AddonPanelController] against it, so
 * the real host code (SurfaceView, host token, SurfacePackage, editor operations) is exercised on
 * a device without having to type into a live keyboard.
 */
public class AddonPanelHostProbeActivity : Activity() {
    public lateinit var container: FrameLayout
        private set

    public lateinit var keyboardView: View
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = FrameLayout(this)
        keyboardView =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xFF202124.toInt())
            }
        container.addView(
            keyboardView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, keyboardHeightPx()),
        )
        setContentView(container)
    }

    private fun keyboardHeightPx(): Int = (360 * resources.displayMetrics.density).toInt()
}
