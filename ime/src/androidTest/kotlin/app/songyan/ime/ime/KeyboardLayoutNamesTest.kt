package app.songyan.ime.ime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageJump
import app.songyan.ime.layout.LayoutPageTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard's own surfaces word the two shipped physical boards by their own names: the layout
 * menu chips, the settings panel tile and the jump key's spoken name never borrow "26 keys".
 */
@RunWith(AndroidJUnit4::class)
class KeyboardLayoutNamesTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aBundledPhysicalBoardIsNamedByItsResourceInsteadOfTheShapeName() {
        assertEquals(
            context.getString(R.string.ime_layout_zhuyin41),
            layoutName(context, BuiltinLayouts.zhuyin41, KeyboardLayoutKind.QWERTY26),
        )
        assertEquals(
            context.getString(R.string.ime_layout_cangjie26),
            layoutName(context, BuiltinLayouts.cangjie26, KeyboardLayoutKind.QWERTY26),
        )
        // The four shipped shapes keep the names they already had.
        assertEquals(
            context.getString(R.string.ime_layout_qwerty26),
            layoutName(context, BuiltinLayouts.qwerty26, KeyboardLayoutKind.QWERTY26),
        )
        assertEquals(
            context.getString(R.string.ime_layout_phone9),
            layoutName(context, BuiltinLayouts.phone9, KeyboardLayoutKind.PHONE9),
        )
    }

    @Test
    fun anAuthorLayoutKeepsItsOwnNameAndUnknownIdsAreNotRenamed() {
        val mine = BuiltinLayouts.zhuyin41.copyWithId("mine", "我的注音")
        assertEquals("我的注音", layoutName(context, mine, KeyboardLayoutKind.QWERTY26))
        // Editing a bundled board makes it the author's own: the saved name wins over the resource.
        assertEquals("我的注音", layoutName(context, BuiltinLayouts.zhuyin41.renamed("我的注音"), KeyboardLayoutKind.QWERTY26))
        // No definition yet keeps the shape name, as every surface did before the boards shipped.
        assertEquals(
            context.getString(R.string.ime_layout_qwerty26),
            layoutName(context, null, KeyboardLayoutKind.QWERTY26),
        )
        for (id in listOf("qwerty26", "merged14", "phone9", "mine", "")) {
            assertNull(id, bundledLayoutNameRes(id))
        }
        assertNull(bundledLayoutNameRes(null))
    }

    @Test
    fun theLayoutChipShowsTheBoardNameHandedToTheMenu() {
        val panel = KeyboardSwitcherPanel(context, BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.QWERTY26)
        panel.setLayoutNames(mapOf(KeyboardLayoutKind.QWERTY26 to context.getString(R.string.ime_layout_zhuyin41)))
        val labels = texts(panel)
        assertEquals(true, labels.contains(context.getString(R.string.ime_layout_zhuyin41)))
        assertEquals(false, labels.contains(context.getString(R.string.ime_layout_qwerty26)))
    }

    @Test
    fun theJumpKeyNamesThePhysicalBoardItBelongsTo() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val names = KeyNames(context).chinese()
            val zhuyin = names.getString(R.string.ime_jump_to_numbers, names.getString(R.string.ime_layout_zhuyin41))
            val shape = names.getString(R.string.ime_jump_to_numbers, names.getString(R.string.ime_layout_qwerty26))

            val view = KeyboardView(context)
            view.faces = KeyboardFaces(mainLayoutId = BuiltinLayouts.zhuyin41.id)
            assertEquals(zhuyin, view.jumpName(jumpKey()))

            // Without a resolved main layout the shape name stays, exactly as before the change.
            val plain = KeyboardView(context)
            assertEquals(shape, plain.jumpName(jumpKey()))
        }
    }

    private fun jumpKey(): LayoutKey =
        LayoutKey(
            label = "123",
            action =
                LayoutAction.layoutPageSwitch(
                    LayoutPageJump(LayoutInput.Builtin.QWERTY26, LayoutPageTarget.NUMERIC),
                ),
        )

    private fun texts(view: android.view.View): List<String> =
        buildList {
            if (view is android.widget.TextView) add(view.text.toString())
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) addAll(texts(view.getChildAt(index)))
            }
        }
}
