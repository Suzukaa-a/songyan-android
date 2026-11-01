package app.songyan.ime.settings

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.LocalQuickContentStore
import app.songyan.ime.settings.ui.screens.QuickContentScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Saved dialog state, and the phrase dialog: focused on open, with a search-term field. */
class QuickContentScreenTest {
    @get:Rule
    val compose = createComposeRule()

    @Before
    fun freshStorage() {
        val files = InstrumentationRegistry.getInstrumentation().targetContext.filesDir
        File(files, "content").deleteRecursively()
    }

    private fun has(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()

    /** The dialog is the only place with a text field; its title also names a button on the page. */
    private fun dialogOpen() = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun theAddDialogOpensWithItsFieldFocusedAndHasASearchTermField() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) { QuickContentScreen(onBack = {}, startWithAddPhrase = true) }
        }
        compose.waitUntil(5_000) { dialogOpen() }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasSetTextAction() and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
    }

    @Test
    fun aSearchTermTypedInTheDialogIsStoredWithThePhrase() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) { QuickContentScreen(onBack = {}, startWithAddPhrase = true) }
        }
        compose.waitUntil(5_000) { dialogOpen() }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("到站了")
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("dz")
        compose.onNodeWithText(str(R.string.quick_content_save)).performClick()
        waitFor { contentStore().read().phrases.any { it.text == "到站了" && it.shortcut == "dz" } }
    }

    @Test
    fun editingAPhraseShowsItsSearchTermAndKeepsItWhenOnlyTheTextChanges() {
        val id =
            java.util.UUID
                .randomUUID()
                .toString()
        contentStore().savePhrase(CommonPhrase(id = id, text = "稍等", shortcut = "sd"))
        show()
        waitFor { has("稍等") }
        compose.onNodeWithContentDescription(str(R.string.quick_content_edit)).performClick()
        waitFor { dialogOpen() }
        compose.onNode(hasSetTextAction() and hasText("sd")).assertExists()
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("一下")
        compose.onNodeWithText(str(R.string.quick_content_save)).performClick()
        waitFor { contentStore().read().phrases.any { it.id == id && it.text.contains("一下") && it.shortcut == "sd" } }
    }

    @Test
    fun aPhraseSavedFromTheDialogShowsInTheList() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) { QuickContentScreen(onBack = {}, startWithAddPhrase = true) }
        }
        compose.waitUntil(5_000) { dialogOpen() }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("已收到")
        compose.onNodeWithText(str(R.string.quick_content_save)).performClick()
        runCatching {
            compose.waitUntil(5_000) { has("已收到") && !dialogOpen() }
        }.onFailure {
            throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), it)
        }
    }

    @Test
    fun anEmptyPhraseIsRefusedWithAShortReason() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) { QuickContentScreen(onBack = {}, startWithAddPhrase = true) }
        }
        compose.waitUntil(5_000) { dialogOpen() }
        compose.onNodeWithText(str(R.string.quick_content_save)).performClick()
        compose.onNodeWithText(str(R.string.quick_content_error_text_required)).assertExists()
    }

    @Test
    fun aDialogInProgressSurvivesRecreation() {
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SongyanTheme(dynamicColor = false) { QuickContentScreen(onBack = {}, startWithAddPhrase = true) }
        }
        compose.waitUntil(5_000) { dialogOpen() }
        compose.onAllNodes(hasSetTextAction())[0].performTextInput("半截的内容")
        restoration.emulateSavedInstanceStateRestore()
        compose.waitUntil(5_000) { dialogOpen() }
        compose.onNodeWithText("半截的内容").assertExists()
    }

    private fun contentStore() =
        LocalQuickContentStore(
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "content").toPath(),
        )

    /** Waits for [condition]; on timeout the failure carries the semantics tree, which names what was on screen. */
    private fun waitFor(condition: () -> Boolean) {
        try {
            compose.waitUntil(5_000, condition)
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError(compose.onRoot(useUnmergedTree = true).printToString(), e)
        }
    }

    private fun show() {
        compose.setContent { SongyanTheme(dynamicColor = false) { QuickContentScreen(onBack = {}) } }
    }

    @Test
    fun deletingAPhraseAsksNothingAndCanBeUndone() {
        contentStore().savePhrase(
            CommonPhrase(
                id =
                    java.util.UUID
                        .randomUUID()
                        .toString(),
                text = "先别删我",
            ),
        )
        show()
        waitFor { has("先别删我") }
        compose.onNode(hasContentDescription(str(R.string.quick_content_delete))).performClick()
        // No confirmation dialog: the Undo action is the way back.
        waitFor { has(str(R.string.quick_content_undo)) }
        waitFor { !has("先别删我") }
        compose.onNodeWithText(str(R.string.quick_content_undo)).performClick()
        waitFor { has("先别删我") }
    }

    @Test
    fun deletingAClipboardEntryBehavesTheSameWay() {
        contentStore().setClipboardEnabled(true)
        contentStore().recordExplicitPaste("复制过的话")
        show()
        compose.waitUntil(5_000) { has("复制过的话") }
        compose.onNode(hasContentDescription(str(R.string.quick_content_clipboard_delete))).performClick()
        compose.waitUntil(5_000) { has(str(R.string.quick_content_undo)) }
        compose.waitUntil(5_000) { !has("复制过的话") }
        compose.onNodeWithText(str(R.string.quick_content_undo)).performClick()
        compose.waitUntil(5_000) { has("复制过的话") }
    }
}
