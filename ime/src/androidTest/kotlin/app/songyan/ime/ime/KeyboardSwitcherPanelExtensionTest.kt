// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.ime

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
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
