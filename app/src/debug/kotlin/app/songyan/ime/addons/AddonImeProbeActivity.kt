// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Debug-only editor for smoke checks of the real keyboard window: an activity with one focused
 * text field, so "adb shell ime set" plus "am start" shows the production keyboard (with the
 * extension section of its menu and an extension panel) on a device. Never part of a release build.
 */
public class AddonImeProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val edit =
            EditText(this).apply {
                hint = "addon smoke probe"
                isSingleLine = true
            }
        val layout =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                addView(
                    TextView(this@AddonImeProbeActivity).apply { text = "Type here:" },
                )
                addView(
                    edit,
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ),
                )
            }
        setContentView(layout)
        edit.requestFocus()
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
        )
    }
}
