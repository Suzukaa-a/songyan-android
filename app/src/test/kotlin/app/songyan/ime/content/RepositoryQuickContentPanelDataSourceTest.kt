package app.songyan.ime.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class RepositoryQuickContentPanelDataSourceTest {
    @Test
    fun contentCarriesEverythingThePanelsRender() =
        runBlocking {
            val repository = QuickContentRepository(Files.createTempDirectory("panels-src").toFile())
            val source = RepositoryQuickContentPanelDataSource(repository)
            repository.refresh()
            repository.setClipboardEnabled(true)
            repository.savePhrase(CommonPhrase(text = "辛苦", shortcut = "xk"))
            repository.recordCapturedClipboardText("copied")
            val content = source.loadContent()
            assertTrue(content.catalog.items.isNotEmpty())
            assertEquals(listOf("辛苦"), content.phrases.map { it.text })
            assertEquals(listOf("copied"), content.clipboard)
            assertTrue(content.clipboardEnabled)
        }

    @Test
    fun symbolInsertUpdatesTheRecentList() =
        runBlocking {
            val repository = QuickContentRepository(Files.createTempDirectory("panels-recent").toFile())
            val source = RepositoryQuickContentPanelDataSource(repository)
            repository.refresh()
            source.recordRecentSymbol("①")
            source.recordRecentSymbol("②")
            assertEquals(listOf("②", "①"), source.loadContent().recentSymbols)
            source.clearRecentSymbols()
            assertEquals(emptyList<String>(), source.loadContent().recentSymbols)
        }

    @Test
    fun deletingAClipboardEntryDoesNotTouchPhrases() =
        runBlocking {
            val repository = QuickContentRepository(Files.createTempDirectory("panels-delete").toFile())
            val source = RepositoryQuickContentPanelDataSource(repository)
            repository.refresh()
            repository.setClipboardEnabled(true)
            repository.savePhrase(CommonPhrase(text = "收到"))
            repository.recordCapturedClipboardText("one")
            repository.recordCapturedClipboardText("two")
            source.deleteClipboardEntry("one")
            val content = source.loadContent()
            assertEquals(listOf("two"), content.clipboard)
            assertEquals(1, content.phrases.size)
            assertFalse(content.phrases.isEmpty())
        }

    @Test
    fun turningClipboardHistoryOnFromThePanelStoresItAndStartsCapture() =
        runBlocking {
            val directory = Files.createTempDirectory("panels-enable").toFile()
            val repository = QuickContentRepository(directory)
            var capturing: Boolean? = null
            val source = RepositoryQuickContentPanelDataSource(repository) { capturing = it }
            repository.refresh()
            assertFalse(source.loadContent().clipboardEnabled)
            source.setClipboardEnabled(true)
            assertTrue(source.loadContent().clipboardEnabled)
            assertEquals(true, capturing)
            // A second store over the same files reads it back, as the settings screen does.
            assertTrue(QuickContentRepository(directory).refresh().clipboardEnabled)
        }
}
