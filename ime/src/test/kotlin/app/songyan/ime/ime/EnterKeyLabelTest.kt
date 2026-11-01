package app.songyan.ime.ime

import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The face of the Enter key: one label per platform action, and the Return face for everything that
 * is not an action the key performs. An editor with its own label never reaches this mapping.
 */
class EnterKeyLabelTest {
    private val actionLabels =
        mapOf(
            EditorInfo.IME_ACTION_GO to R.string.ime_go,
            EditorInfo.IME_ACTION_SEARCH to R.string.ime_search,
            EditorInfo.IME_ACTION_SEND to R.string.ime_send,
            EditorInfo.IME_ACTION_NEXT to R.string.ime_next,
            EditorInfo.IME_ACTION_DONE to R.string.ime_done,
            EditorInfo.IME_ACTION_PREVIOUS to R.string.ime_previous,
        )

    @Test
    fun everyActionHasItsOwnFace() {
        for ((action, label) in actionLabels) {
            assertEquals("action " + action, label, enterKeyLabelRes(action))
        }
        // Six actions, six different strings: no action can be mistaken for another.
        assertEquals(actionLabels.size, actionLabels.values.toSet().size)
    }

    @Test
    fun aReturnOrAnEditorOwnedKeyKeepsTheReturnFace() {
        assertEquals(R.string.ime_enter, enterKeyLabelRes(EditorInfo.IME_ACTION_NONE))
        assertEquals(R.string.ime_enter, enterKeyLabelRes(EditorInfo.IME_ACTION_UNSPECIFIED))
        // The custom-label sentinel is not an action code: the editor's own string is drawn instead.
        assertEquals(R.string.ime_enter, enterKeyLabelRes(EditorPolicy.ACTION_CUSTOM_LABEL))
        // A future platform action never crashes the keyboard.
        assertEquals(R.string.ime_enter, enterKeyLabelRes(99))
        assertEquals(R.string.ime_enter, enterKeyLabelRes(-99))
    }
}
