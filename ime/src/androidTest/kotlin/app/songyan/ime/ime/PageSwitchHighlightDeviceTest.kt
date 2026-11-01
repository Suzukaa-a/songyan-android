package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.layout.KeyboardPageKind
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A page key fades out after the finger lifts, but the page it opens has other keys in those slots:
 * none of them may light up (123 lit h, 返回 lit the 中/英 key).
 */
@RunWith(AndroidJUnit4::class)
class PageSwitchHighlightDeviceTest {
    private fun FeatureHost.fadingKeys(): List<Int> =
        main2 {
            val view = find(KeyboardView::class.java)
            view.releases.indices.filter { view.releases[it] != 0L }
        }

    @Test
    fun noKeyOfTheOpenedPageInheritsThePageKeysFade() =
        withHost("qwerty26") { host ->
            host.start()
            host.hold(host.string(R.string.ime_open_numbers_page), HOLD_MS) {}
            assertTrue(host.await { host.main2 { host.controller.page } == KeyboardPageKind.NUMERIC })
            host.awaitIdle()
            assertEquals("fading on the 123 page", emptyList<Int>(), host.fadingKeys())
            host.hold(host.string(R.string.ime_return_to_letters), HOLD_MS) {}
            assertTrue(host.await { host.main2 { host.controller.page } == null })
            host.awaitIdle()
            assertEquals("fading on the letters", emptyList<Int>(), host.fadingKeys())
        }

    @Test
    fun theNineKeyPadsPageKeysLeaveNoFadeOnThePageTheyOpen() =
        withHost("phone9") { host ->
            host.start()
            host.hold(host.string(R.string.ime_open_numbers_page), HOLD_MS) {}
            assertTrue(host.await { host.main2 { host.controller.page } == KeyboardPageKind.NUMERIC })
            host.awaitIdle()
            assertEquals("fading on the digit pad", emptyList<Int>(), host.fadingKeys())
            host.hold(host.string(R.string.ime_return_to_letters), HOLD_MS) {}
            assertTrue(host.await { host.main2 { host.controller.page } == null })
            host.awaitIdle()
            assertEquals("fading on the 9-key letters", emptyList<Int>(), host.fadingKeys())
        }

    private fun <T> withHost(
        layout: String,
        block: (FeatureHost) -> T,
    ): T {
        val host = FeatureHost(engine.backend(), layout)
        try {
            return block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    companion object {
        private const val HOLD_MS = 30L
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("page-highlight", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
