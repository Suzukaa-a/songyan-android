// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.ime

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
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
    fun theWaitingRowHasTheSameMetricsAsAnActionRow() {
        val panel = KeyboardSwitcherPanel(context, "builtin", KeyboardLayoutKind.QWERTY26)
        panel.setExtensionPanels(emptyList(), loading = true)
        layout(panel, dp(400), dp(500))

        val placeholder = requireNotNull(findText(panel, context.getString(R.string.ime_menu_extensions_loading)))
        val placeholderRow = placeholder.parent as ViewGroup
        val action = requireNotNull(findText(panel, context.getString(R.string.ime_menu_quick_content)))
        val actionRow = action.parent as ViewGroup
        assertEquals("the waiting row keeps the action row height", actionRow.height, placeholderRow.height)
        assertEquals(actionRow.paddingTop, placeholderRow.paddingTop)
        assertEquals(actionRow.paddingBottom, placeholderRow.paddingBottom)

        // The rows that replace it keep the same shape: label plus the action rows' chevron.
        panel.setExtensionPanels(listOf("app.songyan.ime.addon.sample" to "Sample Addon"))
        layout(panel, dp(400), dp(500))
        val addon = requireNotNull(findText(panel, "Sample Addon")).parent as ViewGroup
        assertEquals("an extension row has the action row's shape", actionRow.childCount, addon.childCount)
        assertTrue("an extension row ends in the usual chevron", addon.getChildAt(1) is ImageView)
    }

    @Test
    fun swappingTheWaitingRowForRowsKeepsTheSectionInPlace() {
        val panel = KeyboardSwitcherPanel(context, "builtin", KeyboardLayoutKind.QWERTY26)
        panel.setExtensionPanels(emptyList(), loading = true)
        layout(panel, dp(400), dp(500))
        val titleBefore = requireNotNull(findText(panel, context.getString(R.string.ime_menu_extensions)))
        val titleTop = titleBefore.top
        val placeholder = requireNotNull(findText(panel, context.getString(R.string.ime_menu_extensions_loading)))
        val section = placeholder.parent.parent as ViewGroup
        val sectionTop = section.top

        panel.setExtensionPanels(listOf("a" to "Addon A", "b" to "Addon B"))
        layout(panel, dp(400), dp(500))

        assertFalse("the waiting row is gone", hasText(panel, context.getString(R.string.ime_menu_extensions_loading)))
        assertTrue(hasText(panel, "Addon A"))
        assertEquals("the title does not move when the rows arrive", titleTop, titleBefore.top)
        assertEquals("the section keeps its place", sectionTop, section.top)
        assertEquals("both rows are in the section", 2, section.childCount)
        assertEquals("the section never hides during the swap", View.VISIBLE, section.visibility)
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
