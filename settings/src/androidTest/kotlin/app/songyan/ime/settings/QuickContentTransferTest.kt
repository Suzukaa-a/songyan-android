package app.songyan.ime.settings

import android.app.Application
import android.net.Uri
import android.text.format.Formatter
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.LocalQuickContentStore
import app.songyan.ime.content.SymbolCatalog
import app.songyan.ime.content.SymbolCatalogStore
import app.songyan.ime.settings.ui.QuickContentMessage
import app.songyan.ime.settings.ui.QuickContentViewModel
import app.songyan.ime.settings.ui.screens.QuickContentScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Import, export and reset of phrases and the symbol library, through the same file paths the pickers hand over. */
class QuickContentTransferTest {
    @get:Rule
    val compose = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val scratch get() = File(context.cacheDir, "transfer").apply { mkdirs() }

    @Before
    fun freshStorage() {
        File(context.filesDir, "content").deleteRecursively()
        scratch.deleteRecursively()
    }

    private fun contentStore() = LocalQuickContentStore(File(context.filesDir, "content").toPath())

    private fun viewModel() = QuickContentViewModel(context.applicationContext as Application)

    private fun fileUri(name: String): Uri = Uri.fromFile(File(scratch, name))

    private fun waitForMessage(
        viewModel: QuickContentViewModel,
        expected: (QuickContentMessage) -> Boolean,
    ) {
        compose.waitUntil(5_000) { viewModel.message.value?.let(expected) == true }
    }

    private val customCatalog =
        "{\"version\":1,\"categories\":[{\"id\":\"c\",\"name\":\"测试\",\"order\":1}]," +
            "\"items\":[{\"id\":\"a\",\"text\":\"→\",\"name\":\"右箭头\",\"aliases\":[\"jiantou\"]," +
            "\"category\":\"c\"}]}"

    @Test
    fun exportedPhrasesComeBackThroughImportAfterTheyAreCleared() {
        val first = CommonPhrase(id = "11111111-1111-1111-1111-111111111111", text = "在路上", shortcut = "zls")
        val second = CommonPhrase(id = "22222222-2222-2222-2222-222222222222", text = "稍后回复")
        contentStore().savePhrase(first)
        contentStore().savePhrase(second)
        val model = viewModel()
        val uri = fileUri("phrases.json")

        model.exportPhrases(context.contentResolver, uri)
        waitForMessage(model) { it == QuickContentMessage.PhrasesExported }
        contentStore().deletePhrase(first.id)
        contentStore().deletePhrase(second.id)
        model.consumeMessage()
        model.importPhrases(context.contentResolver, uri)
        waitForMessage(model) { it == QuickContentMessage.PhrasesImported(2) }

        assertEquals(listOf(first, second), contentStore().read().phrases)
    }

    @Test
    fun aPhraseFileThatIsNotOneChangesNothingAndSaysSo() {
        val kept = CommonPhrase(id = "11111111-1111-1111-1111-111111111111", text = "别丢")
        contentStore().savePhrase(kept)
        File(scratch, "bad.json").writeText("{\"version\":1,\"oops\":true}")
        val model = viewModel()

        model.importPhrases(context.contentResolver, fileUri("bad.json"))
        waitForMessage(model) { it == QuickContentMessage.FileRejected }

        assertEquals(listOf(kept), contentStore().read().phrases)
    }

    @Test
    fun aFileThatCannotBeOpenedIsReportedAsUnreadable() {
        val model = viewModel()
        model.importSymbols(context.contentResolver, fileUri("missing.json"))
        waitForMessage(model) { it == QuickContentMessage.FileUnreadable }
    }

    @Test
    fun anImportedSymbolLibraryShowsAsCustomAndRestoringBringsTheBuiltInOneBack() {
        File(scratch, "symbols.json").writeText(customCatalog)
        val model = viewModel()
        compose.setContent { SongyanTheme(dynamicColor = false) { QuickContentScreen(onBack = {}, viewModel = model) } }
        compose.waitUntil(5_000) { !model.uiState.value.loading }
        compose.onNodeWithText(str(R.string.quick_content_symbols_builtin)).assertExists()

        model.importSymbols(context.contentResolver, fileUri("symbols.json"))
        waitForMessage(model) { it == QuickContentMessage.SymbolsImported(1) }
        compose.onNodeWithText(str(R.string.quick_content_symbols_custom)).assertExists()

        // Restoring asks first; the custom library stays until the user confirms.
        compose.onNodeWithText(str(R.string.quick_content_symbols_restore)).performClick()
        compose.onNodeWithText(str(R.string.quick_content_symbols_restore_title)).assertExists()
        compose.onNodeWithText(str(R.string.quick_content_symbols_restore_confirm)).performClick()
        compose.waitUntil(5_000) { model.message.value == QuickContentMessage.SymbolsRestored }
        compose.onNodeWithText(str(R.string.quick_content_symbols_builtin)).assertExists()
    }

    @Test
    fun oversizedImportsStateTheirLimitsAndKeepExistingContent() {
        val kept = CommonPhrase(id = "11111111-1111-1111-1111-111111111111", text = "保留的常用语")
        contentStore().savePhrase(kept)
        val symbols = SymbolCatalogStore(File(context.filesDir, "content/symbols").toPath())
        symbols.importCatalog(customCatalog.toByteArray())
        val model = viewModel()
        compose.setContent { SongyanTheme(dynamicColor = false) { QuickContentScreen(onBack = {}, viewModel = model) } }
        for ((name, limit) in listOf(
            "phrases.json" to LocalQuickContentStore.MAXIMUM_FILE_BYTES,
            "symbols.json" to SymbolCatalog.MAX_JSON_BYTES,
        )) {
            File(scratch, name).writeBytes(ByteArray(limit + 1) { ' '.code.toByte() })
            if (name == "phrases.json") {
                model.importPhrases(context.contentResolver, fileUri(name))
            } else {
                model.importSymbols(context.contentResolver, fileUri(name))
            }
            waitForMessage(model) { true }
            compose
                .onNodeWithText(
                    context.getString(
                        R.string.quick_content_file_too_large,
                        Formatter.formatFileSize(context, limit.toLong()),
                    ),
                ).assertExists()
            model.consumeMessage()
        }
        assertEquals(listOf(kept), contentStore().read().phrases)
        assertEquals(
            "→",
            symbols
                .loadActive()
                .items
                .single()
                .text,
        )
    }

    @Test
    fun aDamagedSymbolLibraryShowsTheFallbackAndCanBeRestoredWithoutLosingPhrases() {
        val kept = CommonPhrase(id = "11111111-1111-1111-1111-111111111111", text = "保留的常用语")
        contentStore().savePhrase(kept)
        val symbols = SymbolCatalogStore(File(context.filesDir, "content/symbols").toPath())
        symbols.importCatalog(customCatalog.toByteArray())
        symbols.activeFile.toFile().writeText("{broken")
        val model = viewModel()
        compose.setContent { SongyanTheme(dynamicColor = false) { QuickContentScreen(onBack = {}, viewModel = model) } }
        compose.waitUntil(5_000) { !model.uiState.value.loading }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.quick_content_symbols_read_failed)))
        compose.onNodeWithText(str(R.string.quick_content_symbols_read_failed)).assertExists()
        compose.onNodeWithText(str(R.string.quick_content_symbols_builtin)).assertExists()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.quick_content_symbols_restore)))
        compose.onNodeWithText(str(R.string.quick_content_symbols_restore)).performClick()
        compose.onNodeWithText(str(R.string.quick_content_symbols_restore_confirm)).performClick()
        waitForMessage(model) { it == QuickContentMessage.SymbolsRestored }
        compose.onNodeWithText(str(R.string.quick_content_symbols_read_failed)).assertDoesNotExist()
        assertEquals(listOf(kept), contentStore().read().phrases)
        assertTrue(!symbols.hasCustomCatalog)
    }

    @Test
    fun theExportedSymbolLibraryIsTheActiveOneAndImportsBack() {
        File(scratch, "symbols.json").writeText(customCatalog)
        val model = viewModel()
        model.importSymbols(context.contentResolver, fileUri("symbols.json"))
        waitForMessage(model) { it == QuickContentMessage.SymbolsImported(1) }
        model.consumeMessage()

        model.exportSymbols(context.contentResolver, fileUri("out.json"))
        waitForMessage(model) { it == QuickContentMessage.SymbolsExported }

        assertTrue(File(scratch, "out.json").readText().contains("右箭头"))
    }
}
