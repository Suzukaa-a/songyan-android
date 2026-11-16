package app.songyan.ime.settings

import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.KeyboardLayoutPreview
import app.songyan.ime.ime.KeyboardView
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.state.InMemoryLayoutLibrarySource
import app.songyan.ime.settings.ui.LayoutEditorViewModel
import app.songyan.ime.settings.ui.LocalLayoutPreview
import app.songyan.ime.settings.ui.screens.LayoutEditorScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The layout editor's preview is the keyboard's own key view: Shift and Delete flank the z row,
 * the a row is inset, and the bottom row is the keyboard's, selected and edited in place.
 */
@RunWith(AndroidJUnit4::class)
class LayoutEditorPreviewDeviceTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(
        id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)

    private fun show(layoutId: String) {
        val source = InMemoryLayoutLibrarySource(LayoutRegistry.create())
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalLayoutPreview provides KeyboardLayoutPreview) {
                    LayoutEditorScreen(
                        layoutId = layoutId,
                        onClose = {},
                        viewModel =
                            viewModel(
                                factory =
                                    object : ViewModelProvider.Factory {
                                        @Suppress("UNCHECKED_CAST")
                                        override fun <T : ViewModel> create(
                                            modelClass: Class<T>,
                                            extras: CreationExtras,
                                        ): T = LayoutEditorViewModel(source, layoutId, newId = { "copy" }) as T
                                    },
                            ),
                    )
                }
            }
        }
        compose.waitUntil(10_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test.hasContentDescription(
                        if (layoutId ==
                            "phone9"
                        ) {
                            keyDescription(1, 2, "ABC")
                        } else {
                            keyDescription(1, 1, "q")
                        },
                    ),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun keyDescription(
        row: Int,
        key: Int,
        label: String,
    ) = str(R.string.layout_editor_key_description, row, key, label)

    private fun keyNode(
        row: Int,
        key: Int,
        label: String,
    ) = compose.onNodeWithContentDescription(keyDescription(row, key, label))

    /** The keyboard view inside the editor, and each key it drew: its spoken name and touch area. */
    private fun drawnKeys(): List<Pair<String, Rect>> {
        var keys: List<Pair<String, Rect>> = emptyList()
        compose.runOnIdle {
            val view = requireNotNull(find(compose.activity.window.decorView)) { "no keyboard view in the editor" }
            val provider = requireNotNull(view.accessibilityNodeProvider)
            keys =
                generateSequence(0) { it + 1 }
                    .map { provider.createAccessibilityNodeInfo(it) }
                    .takeWhile { it != null }
                    .map { node ->
                        val rect = Rect()
                        node!!.getBoundsInParent(rect)
                        (node.contentDescription?.toString() ?: "") to rect
                    }.toList()
        }
        return keys
    }

    private fun find(view: View): KeyboardView? {
        if (view is KeyboardView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        }
        return null
    }

    @Test
    fun shiftAndDeleteFlankTheZRowAndTheBottomRowIsTheKeyboards() {
        show("qwerty26")
        val keys = drawnKeys()
        val labels = keys.map { it.first }
        val z = labels.indexOf("z")
        val m = labels.indexOf("m")
        assertTrue(labels.toString(), z > 0 && m > z)
        val shift = keys[z - 1]
        val delete = keys[m + 1]
        assertEquals(str(app.songyan.ime.ime.R.string.ime_shift), shift.first)
        assertEquals(str(app.songyan.ime.ime.R.string.ime_delete), delete.first)
        // Shift and Delete stand in z's row, not in the a row above it.
        assertEquals(keys[z].second.top, shift.second.top)
        assertEquals(keys[z].second.top, delete.second.top)
        assertTrue(shift.second.right <= keys[z].second.left)
        assertTrue("a sits above z's row", keys[labels.indexOf("a")].second.bottom <= keys[z].second.top)
        // The a row is inset by half a key against q: the keycaps the editor outlines are the drawn ones.
        val a = keyNode(2, 1, "a").fetchSemanticsNode().boundsInRoot
        val q = keyNode(1, 1, "q").fetchSemanticsNode().boundsInRoot
        assertTrue("a row not inset: a=$a q=$q", a.left - q.left > q.width / 3)

        // The bottom row is the keyboard's own: 123 中/英 ， 空格 。 and the action key.
        keyNode(4, 1, "123").assertIsDisplayed()
        keyNode(4, 3, "，").assertIsDisplayed()
        keyNode(4, 4, "空格").assertIsDisplayed()
        keyNode(4, 5, "。").assertIsDisplayed()
        val space = keys.first { it.first == str(app.songyan.ime.ime.R.string.ime_space) }.second
        assertTrue("space bar is below z's row", space.top >= keys[z].second.bottom)

        // Tapping a bottom-row key selects it for editing.
        keyNode(4, 4, "空格").performClick()
        compose.onNodeWithText(str(R.string.layout_editor_selected_section, "空格")).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun theNineKeyEditorDrawsTheKeypadShellAndSelectsItsGrid() {
        show("phone9")
        keyNode(1, 2, "ABC").assertIsDisplayed()
        keyNode(3, 3, "WXYZ").performClick()
        compose
            .onNodeWithText(
                str(R.string.layout_editor_selected_section, "WXYZ"),
            ).performScrollTo()
            .assertIsDisplayed()
        // The shell's own bottom row is the draft's bottom row.
        keyNode(4, 1, "123").assertIsDisplayed()
    }
}
