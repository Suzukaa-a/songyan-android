package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The settings screen and the keyboard communicate the "quick content changed" event through one
 * broadcast action. It is a wire contract across modules, so its value is pinned here: renaming it
 * would silently stop a running keyboard from picking up a clipboard-history change.
 */
class QuickContentEventsContractTest {
    @Test
    fun theImeListensForTheDocumentedAction() {
        assertEquals(
            "app.songyan.ime.action.QUICK_CONTENT_CHANGED",
            QuickContentEvents.ACTION_CHANGED,
        )
    }
}
