package app.songyan.ime.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.ui.components.TryOutKind
import app.songyan.ime.settings.ui.screens.TryOutScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The try-out page offers every field kind and the field really turns into that kind. */
class TryOutScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(
        restoration: StateRestorationTester? = null,
        japanese: Boolean = false,
    ) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            SongyanTheme(dynamicColor = false) {
                TryOutScreen(
                    ime = ImeStatus(enabled = true, selected = true),
                    japanese = japanese,
                    onTyped = {},
                    onEnableIme = {},
                    onBack = null,
                )
            }
        }
        if (restoration != null) restoration.setContent(content) else compose.setContent(content)
    }

    /** A password box tells the system (autofill, accessibility) what it is; no other kind does. */
    private val isPassword = SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentType)

    @Test
    fun everyFieldKindCanBeChosen() {
        show()
        TryOutKind.entries.forEach { kind ->
            compose.onNodeWithText(str(kind.labelRes)).performScrollTo().performClick()
            compose.onNodeWithText(str(kind.labelRes)).assertIsSelected()
        }
    }

    @Test
    fun thePasswordKindMakesAPasswordFieldAndLeavingItRestoresPlainText() {
        show()
        compose.onNode(hasSetTextAction()).performTextInput("abc")
        compose.onNode(hasSetTextAction() and isPassword).assertDoesNotExist()
        compose.onNodeWithText(str(R.string.tryout_kind_password)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and isPassword).assertExists()
        compose.onNodeWithText(str(R.string.tryout_kind_url)).performScrollTo().performClick()
        compose.onNode(hasSetTextAction() and isPassword).assertDoesNotExist()
        // Switching kind must not throw away what was typed.
        compose.onNode(hasSetTextAction() and hasText("abc")).assertExists()
    }

    @Test
    fun theNoteNamesTheLanguageKeyTheKeyboardInUseShows() {
        show()
        compose.onNodeWithText(str(R.string.tryout_note)).performScrollTo().assertExists()
    }

    @Test
    fun aJapaneseInputMethodGetsTheKanaKeyInTheNote() {
        // A Japanese keyboard shows あ/A, not 中/英; the note must not send the user looking for a
        // key that is not there.
        show(japanese = true)
        compose.onNodeWithText(str(R.string.tryout_note_japanese)).performScrollTo().assertExists()
        compose.onNodeWithText(str(R.string.tryout_note)).assertDoesNotExist()
    }

    @Test
    fun theCursorStaysWhereItWasAfterTheActivityIsRecreated() {
        val restoration = StateRestorationTester(compose)
        show(restoration)
        val field = hasSetTextAction()
        compose.onNode(field).performTextInput("hello")
        compose.onNode(field).performTextInputSelection(TextRange(2))
        restoration.emulateSavedInstanceStateRestore()
        val selection = compose.onNode(field).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(TextRange(2), selection)
    }
}
