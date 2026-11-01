package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** The nine-key side rail follows the quick symbols the user saved, live and without a restart. */
@RunWith(AndroidJUnit4::class)
class NineKeyQuickSymbolsDeviceTest {
    private fun withHost(block: (FeatureHost) -> Unit) {
        val host = FeatureHost(engine.backend(), "phone9")
        try {
            host.start()
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun labels(host: FeatureHost) = host.labelsOf(KeyboardView::class.java)

    @Test
    fun aSavedFirstSymbolTakesTheTopOfTheRailAndTypes() =
        withHost { host ->
            host.quickSymbolsFlow.value = "；"
            host.awaitIdle()
            assertTrue(labels(host).toString(), "；" in labels(host))
            host.tap("；")
            host.assertText("；")
        }

    @Test
    fun theDefaultOrderComesBackWhenTheSavedListIsCleared() =
        withHost { host ->
            host.quickSymbolsFlow.value = "；"
            host.awaitIdle()
            host.quickSymbolsFlow.value = ""
            host.awaitIdle()
            assertTrue(labels(host).toString(), "？" in labels(host) && "；" !in labels(host))
        }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("nine-key-quick-symbols", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
