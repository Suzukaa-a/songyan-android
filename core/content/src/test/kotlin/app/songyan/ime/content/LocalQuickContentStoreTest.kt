package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections

/** Ported from LocalQuickContentTests: phrases, opt-in clipboard history and the file budget. */
class LocalQuickContentStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath()

    private fun directory(name: String = "content"): Path = root.resolve(name)

    private fun listing(directory: Path): List<String> =
        Files.list(directory).use { stream -> stream.map { it.fileName.toString() }.sorted().toList() }

    @Test
    fun disabledByDefaultAndDoesNotCreateStorageWhenPasting() {
        val url = directory()
        val store = LocalQuickContentStore(url)
        assertFalse(store.read().clipboardEnabled)
        assertFalse(store.recordExplicitPaste("secret"))
        assertFalse(Files.exists(url))
    }

    @Test
    fun existingDisabledSettingsDoNotCreateLockOrRewriteFile() {
        val url = directory()
        Files.createDirectory(url)
        val bytes =
            "{\"version\":1,\"phrases\":[],\"clipboardEnabled\":false,\"clipboard\":[]}".toByteArray()
        val file = url.resolve(LocalQuickContentStore.FILE_NAME)
        Files.write(file, bytes)
        val store = LocalQuickContentStore(url)
        assertFalse(store.read().clipboardEnabled)
        assertFalse(store.recordExplicitPaste("private"))
        assertTrue(bytes.contentEquals(Files.readAllBytes(file)))
        assertEquals(listOf(LocalQuickContentStore.FILE_NAME), listing(url))
    }

    @Test
    fun historyIsBoundedDeduplicatedAndDisableErasesIt() {
        val url = directory()
        val store = LocalQuickContentStore(url)
        store.setClipboardEnabled(true)
        for (index in 0 until 25) assertTrue(store.recordExplicitPaste("item-" + index))
        assertEquals(20, store.read().clipboard.size)
        assertTrue(store.recordExplicitPaste("item-10"))
        assertEquals("item-10", store.read().clipboard.first())
        assertEquals(1, store.read().clipboard.count { it == "item-10" })
        store.deleteClipboard("item-10")
        assertFalse(store.read().clipboard.contains("item-10"))
        store.clearClipboard()
        assertEquals(emptyList<String>(), store.read().clipboard)
        assertTrue(store.read().clipboardEnabled)
        store.recordExplicitPaste("private")
        store.setClipboardEnabled(false)
        assertEquals(emptyList<String>(), store.read().clipboard)
        assertFalse(store.recordExplicitPaste("later"))
        val bytes = Files.readAllBytes(store.contentFile)
        assertFalse(String(bytes, Charsets.UTF_8).contains("private"))
        assertEquals(listOf(LocalQuickContentStore.LOCK_FILE_NAME, LocalQuickContentStore.FILE_NAME), listing(url))
    }

    @Test
    fun sensitiveClipboardTextIsNeverStored() {
        val url = directory()
        val store = LocalQuickContentStore(url)
        store.setClipboardEnabled(true)
        assertFalse(store.recordCapturedClipboardText("secret", isSensitive = true))
        assertEquals(emptyList<String>(), store.read().clipboard)
        assertTrue(store.recordCapturedClipboardText("clipboard text", isSensitive = false))
        assertEquals(listOf("clipboard text"), store.read().clipboard)
        // Blank and oversized captures are skipped, never surfaced as an error.
        assertFalse(store.recordCapturedClipboardText(" \n ", isSensitive = false))
        assertFalse(store.recordCapturedClipboardText("a".repeat(4097), isSensitive = false))
        assertEquals(listOf("clipboard text"), store.read().clipboard)
        // Disabled history stores nothing.
        store.setClipboardEnabled(false)
        assertFalse(store.recordCapturedClipboardText("later", isSensitive = false))
        assertEquals(emptyList<String>(), store.read().clipboard)
    }

    @Test
    fun phraseTransferExcludesClipboardAndPreservesOptIn() {
        val url = directory("a")
        val otherUrl = directory("b")
        val store = LocalQuickContentStore(url)
        val phrase = CommonPhrase(text = "稍后回复你", shortcut = "sh")
        store.savePhrase(phrase)
        store.setClipboardEnabled(true)
        store.recordExplicitPaste("private-history")
        val data = store.exportPhrases()
        assertFalse(String(data, Charsets.UTF_8).contains("private-history"))
        assertFalse(String(data, Charsets.UTF_8).contains("clipboard"))
        val other = LocalQuickContentStore(otherUrl)
        other.importPhrases(data)
        assertEquals(listOf(phrase), other.read().phrases)
        val source = otherUrl.resolve("phrases.json")
        Files.write(source, data)
        assertEquals(listOf(phrase), other.importPhrasesFile(source).phrases)
        Files.write(source, ByteArray(LocalQuickContentStore.MAXIMUM_FILE_BYTES + 1) { 0x20 })
        assertThrows(ContentException::class.java) { other.importPhrasesFile(source) }
        assertEquals(listOf(phrase), other.read().phrases)
        assertFalse(other.read().clipboardEnabled)
        assertTrue(phrase.matches("ＳＨ"))
        store.deletePhrase(phrase.id)
        assertEquals(emptyList<CommonPhrase>(), store.read().phrases)
    }

    @Test
    fun limitsAndCorruptionDoNotOverwriteAcceptedContent() {
        val url = directory()
        val store = LocalQuickContentStore(url)
        store.setClipboardEnabled(true)
        store.recordExplicitPaste("kept")
        assertThrows(ContentException::class.java) { store.recordExplicitPaste("a".repeat(4097)) }
        assertFalse(LocalQuickContentStore.canRecord("a".repeat(4097)))
        assertFalse(LocalQuickContentStore.canRecord(" \n "))
        assertTrue(LocalQuickContentStore.canRecord("kept"))
        assertEquals(listOf("kept"), store.read().clipboard)
        assertThrows(ContentException::class.java) { store.savePhrase(CommonPhrase(text = "  ")) }
        for (index in 0 until 100) store.savePhrase(CommonPhrase(text = "phrase " + index))
        assertThrows(ContentException::class.java) { store.savePhrase(CommonPhrase(text = "overflow")) }
        assertEquals(100, store.read().phrases.size)
        val file = store.contentFile
        val damaged = "broken".toByteArray()
        Files.write(file, damaged)
        assertThrows(ContentException::class.java) { store.read() }
        assertThrows(ContentException::class.java) { store.recordExplicitPaste("not-saved") }
        assertTrue(damaged.contentEquals(Files.readAllBytes(file)))
    }

    @Test
    fun concurrentCapturesStayBoundedAndDisableErasesThem() {
        val url = directory()
        val store = LocalQuickContentStore(url)
        store.setClipboardEnabled(true)
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val threads =
            (0 until 20).map { index ->
                Thread {
                    try {
                        store.recordExplicitPaste("item-" + index)
                    } catch (error: Throwable) {
                        failures.add(error)
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
        val captured = store.read().clipboard
        assertEquals(20, captured.size)
        assertEquals(20, captured.distinct().size)
        // Disabling erases everything, including entries captured concurrently.
        store.setClipboardEnabled(false)
        assertFalse(store.read().clipboardEnabled)
        assertEquals(emptyList<String>(), store.read().clipboard)
    }

    @Test
    fun invalidUtf8IsRefusedInsteadOfBeingRewritten() {
        val url = directory()
        Files.createDirectory(url)
        // A dangling lead byte inside the phrase text: a lenient decode would turn it into U+FFFD
        // and the next save would persist that replacement character over the original bytes.
        val prefix =
            "{\"version\":1,\"phrases\":[{\"id\":\"3f2504e0-4f89-11d3-9a0c-0305e82c3301\"," +
                "\"text\":\"a"
        val suffix = "\",\"shortcut\":\"\"}],\"clipboardEnabled\":false,\"clipboard\":[]}"
        val damaged = prefix.toByteArray() + byteArrayOf(0xC3.toByte()) + suffix.toByteArray()
        val file = url.resolve(LocalQuickContentStore.FILE_NAME)
        Files.write(file, damaged)
        val store = LocalQuickContentStore(url)
        val error = assertThrows(ContentException::class.java) { store.read() }
        assertEquals(ContentErrorCode.MALFORMED_JSON, error.code)
        assertThrows(ContentException::class.java) { store.savePhrase(CommonPhrase(text = "kept")) }
        assertTrue("the damaged file must stay untouched", damaged.contentEquals(Files.readAllBytes(file)))
    }

    @Test
    fun malformedPhraseImportIsActionableAndPreservesContent() {
        val url = directory()
        val store = LocalQuickContentStore(url)
        store.savePhrase(CommonPhrase(text = "kept"))
        val error = assertThrows(ContentException::class.java) { store.importPhrases("broken".toByteArray()) }
        assertEquals(ContentErrorCode.MALFORMED_JSON, error.code)
        assertEquals(
            "kept",
            store
                .read()
                .phrases
                .first()
                .text,
        )
    }

    @Test
    fun iosShapedFileLoadsAndNormalizesIds() {
        val url = directory()
        Files.createDirectory(url)
        // Written by the iOS app: uppercase UUID, and a field this build does not know.
        val iosFile =
            """
            {
              "version": 1,
              "phrases": [
                {"id": "3F2504E0-4F89-11D3-9A0C-0305E82C3301", "text": "稍后回复你", "shortcut": "sh"}
              ],
              "clipboardEnabled": true,
              "clipboard": ["from-ios"],
              "someFutureField": true
            }
            """.trimIndent()
        Files.write(url.resolve(LocalQuickContentStore.FILE_NAME), iosFile.toByteArray())
        val store = LocalQuickContentStore(url)
        val state = store.read()
        assertTrue(state.clipboardEnabled)
        assertEquals(listOf("from-ios"), state.clipboard)
        assertEquals("3f2504e0-4f89-11d3-9a0c-0305e82c3301", state.phrases.first().id)
        // Rewriting normalizes the file to the fields this build owns.
        store.savePhrase(state.phrases.first().copy(text = "稍后回复你。"))
        val rewritten = Files.readString(store.contentFile)
        assertFalse(rewritten.contains("someFutureField"))
        assertEquals(
            "稍后回复你。",
            store
                .read()
                .phrases
                .first()
                .text,
        )
    }

    @Test
    fun phraseArchiveRoundTripsAndRejectsBadIds() {
        val url = directory()
        val store = LocalQuickContentStore(url)
        store.savePhrase(CommonPhrase(text = "好的"))
        store.savePhrase(CommonPhrase(text = "谢谢", shortcut = "xx"))
        val archive = store.exportPhrases()
        assertEquals(
            store.read().phrases,
            LocalQuickContentStore(url.resolve("restore")).importPhrases(archive).phrases,
        )
        // Duplicate ids and a malformed id are rejected whole.
        val duplicate =
            """
            {"version":1,"phrases":[
              {"id":"3f2504e0-4f89-11d3-9a0c-0305e82c3301","text":"a","shortcut":""},
              {"id":"3f2504e0-4f89-11d3-9a0c-0305e82c3301","text":"b","shortcut":""}
            ]}
            """.trimIndent()
        val duplicateError = assertThrows(ContentException::class.java) { store.importPhrases(duplicate.toByteArray()) }
        assertEquals(ContentErrorCode.INVALID_VALUE, duplicateError.code)
        val badId = "{\"version\":1,\"phrases\":[{\"id\":\"nope\",\"text\":\"a\",\"shortcut\":\"\"}]}"
        val badIdError = assertThrows(ContentException::class.java) { store.importPhrases(badId.toByteArray()) }
        assertEquals("$.phrases[0].id", badIdError.path)
        assertEquals(2, store.read().phrases.size)
        // A NUL character is never accepted as content.
        assertFalse(LocalQuickContentStore.canRecord("a\u0000b"))
        store.setClipboardEnabled(true)
        assertThrows(ContentException::class.java) { store.recordExplicitPaste("a\u0000b") }
    }

    @Test
    fun concurrentWritersPreservePhrases() {
        val url = directory()
        val failures = Collections.synchronizedList(ArrayList<Throwable>())
        val threads =
            (0 until 20).map { index ->
                Thread {
                    try {
                        LocalQuickContentStore(url).savePhrase(CommonPhrase(text = "phrase " + index))
                    } catch (error: Throwable) {
                        failures.add(error)
                    }
                }
            }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(20, LocalQuickContentStore(url).read().phrases.size)
    }

    @Test
    fun historyLivesInItsOwnFileSoPhrasesAloneCanBeBackedUp() {
        val url = directory()
        val store = LocalQuickContentStore(url)
        store.savePhrase(CommonPhrase(text = "稍后回复你", shortcut = "sh"))
        store.setClipboardEnabled(true)
        store.recordExplicitPaste("private-history")
        assertFalse(String(Files.readAllBytes(store.contentFile), Charsets.UTF_8).contains("private-history"))
        assertTrue(String(Files.readAllBytes(store.clipboardFile), Charsets.UTF_8).contains("private-history"))
        assertEquals(listOf("private-history"), LocalQuickContentStore(url).read().clipboard)
        store.setClipboardEnabled(false)
        assertEquals(emptyList<String>(), store.read().clipboard)
        assertFalse(Files.exists(store.clipboardFile))
    }

    @Test
    fun historyFromAnOlderVersionIsReadAndMovedOutOnTheNextSave() {
        val url = directory()
        Files.createDirectories(url)
        val store = LocalQuickContentStore(url)
        Files.write(
            store.contentFile,
            "{\"version\":1,\"phrases\":[],\"clipboardEnabled\":true,\"clipboard\":[\"old\"]}".toByteArray(),
        )
        assertEquals(listOf("old"), store.read().clipboard)
        store.recordExplicitPaste("new")
        assertEquals(listOf("new", "old"), store.read().clipboard)
        assertFalse(String(Files.readAllBytes(store.contentFile), Charsets.UTF_8).contains("old"))
    }

    @Test
    fun anUnreadableHistoryFileIsAnEmptyHistoryAndKeepsThePhrases() {
        val url = directory()
        val store = LocalQuickContentStore(url)
        store.savePhrase(CommonPhrase(text = "keep", shortcut = ""))
        store.setClipboardEnabled(true)
        Files.write(store.clipboardFile, byteArrayOf(0x7b, 0x00, 0x1f))
        val state = store.read()
        assertEquals(1, state.phrases.size)
        assertEquals(emptyList<String>(), state.clipboard)
    }
}
