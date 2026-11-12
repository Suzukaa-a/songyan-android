// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.songyan.ime.addon.AddonPanelContext
import app.songyan.ime.addon.AddonPanelHost
import app.songyan.ime.addon.AddonTextQuery
import app.songyan.ime.addon.AddonTextScope
import app.songyan.ime.addon.AddonTextWrite

/**
 * The sample panel. It is an ordinary View hierarchy drawn in the addon's own process: thin rows
 * with a fixed dp height so the host's integration tests can tap them, and one button per exposed
 * editor operation (insert, delete backwards, read, ask for more room, close).
 */
internal class SamplePanelView(
    context: Context,
    private val panelHost: AddonPanelHost,
    initialContext: AddonPanelContext,
) : LinearLayout(context) {
    private val status: TextView = TextView(context)
    private var panelContext: AddonPanelContext = initialContext

    init {
        orientation = VERTICAL
        setPadding(dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP), dp(PADDING_DP))
        val dark = initialContext.darkMode
        setBackgroundColor(
            initialContext.keyboardColor.takeIf { it != 0 } ?: if (dark) DARK_BACKGROUND else LIGHT_BACKGROUND,
        )
        val textColor = if (dark) Color.WHITE else Color.BLACK

        addView(
            textView(context, context.getString(R.string.panel_title), textColor, titleHeight = true),
            LayoutParams(MATCH_PARENT, dp(TITLE_HEIGHT_DP)),
        )
        addView(
            textView(
                context,
                context.getString(
                    R.string.panel_context,
                    initialContext.inputType,
                    initialContext.widthPx,
                    initialContext.heightPx,
                ),
                textColor,
            ),
            LayoutParams(MATCH_PARENT, dp(TITLE_HEIGHT_DP)),
        )
        addButton(context, R.string.panel_commit, textColor) {
            val text = context.getString(R.string.panel_plain_text)
            val result = panelHost.insertText(AddonTextWrite(text = text, deleteBefore = 0))
            showStatus(
                if (result.applied) {
                    context.getString(
                        R.string.panel_committed,
                        text,
                    )
                } else {
                    context.getString(R.string.panel_refused)
                },
            )
        }
        addButton(context, R.string.panel_delete, textColor) {
            val result = panelHost.insertText(AddonTextWrite(text = "", deleteBefore = 1))
            showStatus(
                if (result.applied) {
                    context.getString(
                        R.string.panel_deleted,
                    )
                } else {
                    context.getString(R.string.panel_refused)
                },
            )
        }
        addButton(context, R.string.panel_read, textColor) {
            val result = panelHost.readText(AddonTextQuery(mode = AddonTextScope.SELECTION, maxUnits = 200))
            val text = result.text
            showStatus(
                if (text.isNullOrEmpty()) {
                    context.getString(
                        R.string.panel_no_text,
                    )
                } else {
                    context.getString(R.string.panel_read_result, text)
                },
            )
        }
        addButton(context, R.string.panel_resize, textColor) {
            val desired = (panelContext.heightPx * 1.5f).toInt().coerceAtLeast(panelContext.heightPx + 1)
            panelContext = panelHost.requestPanelSize(panelContext.widthPx, desired)
            showStatus(
                context.getString(
                    R.string.panel_context,
                    panelContext.inputType,
                    panelContext.widthPx,
                    panelContext.heightPx,
                ),
            )
        }
        addButton(context, R.string.panel_close, textColor) {
            panelHost.closePanel()
        }

        addView(status, LayoutParams(MATCH_PARENT, dp(TITLE_HEIGHT_DP)))
    }

    private fun showStatus(text: String) {
        status.text = text
    }

    private fun textView(
        context: Context,
        text: String,
        color: Int,
        titleHeight: Boolean = false,
    ): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(color)
            textSize = if (titleHeight) 16f else 14f
            gravity = Gravity.CENTER_VERTICAL
        }

    private fun addButton(
        context: Context,
        label: Int,
        color: Int,
        onClick: () -> Unit,
    ) {
        val button =
            Button(context).apply {
                text = context.getString(label)
                setTextColor(color)
                setOnClickListener { onClick() }
            }
        addView(button, LayoutParams(MATCH_PARENT, dp(BUTTON_HEIGHT_DP)))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal companion object {
        const val PADDING_DP: Int = 8
        const val TITLE_HEIGHT_DP: Int = 32
        const val BUTTON_HEIGHT_DP: Int = 44

        private const val DARK_BACKGROUND: Int = 0xFF1B1B1F.toInt()
        private const val LIGHT_BACKGROUND: Int = 0xFFE8E8EC.toInt()

        /** Top of the first button in dp, used by the integration tests to tap it. */
        const val FIRST_BUTTON_TOP_DP: Int = PADDING_DP + TITLE_HEIGHT_DP + TITLE_HEIGHT_DP
    }
}
