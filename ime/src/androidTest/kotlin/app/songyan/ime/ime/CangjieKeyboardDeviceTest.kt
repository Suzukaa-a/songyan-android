package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.layout.LayoutActionKind
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The cangjie keyboard over the real cangjie5 package and the shipped editor wiring.
 *
 * The caps show the radicals and their Latin codes in the corner; a screen reader must announce the
 * radical, because the code is already visible and reading it would hide the radical from a blind
 * user. Typing 人弓火 spells 你 on the deployed dictionary.
 *
 * The real scheme package is not in the repository: its pinned archives are pushed into this test
 * package's cache before the explicit acceptance run (see [TraditionalSchemeFixture]). A missing
 * archive fails the test instead of turning it green without typing.
 */
@RunWith(AndroidJUnit4::class)
class CangjieKeyboardDeviceTest {
    @Test
    fun theCapsShowRadicalsAndABlindReaderHearsTheRadicalNotTheCode() =
        withCangjie { host ->
            host.start()
            val labels = host.labelsOf(KeyboardView::class.java)
            assertTrue(
                "the cangjie caps are drawn: $labels",
                "手" in labels && "人" in labels && "弓" in labels && "火" in labels,
            )
            assertFalse(
                "the caps show radicals, not the Latin codes: $labels",
                labels.any { it.length == 1 && it[0] in 'a'..'z' },
            )
            // The corner letters are the codes; TalkBack must read the radical the cap shows.
            assertEquals("人", host.spokenLabel("o"))
            assertEquals("弓", host.spokenLabel("n"))
            assertEquals("火", host.spokenLabel("f"))
            for (code in listOf("o", "n", "f")) {
                val spoken = host.spokenLabel(code)
                assertFalse(
                    "a screen reader must not read the code in '$spoken'",
                    spoken.any { it in 'a'..'z' || it in 'A'..'Z' },
                )
            }
        }

    @Test
    fun tappingRenGongHuoTypesNi() =
        withCangjie { host ->
            host.start()
            for (code in listOf("o", "n", "f")) host.tapAction(LayoutActionKind.CODE, code)
            assertTrue("the composition offers 你: ${host.barLabels()}", host.barLabels().any { it.startsWith("你") })
            host.tapCandidate("你")
            host.assertText("你")
            assertFalse("the composition ended", host.editor.composing)
            assertFalse("the engine composition ended", host.backend.currentSnapshot().isComposing)
        }

    private fun <T> withCangjie(block: (FeatureHost) -> T): T = withTraditionalHost(deployment(), "cangjie", block)

    companion object {
        private const val ROOT = "traditional-cangjie"

        private lateinit var root: File
        private var deployed: TraditionalSchemeFixture.Deployment? = null

        /** One merge and deployment for the class; each test opens its own backend over it. */
        private fun deployment(): TraditionalSchemeFixture.Deployment {
            deployed?.let { return it }
            TraditionalSchemeFixture.requireProvisioned(TraditionalSchemeFixture.CANGJIE)
            root =
                File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    ROOT,
                ).also { it.deleteRecursively() }
            return runBlocking {
                TraditionalSchemeFixture.deploy(TraditionalSchemeFixture.CANGJIE, root)
            }.also { deployed = it }
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            if (::root.isInitialized) root.deleteRecursively()
        }
    }
}
