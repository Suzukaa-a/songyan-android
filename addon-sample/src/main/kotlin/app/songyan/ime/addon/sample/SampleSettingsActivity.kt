// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample

import android.app.Activity
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The addon's own settings page. Songyan only launches it; the settings themselves belong to the
 * addon, which keeps this out of the keyboard process and out of the host's storage.
 */
public class SampleSettingsActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
            }
        content.addView(
            TextView(this).apply {
                text = getString(R.string.settings_title)
                textSize = 24f
            },
        )
        content.addView(
            TextView(this).apply {
                text = getString(R.string.settings_explain)
                textSize = 16f
                setPadding(0, 24, 0, 0)
            },
        )
        setContentView(content)
    }
}
