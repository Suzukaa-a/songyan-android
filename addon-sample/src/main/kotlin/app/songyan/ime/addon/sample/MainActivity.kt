// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** A plain launcher screen so the sample is an installable, explainable app. */
public class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(48, 48, 48, 48)
                gravity = Gravity.START
            }
        content.addView(title(getString(R.string.app_name)))
        content.addView(body(getString(R.string.sample_explain)))
        content.addView(body(getString(R.string.sample_tools)))
        content.addView(body(getString(R.string.sample_panel)))
        content.addView(body(getString(R.string.sample_voice)))
        content.addView(
            Button(this).apply {
                setText(R.string.settings_title)
                setOnClickListener {
                    startActivity(Intent(this@MainActivity, SampleSettingsActivity::class.java))
                }
            },
            LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = 32 },
        )
        setContentView(ScrollView(this).apply { addView(content, MATCH_PARENT, WRAP_CONTENT) })
    }

    private fun title(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 24f
        }

    private fun body(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 16f
            setPadding(0, 16, 0, 0)
        }
}
