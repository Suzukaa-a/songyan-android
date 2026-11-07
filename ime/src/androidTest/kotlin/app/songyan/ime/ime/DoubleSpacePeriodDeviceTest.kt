package app.songyan.ime.ime

import android.text.InputType
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.AfterClass
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** The double-space period against a real editor: what the field holds after the space key. */
@RunWith(AndroidJUnit4::class)
class DoubleSpacePeriodDeviceTest {
    private fun withHost(block: (FeatureHost) -> Unit) {
        val host = FeatureHost(engine.backend())
        try {
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun FeatureHost.english(inputType: Int = InputType.TYPE_CLASS_TEXT) {
        start(inputType = inputType)
        main { controller.doubleSpaceWindowMs = GENEROUS_MS }
        tapControl(ControlKey.LANGUAGE_TOGGLE)
    }

    private fun FeatureHost.space() = tapControl(ControlKey.SPACE)

    @Test
    fun twoSpacesAfterAnEnglishWordTypeAPeriodAndASpace() =
        withHost { host ->
            host.english()
            host.type("hi")
            host.space()
            host.assertText("hi ")
            host.space()
            host.assertText("hi. ")
        }

    @Test
    fun aDigitCountsAsAWordCharacter() =
        withHost { host ->
            host.start(initial = "a1")
            host.main { host.controller.doubleSpaceWindowMs = GENEROUS_MS }
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.space()
            host.space()
            host.assertText("a1. ")
        }

    @Test
    fun aSecondSpaceAfterTheWindowIsAPlainSpace() =
        withHost { host ->
            host.english()
            host.main { host.controller.doubleSpaceWindowMs = 1 }
            host.type("hi")
            host.space()
            host.space()
            host.assertText("hi  ")
        }

    @Test
    fun aThirdSpaceIsNotAnotherPeriod() =
        withHost { host ->
            host.english()
            host.type("hi")
            host.space()
            host.space()
            host.space()
            host.assertText("hi.  ")
        }

    @Test
    fun anotherKeyBetweenTheSpacesCancelsIt() =
        withHost { host ->
            host.english()
            host.type("hi")
            host.space()
            host.type("x")
            host.space()
            host.assertText("hi x ")
        }

    @Test
    fun spacesAfterPunctuationStaySpaces() =
        withHost { host ->
            host.start(initial = "hi.")
            host.main { host.controller.doubleSpaceWindowMs = GENEROUS_MS }
            host.tapControl(ControlKey.LANGUAGE_TOGGLE)
            host.space()
            host.space()
            host.assertText("hi.  ")
        }

    @Test
    fun movingTheCaretBetweenTheSpacesCancelsIt() =
        withHost { host ->
            host.english()
            host.type("hi")
            host.space()
            // What the service calls when the user moves the caret in the editor.
            host.main { host.controller.onSelectionMovedExternally() }
            host.awaitIdle()
            host.space()
            host.assertText("hi  ")
        }

    @Test
    fun urlAndEmailFieldsKeepTheSpacesTheUserTyped() {
        for (
        type in
        listOf(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        ) {
            withHost { host ->
                host.start(inputType = type)
                host.main { host.controller.doubleSpaceWindowMs = GENEROUS_MS }
                host.type("ab")
                host.space()
                host.space()
                host.assertText("ab  ")
            }
        }
    }

    @Test
    fun chineseInputIsLeftAlone() =
        withHost { host ->
            host.start()
            host.main { host.controller.doubleSpaceWindowMs = GENEROUS_MS }
            host.space()
            host.space()
            host.assertText("  ")
        }

    @Test
    fun turnedOffInTheSettingsTheSpacesStaySpaces() =
        withHost { host ->
            host.doubleSpacePeriodFlow.value = false
            host.english()
            host.await { !host.main2 { host.controller.doubleSpacePeriodEnabled } }
            host.type("hi")
            host.space()
            host.space()
            host.assertText("hi  ")
        }

    @Test
    fun theReplacementIsOneEditThatUndoesWithOneBackspace() =
        withHost { host ->
            host.english()
            host.type("hi")
            host.space()
            host.space()
            host.assertText("hi. ")
            host.tapControl(ControlKey.DELETE)
            host.assertText("hi.")
        }

    private companion object {
        const val GENEROUS_MS = 10_000L
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("double-space", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
