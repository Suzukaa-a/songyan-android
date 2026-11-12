// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample.debug

import android.app.Activity
import android.os.Bundle
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * Debug-only stand-in for the keyboard window: an activity in the host process holding the
 * SurfaceView where the addon's panel surface is attached with setChildSurfacePackage. The addon
 * service runs in the separate ":addon" process in debug builds, so this reproduces the real
 * cross-process embedding instead of an in-process shortcut.
 */
public class PanelHostActivity : Activity() {
    public lateinit var surfaceView: SurfaceView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(this)
        surfaceView = SurfaceView(this)
        // Only a SurfaceView above its host surface receives input for the embedded hierarchy.
        surfaceView.setZOrderOnTop(true)
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT),
        )
        setContentView(root)
    }
}
