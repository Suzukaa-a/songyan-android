package app.songyan.ime.ime

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.layout.UserKeyboards
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The keyboard menu lists the user's keyboards, drawn and typed on the real engine. */
@RunWith(AndroidJUnit4::class)
class MyKeyboardsMenuDeviceTest {
    @Test
    fun aUserWhoNeverEditedTheListSeesTheKeyboardsTheOldMenuOffered() {
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.hiddenLayoutsFlow.value = setOf(KeyboardLayoutKind.MERGED14.wire)
            host.start()
            assertTrue(host.await { host.main2 { host.controller.menuHiddenLayouts.isNotEmpty() } })
            host.main { host.controller.openKeyboardMenu() }
            val chips = host.main2 { host.chips().map { it.text.toString() } }
            val offered =
                listOf(KeyboardLayoutKind.QWERTY26, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9)
                    .map { host.string(layoutNameRes(it)) }
            assertTrue("the shapes not hidden are offered: $chips", chips.containsAll(offered))
            assertTrue(host.string(layoutNameRes(KeyboardLayoutKind.MERGED14)) !in chips)
        } finally {
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    @Test
    fun aLayoutTheUserCopiedIsItsOwnKeyboardThatDrawsAndTypes() {
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            installCopy()
            host.myKeyboardsFlow.value =
                UserKeyboards.Stored(listOf(keyboard(QUANPIN, null), keyboard(QUANPIN, COPY)), setOf(QUANPIN))
            host.start()
            host.awaitIdle()
            host.main { host.controller.openKeyboardMenu() }
            val chips = host.main2 { host.chips().map { it.text.toString() } }
            assertTrue("the copy is a keyboard: $chips", COPY_NAME in chips)
            host.main { host.chip(COPY_NAME).performClick() }
            assertTrue(host.await { host.main2 { host.controller.mainDefinition().id } == COPY })
            assertEquals(COPY, host.layoutDefinitionFlow.value)
            host.type("ni")
            assertTrue("the copy types: ${host.barLabels()}", host.await { "你" in host.barLabels() })
            // The built-in keyboard of the same shape is still there and draws its own layout.
            host.main { host.controller.openKeyboardMenu() }
            host.main { host.chip(host.string(R.string.ime_layout_qwerty26)).performClick() }
            assertTrue(host.await { host.main2 { host.controller.mainDefinition().id } == BuiltinLayouts.qwerty26.id })
        } finally {
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    @Test
    fun anInputMethodChipFollowsTheSameLayoutThenTheSameShapeThenTheFirst() {
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            installCopy()
            host.myKeyboardsFlow.value =
                UserKeyboards.Stored(
                    listOf(
                        keyboard(QUANPIN, null),
                        keyboard(QUANPIN, COPY),
                        keyboard(QUANPIN, KeyboardLayoutKind.MERGED14.wire, KeyboardLayoutKind.MERGED14),
                        keyboard(ONLY, null),
                        keyboard(ONLY, COPY),
                    ),
                    setOf(QUANPIN, ONLY),
                )
            host.start()
            host.awaitIdle()
            host.main { host.controller.menu.onKeyboard(keyboard(QUANPIN, COPY)) }
            assertTrue(host.await { host.main2 { host.controller.mainDefinition().id } == COPY })
            host.main { host.controller.menu.onSchema(ONLY) }
            assertTrue(host.await { host.main2 { host.controller.schemaId } == ONLY })
            assertEquals("the same layout id wins over the first keyboard", COPY, host.layoutDefinitionFlow.value)

            host.main { host.controller.menu.onSchema(QUANPIN) }
            assertTrue(host.await { host.main2 { host.controller.schemaId } == QUANPIN })
            host.main {
                host.controller.menu.onKeyboard(
                    keyboard(QUANPIN, KeyboardLayoutKind.MERGED14.wire, KeyboardLayoutKind.MERGED14),
                )
            }
            assertTrue(host.await { host.main2 { host.controller.layoutKind } == KeyboardLayoutKind.MERGED14 })
            host.main { host.controller.menu.onSchema(ONLY) }
            assertTrue(host.await { host.main2 { host.controller.schemaId } == ONLY })
            assertEquals(
                "no 14-key keyboard there: the first one",
                KeyboardLayoutKind.QWERTY26,
                host.main2 {
                    host.controller.layoutKind
                },
            )
            assertEquals(KeyboardLayoutKind.QWERTY26.wire, host.layoutDefinitionFlow.value)
        } finally {
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    private fun keyboard(
        schema: String,
        board: String?,
        kind: KeyboardLayoutKind = KeyboardLayoutKind.QWERTY26,
    ) = PublishedKeyboard(schema, kind, board ?: kind.wire)

    private fun installCopy() {
        val registry = LayoutRegistry.create().install(BuiltinLayouts.qwerty26.copyWithId(COPY, COPY_NAME).encoded())
        instrumentation.runOnMainSync { KeyboardLayoutCatalog.install(registry) }
    }

    private val instrumentation get() =
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()

    private fun layoutNameRes(kind: KeyboardLayoutKind): Int =
        when (kind) {
            KeyboardLayoutKind.QWERTY26 -> R.string.ime_layout_qwerty26
            KeyboardLayoutKind.MERGED14 -> R.string.ime_layout_merged14
            KeyboardLayoutKind.MERGED18 -> R.string.ime_layout_merged18
            KeyboardLayoutKind.PHONE9 -> R.string.ime_layout_phone9
        }

    private fun FeatureHost.chip(text: String): TextView =
        requireNotNull(chips().firstOrNull { it.text.toString() == text }) { "no chip '$text' in the menu" }

    /** Every clickable text in the menu: the chips are the only clickable TextViews. */
    private fun FeatureHost.chips(): List<TextView> {
        val found = mutableListOf<TextView>()

        fun walk(view: View) {
            if (view is TextView && view.isClickable) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(controller.menu)
        return found
    }

    companion object {
        private const val QUANPIN = "songyan_quanpin"
        private const val ONLY = "qwerty_only"
        private const val COPY = "my-26-copy"
        private const val COPY_NAME = "26 键 副本"
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine =
                DeployedEngine("my-keyboards", english = false) { source ->
                    // A source scheme without generated compact companions supports only 26 keys.
                    File(source, "qwerty_only.schema.yaml").writeText(
                        File(source, "songyan_quanpin.schema.yaml")
                            .readText()
                            .replace("schema_id: songyan_quanpin", "schema_id: qwerty_only"),
                    )
                    File(source, "default.yaml").appendText("  - schema: qwerty_only\n")
                }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            KeyboardLayoutCatalog.install(LayoutRegistry.create())
            engine.delete()
        }
    }
}
