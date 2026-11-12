// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.ime

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The input-method menu's extension section: rows appear for the list the service provides and the
 * section disappears when it is empty. This is the UI half of "enabling an extension shows its menu
 * row on the next open"; the service-side refresh is covered by the app's device tests.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardSwitcherPanelExtensionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun hasText(
        view: View,
        text: String,
    ): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasText(view.getChildAt(index), text)) return true
            }
        }
        return false
    }

    private fun findText(
        view: View,
        text: String,
    ): TextView? {
        if (view is TextView && view.text.toString() == text) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findText(view.getChildAt(index), text)?.let { return it }
            }
        }
        return null
    }

    private fun dp(value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    private fun layout(
        view: View,
        width: Int,
        height: Int,
    ) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, width, height)
    }

    @Test
    fun extensionRowsAreVisibleWithoutScrolling() {
        val panel = KeyboardSwitcherPanel(context, "builtin", KeyboardLayoutKind.QWERTY26)
        panel.setExtensionPanels(listOf("app.songyan.ime.addon.sample" to "Sample Addon"))
        layout(panel, dp(400), dp(360))

        val scroll = panel.scroll
        assertEquals("the menu opens at its top", 0, scroll.scrollY)
        val row = requireNotNull(findText(panel, "Sample Addon"))
        val rect = Rect()
        row.getDrawingRect(rect)
        scroll.offsetDescendantRectToMyCoords(row, rect)
        assertTrue(
            "the extension row must be in the visible part of the menu: " + rect + " inside " + scroll.height,
            rect.top >= 0 && rect.bottom <= scroll.height,
        )
    }

    @Test
    fun aFirstOpenWaitsWithALoadingRowUntilTheListArrives() {
        val panel = KeyboardSwitcherPanel(context, "builtin", KeyboardLayoutKind.QWERTY26)
        panel.setExtensionPanels(emptyList(), loading = true)
        assertTrue(
            "a first open must show that the section is still coming",
            hasText(panel, context.getString(R.string.ime_menu_extensions_loading)),
        )

        panel.setExtensionPanels(listOf("app.songyan.ime.addon.sample" to "Sample Addon"))
        assertFalse(
            "the loading row goes away with the rows",
            hasText(panel, context.getString(R.string.ime_menu_extensions_loading)),
        )
        assertTrue(hasText(panel, "Sample Addon"))
    }

    @Test
    fun anExtensionRowReportsItsPackageWhenTapped() {
        val panel = KeyboardSwitcherPanel(context, "builtin", KeyboardLayoutKind.QWERTY26)
        var opened: String? = null
        panel.onExtensionPanel = { opened = it }
        panel.setExtensionPanels(listOf("app.songyan.ime.addon.sample" to "Sample Addon"))
        layout(panel, dp(400), dp(360))

        val label = requireNotNull(findText(panel, "Sample Addon"))
        (label.parent as View).performClick()
        assertEquals("app.songyan.ime.addon.sample", opened)
    }

    @Test
    fun aKnownListIsNotRebuiltWhileTheMenuStaysOpen() {
        val panel = KeyboardSwitcherPanel(context, "builtin", KeyboardLayoutKind.QWERTY26)
        panel.setExtensionPanels(listOf("app.songyan.ime.addon.sample" to "Sample Addon"))
        val rowsBefore = panel.extensionRows

        // The same list again is what a finished background scan usually brings: nothing moves.
        panel.setExtensionPanels(listOf("app.songyan.ime.addon.sample" to "Sample Addon"))
        assertEquals(rowsBefore, panel.extensionRows)
        assertFalse(panel.extensionWaiting)
        assertTrue(hasText(panel, "Sample Addon"))
    }

    @Test
    fun theExtensionSectionFollowsTheListItIsGiven() {
        val panel = KeyboardSwitcherPanel(context, "builtin", KeyboardLayoutKind.QWERTY26)
        assertFalse(hasText(panel, "Sample Addon"))

        panel.setExtensionPanels(listOf("app.songyan.ime.addon.sample" to "Sample Addon"))
        assertTrue("an enabled extension must appear as a menu row", hasText(panel, "Sample Addon"))

        panel.setExtensionPanels(emptyList())
        assertFalse("the section must disappear when no extension offers a panel", hasText(panel, "Sample Addon"))
    }
}
