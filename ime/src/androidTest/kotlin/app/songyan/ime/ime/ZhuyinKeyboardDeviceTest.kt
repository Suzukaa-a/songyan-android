package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The Dachen zhuyin keyboard over the real bopomofo package and the shipped editor wiring.
 *
 * The scheme's family decides the physical layout, so the keyboard draws the zhuyin caps without a
 * stored pairing; the four-row layout, the caps' codes and the composition display are the shipped
 * data. The tests type through the keyboard's own key actions and read the platform editor, so every
 * commitment below is what the engine told the editor, not what a test stub was told to return.
 *
 * The real scheme package is not in the repository: its pinned archives are pushed into this test
 * package's cache before the explicit acceptance run (see [TraditionalSchemeFixture]). A missing
 * archive fails the test instead of turning it green without typing.
 */
@RunWith(AndroidJUnit4::class)
class ZhuyinKeyboardDeviceTest {
    @Test
    fun thePreeditShowsZhuyinNotKeyCodes() =
        withZhuyin { host ->
            host.start()
            host.typeCodes("su3cl3")
            host.assertText("ㄋㄧˇ ㄏㄠˇ")
            assertTrue("the composition is marked text", host.editor.composing)
            val shown = host.text()
            assertTrue("the field must show zhuyin, not the ASCII keys: '$shown'", shown.any { it.code > 0x7E })
            assertTrue("no key code leaks into the preedit: '$shown'", shown.none { it.code in 0x21..0x7E })
            assertEquals(host.backend.currentSnapshot().preedit, shown)
            val labels = host.labelsOf(KeyboardView::class.java)
            assertTrue("the zhuyin caps are drawn: $labels", "ㄅ" in labels && "ㄋ" in labels && "ㄧ" in labels)
            assertFalse("no ASCII letter cap is drawn: $labels", labels.any { it.length == 1 && it[0] in 'a'..'z' })
        }

    @Test
    fun choosingTheWholeWordCommitsItInTheEditor() =
        withZhuyin { host ->
            host.start()
            host.typeCodes("su3cl3")
            host.assertText("ㄋㄧˇ ㄏㄠˇ")
            host.tapCandidate("你好")
            host.assertText("你好")
            assertFalse("the composition ended", host.editor.composing)
            assertFalse("the engine composition ended", host.backend.currentSnapshot().isComposing)
        }

    @Test
    fun aPartialChoiceKeepsTheRemainingSyllable() =
        withZhuyin { host ->
            host.start()
            host.typeCodes("su3cl3")
            host.tapCandidate("你")
            // The engine keeps the rest of the reading: the field shows the chosen syllable followed
            // by the still-unconfirmed zhuyin, and nothing has been committed yet.
            host.assertText("你ㄏㄠˇ")
            assertTrue("the composition continues", host.editor.composing)
            assertEquals("你ㄏㄠˇ", host.backend.currentSnapshot().preedit)
            val shown = host.text()
            val span = host.composingSpan()
            assertEquals("the whole field is still marked text", 0, span.first)
            assertEquals(shown.length, span.second)
            assertTrue("the rest is still a choice: ${host.barLabels()}", host.barLabels().isNotEmpty())
            host.tapCandidate("好")
            host.assertText("你好")
            assertFalse(
                "the final candidate tap finished the native composition",
                host.backend.currentSnapshot().isComposing,
            )
            assertFalse("the finished word was committed", host.editor.composing)
        }

    @Test
    fun spaceAfterACompleteToneCommitsTheSyllable() =
        withZhuyin { host ->
            host.start()
            host.typeCodes("5k4")
            host.assertText("ㄓㄜˋ")
            host.tapControl(ControlKey.SPACE)
            host.assertText("這")
            assertFalse("the syllable is committed", host.editor.composing)
        }

    @Test
    fun spaceAfterAnUntonedSyllableKeepsComposing() =
        withZhuyin { host ->
            host.start()
            // The first tone is typed with Space, which only marks it here: the highlighted segment is
            // not confirmed yet, so nothing may reach the editor.
            host.typeCodes("5k")
            host.assertText("ㄓㄜ")
            host.tapControl(ControlKey.SPACE)
            host.assertText("ㄓㄜˉ")
            assertTrue("the composition continues", host.editor.composing)
            assertEquals(host.backend.currentSnapshot().preedit, host.text())
            val shown = host.text()
            val span = host.composingSpan()
            assertEquals("the whole field is still marked text", 0, span.first)
            assertEquals(shown.length, span.second)
            assertTrue("the syllable still offers choices: ${host.barLabels()}", host.barLabels().isNotEmpty())
        }

    private fun <T> withZhuyin(block: (FeatureHost) -> T): T = withTraditionalHost(deployment(), "zhuyin", block)

    companion object {
        private const val ROOT = "traditional-zhuyin"

        private lateinit var root: File
        private var deployed: TraditionalSchemeFixture.Deployment? = null

        /** One merge and deployment for the class; each test opens its own backend over it. */
        private fun deployment(): TraditionalSchemeFixture.Deployment {
            deployed?.let { return it }
            TraditionalSchemeFixture.requireProvisioned(TraditionalSchemeFixture.BOPOMOFO)
            root =
                File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    ROOT,
                ).also { it.deleteRecursively() }
            return runBlocking {
                TraditionalSchemeFixture.deploy(TraditionalSchemeFixture.BOPOMOFO, root)
            }.also { deployed = it }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            if (::root.isInitialized) root.deleteRecursively()
        }
    }
}
