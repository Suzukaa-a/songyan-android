package app.songyan.ime.ime

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.LayoutActionKind
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** After a switch the space bar names the new keyboard for a moment, then shows its usual face. */
@RunWith(AndroidJUnit4::class)
class SwitchNoticeDeviceTest {
    private fun spaceFace(host: FeatureHost): String =
        host.main2 {
            val keyboard = host.controller.keyboard
            keyboard.face(keyboard.keys.first { it.action.type == LayoutActionKind.SPACE }, spoken = false)
        }

    @Test
    fun theSpaceBarNamesTheNewKeyboardThenItsUsualFace() {
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.start()
            host.awaitIdle()
            val usual = spaceFace(host)
            assertTrue("starting the keyboard is not a switch", !usual.contains(" · "))

            host.main { host.controller.menu.onSchema("notice_probe") }
            assertTrue(host.await { host.main2 { host.controller.schemaId } == "notice_probe" })
            assertTrue(host.await { spaceFace(host).startsWith("通知探针 · ") })

            // A composition takes the space bar back: it names what space does next.
            host.type("ni")
            assertTrue(host.await { "你" in host.barLabels() })
            assertTrue(host.await { !spaceFace(host).contains(" · ") })

            SystemClock.sleep(SWITCH_NOTICE_MS + 500)
            host.tapBar("你")
            host.assertText("你")
            host.await { spaceFace(host) == usual }
            assertEquals(usual, spaceFace(host))

            host.main { host.controller.menu.onSchema("songyan_quanpin") }
            assertTrue(host.await { host.main2 { host.controller.schemaId } == "songyan_quanpin" })
            assertTrue(host.await { spaceFace(host).contains(" · ") })
            val qwerty = spaceFace(host)
            // A layout switch names the new layout, too.
            host.main { host.controller.menu.onLayout(KeyboardLayoutKind.MERGED14) }
            assertTrue(host.await { host.main2 { host.controller.layoutKind } == KeyboardLayoutKind.MERGED14 })
            assertTrue(host.await { spaceFace(host).contains(" · ") && spaceFace(host) != qwerty })
            SystemClock.sleep(SWITCH_NOTICE_MS + 500)
            assertTrue(host.await { !spaceFace(host).contains(" · ") })

            // Picking the running input method again switches nothing, so nothing is announced.
            host.main { host.controller.menu.onSchema("songyan_quanpin") }
            host.awaitIdle()
            assertTrue(!spaceFace(host).contains(" · "))
            assertEquals(1f, host.main2 { host.controller.keyboard.alpha })
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
                DeployedEngine("switch-notice", english = false) { source ->
                    File(source, "notice_probe.schema.yaml").writeText(
                        File(source, "songyan_quanpin.schema.yaml")
                            .readText()
                            .replace("schema_id: songyan_quanpin", "schema_id: notice_probe")
                            .replaceFirst(Regex("(?m)^(\\s*name:).*$"), "$1 通知探针"),
                    )
                    File(source, "default.yaml").appendText("  - schema: notice_probe\n")
                }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
