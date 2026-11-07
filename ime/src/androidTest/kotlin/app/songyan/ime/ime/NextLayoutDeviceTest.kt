package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The 布局 key (next layout) only visits layouts the keyboard menu offers. */
@RunWith(AndroidJUnit4::class)
class NextLayoutDeviceTest {
    private val nextLayout = LayoutAction(LayoutActionKind.MODE_SWITCH, "nextLayout")

    @Test
    fun aDisabledLayoutNeverComesUp() {
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.hiddenLayoutsFlow.value = setOf(KeyboardLayoutKind.MERGED14.wire)
            host.start()
            // Let the preference collector apply the hidden set before pressing.
            assertTrue(host.await { host.main2 { host.controller.menuHiddenLayouts.isNotEmpty() } })
            val visited = mutableListOf<KeyboardLayoutKind>()
            repeat(KeyboardLayoutKind.entries.size * 2) {
                host.main { host.controller.enqueueKey(nextLayout) }
                host.awaitIdle()
                visited += host.main2 { host.controller.layoutKind }
            }
            assertFalse(visited.toString(), KeyboardLayoutKind.MERGED14 in visited)
            assertEquals(KeyboardLayoutKind.entries.toSet() - KeyboardLayoutKind.MERGED14, visited.toSet())
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    @Test
    fun aRefusedLayoutKeepsTheSavedChoiceAndComposition() {
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.start()
            host.main { host.controller.menu.onSchema("qwerty_only") }
            assertTrue(host.await { host.main2 { host.controller.schemaId } == "qwerty_only" })
            host.type("ni")
            assertTrue(host.await { "你" in host.barLabels() })
            host.main {
                host.controller.openKeyboardMenu()
                host.controller.menu.onLayout(KeyboardLayoutKind.PHONE9)
            }
            host.awaitIdle()
            assertEquals(KeyboardLayoutKind.QWERTY26, host.main2 { host.controller.layoutKind })
            assertEquals(
                "qwerty26",
                runBlocking {
                    host.controller.preferences!!
                        .layoutId
                        .first()
                },
            )
            host.assertText("ni")
            host.main { host.controller.closeKeyboardMenu() }
            host.tapBar("你")
            host.assertText("你")
        } finally {
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    @Test
    fun aRefusedInputMethodKeepsTheSavedChoice() {
        val host = FeatureHost(engine.backend(), "phone9")
        try {
            host.start()
            val saved =
                runBlocking {
                    host.controller.preferences!!
                        .activeSchemaId
                        .first()
                }
            host.main { host.controller.menu.onSchema("qwerty_only") }
            host.awaitIdle()
            assertEquals("songyan_quanpin", host.main2 { host.controller.schemaId })
            assertEquals(
                saved,
                runBlocking {
                    host.controller.preferences!!
                        .activeSchemaId
                        .first()
                },
            )
            host.main { host.controller.menu.onLayout(KeyboardLayoutKind.QWERTY26) }
            assertTrue(host.await { host.main2 { host.controller.layoutKind } == KeyboardLayoutKind.QWERTY26 })
            host.main { host.controller.menu.onSchema("qwerty_only") }
            assertTrue(host.await { host.main2 { host.controller.schemaId } == "qwerty_only" })
            host.type("ni")
            assertTrue(host.await { "你" in host.barLabels() })
            host.tapBar("你")
            host.assertText("你")
        } finally {
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine =
                DeployedEngine("next-layout", english = false) { source ->
                    // An imported source scheme without generated compact companions supports only 26 keys.
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
            engine.delete()
        }
    }
}
