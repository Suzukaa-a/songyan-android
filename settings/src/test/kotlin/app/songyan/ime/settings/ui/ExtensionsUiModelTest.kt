// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.settings.ui

import app.songyan.ime.settings.state.AddonToolInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one rule of the 扩展 page that depends on the host's build type: a debug host lists the
 * developer-only tools an extension declares, a release host drops them so the shipped keyboard
 * never advertises tools that only exist in a developer build.
 */
class ExtensionsUiModelTest {
    private val tools =
        listOf(
            AddonToolInfo("uppercase", "Uppercase"),
            AddonToolInfo("delay", "Delayed result (debug only)"),
            AddonToolInfo("crash", "Crash the addon (debug only)"),
            AddonToolInfo("bookTitle", "Add book-title marks"),
        )

    @Test
    fun aDebugHostListsEveryDeclaredTool() {
        assertEquals(tools, ExtensionsUiModel.visibleTools(tools, releaseHost = false))
    }

    @Test
    fun aReleaseHostDropsDeveloperOnlyTools() {
        assertEquals(
            listOf("uppercase", "bookTitle"),
            ExtensionsUiModel.visibleTools(tools, releaseHost = true).map { it.id },
        )
    }

    @Test
    fun theMarkerIsMatchedWithoutCase() {
        assertTrue(ExtensionsUiModel.isDebugOnlyTool("Crash the addon (DEBUG ONLY)"))
        assertFalse(ExtensionsUiModel.isDebugOnlyTool("Uppercase"))
    }
}
