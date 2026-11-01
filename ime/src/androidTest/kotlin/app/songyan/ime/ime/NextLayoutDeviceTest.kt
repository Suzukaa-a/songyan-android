package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

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

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("next-layout", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
