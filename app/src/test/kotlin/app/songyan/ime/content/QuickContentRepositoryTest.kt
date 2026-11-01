package app.songyan.ime.content

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class QuickContentRepositoryTest {
    private fun repository(): QuickContentRepository =
        QuickContentRepository(Files.createTempDirectory("content").toFile())

    @Test
    fun clipboardCaptureNeedsOptIn() =
        runBlocking {
            val repository = repository()
            repository.refresh()
            assertFalse(repository.state.value.clipboardEnabled)
            assertFalse(repository.recordCapturedClipboardText("hello"))
            assertEquals(emptyList<String>(), repository.state.value.clipboard)
        }

    @Test
    fun capturedTextIsNewestFirstAndDeduplicated() =
        runBlocking {
            val repository = repository()
            repository.refresh()
            repository.setClipboardEnabled(true)
            assertTrue(repository.recordCapturedClipboardText("first"))
            assertTrue(repository.recordCapturedClipboardText("second"))
            assertTrue(repository.recordCapturedClipboardText("first"))
            assertEquals(listOf("first", "second"), repository.state.value.clipboard)
        }

    @Test
    fun sensitiveTextIsNeverStored() =
        runBlocking {
            val repository = repository()
            repository.refresh()
            repository.setClipboardEnabled(true)
            assertFalse(repository.recordCapturedClipboardText("4111111111111111", isSensitive = true))
            assertEquals(emptyList<String>(), repository.state.value.clipboard)
        }

    @Test
    fun turningHistoryOffErasesIt() =
        runBlocking {
            val repository = repository()
            repository.refresh()
            repository.setClipboardEnabled(true)
            repository.recordCapturedClipboardText("kept")
            repository.setClipboardEnabled(false)
            assertFalse(repository.state.value.clipboardEnabled)
            assertEquals(emptyList<String>(), repository.state.value.clipboard)
        }

    @Test
    fun clearAndDeleteOnlyTouchHistory() =
        runBlocking {
            val repository = repository()
            repository.refresh()
            repository.setClipboardEnabled(true)
            repository.savePhrase(CommonPhrase(text = "辛苦了", shortcut = "xkl"))
            repository.recordCapturedClipboardText("one")
            repository.recordCapturedClipboardText("two")
            repository.deleteClipboardEntry("one")
            assertEquals(listOf("two"), repository.state.value.clipboard)
            repository.clearClipboard()
            assertEquals(emptyList<String>(), repository.state.value.clipboard)
            assertEquals(1, repository.state.value.phrases.size)
        }

    @Test
    fun phrasesRoundTrip() =
        runBlocking {
            val repository = repository()
            repository.refresh()
            val phrase = CommonPhrase(text = "收到", shortcut = "sd")
            repository.savePhrase(phrase)
            val stored = repository.refresh().phrases.single()
            assertEquals(phrase.text, stored.text)
            assertEquals(phrase.shortcut, stored.shortcut)
            assertEquals(phrase.id, stored.id)
            repository.deletePhrase(phrase.id)
            assertEquals(emptyList<CommonPhrase>(), repository.refresh().phrases)
        }

    @Test
    fun panelsDataLoadsSymbolsPhrasesAndHistoryInOnePass() =
        runBlocking {
            val repository = repository()
            repository.refresh()
            repository.setClipboardEnabled(true)
            repository.savePhrase(CommonPhrase(text = "辛苦", shortcut = "xk"))
            repository.recordCapturedClipboardText("copied")
            val panels = repository.loadPanelsData()
            assertTrue(panels.symbols.catalog.items.size > 0)
            assertEquals(listOf("辛苦" to "xk"), panels.phrases.map { it.text to it.shortcut })
            assertEquals(listOf("copied"), panels.clipboard)
            assertTrue(panels.clipboardEnabled)
        }

    @Test
    fun bundledSymbolCatalogLoadsAndRecentsMoveToFront() =
        runBlocking {
            val repository = repository()
            repository.refresh()
            val symbols = repository.loadSymbols()
            assertTrue(symbols.catalog.items.isNotEmpty())
            assertFalse(symbols.customCatalog)
            repository.recordRecentSymbol("①")
            repository.recordRecentSymbol("②")
            repository.recordRecentSymbol("①")
            assertEquals(listOf("①", "②"), repository.loadSymbols().recents)
            repository.clearRecentSymbols()
            assertEquals(emptyList<String>(), repository.loadSymbols().recents)
        }
}
