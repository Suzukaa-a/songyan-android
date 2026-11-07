package app.songyan.ime.ime

import android.text.InputType
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.support.ShiftState
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The ABC page's English suggestions and the Chinese-mode Shift, against the shipped engine. The
 * deployed scheme mounts a small English dictionary, as an imported scheme package does.
 */
@RunWith(AndroidJUnit4::class)
class FeatureInputDeviceTest {
    @Test
    fun theAbcPageCompletesTheWordFromTheSchemesEnglishDictionary() =
        withHost { host ->
            host.start()
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.type("th")
            assertTrue("completions for 'th': ${host.barLabels()}", host.await { "then" in host.barLabels() })
            assertTrue("the toolbar gives its band to the suggestions", host.toolbarLabels().isEmpty())
            host.tapBar("then")
            host.assertText("then ")
        }

    @Test
    fun aCapitalLetterCompletesInTheCapitalsTheUserTyped() =
        withHost { host ->
            host.start()
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.tapControl(ControlKey.SHIFT)
            host.tap("t")
            host.tap("h")
            assertTrue("capitalized: ${host.barLabels()}", host.await { "Then" in host.barLabels() })
        }

    @Test
    fun nextWordGuessesFollowAFinishedWordAndTheToolbarComesBackWhenThereAreNone() =
        withHost { host ->
            host.start()
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.type("i")
            host.tapControl(ControlKey.SPACE)
            assertTrue("guesses after 'i': ${host.barLabels()}", host.await { "am" in host.barLabels() })
            host.tapBar("am")
            host.assertText("i am ")
            // "am" has no continuation in the built-in table: the idle band is the toolbar again.
            assertTrue(host.await { host.toolbarLabels().isNotEmpty() })
            assertTrue(host.barLabels().isEmpty())
        }

    @Test
    fun movingTheCaretForgetsTheWordSoNoStaleCompletionReplacesText() =
        withHost { host ->
            host.start()
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.type("th")
            assertTrue(host.await { "then" in host.barLabels() })
            host.main { host.controller.onSelectionMovedExternally() }
            host.awaitIdle()
            assertTrue("no suggestions for a word that is no longer being typed", host.barLabels().isEmpty())
            assertTrue(host.toolbarLabels().isNotEmpty())
        }

    @Test
    fun backspaceTrimsTheWordTheSuggestionsAreForAndLeavesNoneWhenItIsGone() =
        withHost { host ->
            host.start()
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.type("th")
            assertTrue(host.await { "then" in host.barLabels() })
            host.tapControl(ControlKey.DELETE)
            host.tapControl(ControlKey.DELETE)
            assertTrue(host.await { host.barLabels().isEmpty() })
            assertEquals("", host.text())
        }

    @Test
    fun aPasswordFieldNeverShowsSuggestions() =
        withHost { host ->
            host.start(inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            host.type("th")
            host.awaitIdle()
            assertEquals("th", host.text())
            assertTrue("no suggestions in a password field: ${host.barLabels()}", host.barLabels().isEmpty())
        }

    @Test
    fun chineseShiftTypesOneCapitalLetterAndThenLowercaseSpellingResumes() =
        withHost { host ->
            host.start()
            host.tapControl(ControlKey.SHIFT)
            host.tap("n")
            host.assertText("N")
            assertFalse(
                "the one-shot is spent",
                host.main2 {
                    host.controller.session
                        ?.shiftState
                        ?.isActive == true
                },
            )
            host.tap("i")
            host.awaitIdle()
            assertTrue("lowercase letters compose again", host.editor.composing)
        }

    @Test
    fun chineseDoubleShiftLocksCapitalsUntilShiftIsTappedAgain() =
        withHost { host ->
            host.start()
            host.tapControl(ControlKey.SHIFT)
            // The second tap of a double tap, as the key view reports it.
            host.main { host.controller.keyboard.onDoubleShift() }
            assertTrue(host.await { host.main2 { host.controller.session?.shiftState } == ShiftState.LOCKED })
            host.tap("a")
            host.tap("b")
            host.assertText("AB")
            host.tapControl(ControlKey.SHIFT)
            host.tap("n")
            host.awaitIdle()
            assertTrue("released: lowercase letters compose again", host.editor.composing)
        }

    @Test
    fun chineseShiftWhileComposingHandsTheCapitalToTheEngine() =
        withHost { host ->
            host.start()
            host.type("ni")
            assertTrue(host.await { host.editor.composing })
            host.tapControl(ControlKey.SHIFT)
            host.tap("b")
            host.awaitIdle()
            val shown = host.text()
            assertTrue("the capital reached the editor: '$shown'", shown.contains("B"))
            assertFalse("the key is not lost as a lowercase letter: '$shown'", shown.contains("b"))
        }

    /**
     * A scheme that reads a capital gets it, even with nothing composed: rime-ice starts its symbol
     * input with `V` (`Vhelp`), which the keyboard used to type straight into the field.
     */
    @Test
    fun chineseShiftHandsACapitalToASchemeThatReadsIt() {
        val host = FeatureHost(capitals.backend())
        try {
            host.start()
            host.tapControl(ControlKey.SHIFT)
            host.tap("v")
            host.type("help")
            host.awaitIdle()
            assertTrue("the scheme composes the capital", host.editor.composing)
            assertEquals("Vhelp", host.backend.currentSnapshot().rawInput)
            host.assertText("Vhelp")
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    @Test
    fun shiftOnTheEnglishPageStillUppercasesTheNextLetter() =
        withHost { host ->
            host.start()
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.tapControl(ControlKey.SHIFT)
            host.tap("a")
            host.assertText("A")
        }

    private fun <T> withHost(block: (FeatureHost) -> T): T {
        val host = FeatureHost(engine.backend())
        try {
            return block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    companion object {
        private lateinit var engine: DeployedEngine

        /** Full pinyin whose speller also reads `V`, the way rime-ice's alphabet takes capitals. */
        private val capitalsDeployment =
            lazy {
                DeployedEngine("capitals", english = false) { source ->
                    val schema = java.io.File(source, "songyan_quanpin.schema.yaml")
                    val text = schema.readText()
                    val patched =
                        text.replace(
                            "alphabet: zyxwvutsrqponmlkjihgfedcba\n",
                            "alphabet: zyxwvutsrqponmlkjihgfedcbaV\n",
                        )
                    check(patched != text) { "the alphabet line moved" }
                    schema.writeText(patched)
                }
            }
        private val capitals by capitalsDeployment

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("input", english = true)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
            if (capitalsDeployment.isInitialized()) capitals.delete()
        }
    }
}
