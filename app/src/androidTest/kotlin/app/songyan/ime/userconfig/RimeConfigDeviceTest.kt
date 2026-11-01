package app.songyan.ime.userconfig

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.R
import app.songyan.ime.content.SettingsInForeground
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.RimeConfigApplyResult
import app.songyan.ime.settings.state.RimeConfigHolder
import app.songyan.ime.settings.state.SchemeImportHolder
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileNotFoundException

/**
 * The Rime configuration folder on a device: the documents provider as the Files app uses it, and
 * the real :deploy process and librime applying what the user wrote there.
 */
@RunWith(AndroidJUnit4::class)
class RimeConfigDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val resolver: ContentResolver = context.contentResolver
    private val storage = AppStorage(context.filesDir)
    private val publisher = GenerationPublisher(storage)
    private val settings = SettingsInForeground()
    private val authority = RimeConfigProvider.authority(context)
    private val editFolder = DocumentsContract.buildDocumentUri(authority, RimeConfigProvider.EDIT)
    private var imported: String? = null

    @Before
    fun setUp() {
        settings.open()
        clearEdits()
        runBlocking { StoredSourcesHolder.current.use(SourceIds.BUILTIN) }
    }

    @After
    fun tearDown() {
        clearEdits()
        runBlocking {
            RimeConfigHolder.current.apply()
            imported?.let { StoredSourcesHolder.current.delete(it) }
            StoredSourcesHolder.current.use(SourceIds.BUILTIN)
        }
        settings.close()
    }

    private fun clearEdits() {
        storage.userConfigDir.listFiles()?.forEach { it.delete() }
    }

    private fun create(
        name: String,
        text: String,
    ): Uri {
        val uri = requireNotNull(DocumentsContract.createDocument(resolver, editFolder, "text/plain", name))
        write(uri, text)
        return uri
    }

    private fun write(
        uri: Uri,
        text: String,
    ) {
        requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray()) }
        // The provider replaces the document once the writer closed it, on its own thread.
        val name = DocumentsContract.getDocumentId(uri).substringAfter('/')
        val deadline = System.currentTimeMillis() + 5_000
        while (File(storage.userConfigDir, name).readText() != text &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20)
        }
    }

    private fun read(uri: Uri): String =
        requireNotNull(resolver.openInputStream(uri)).use {
            it.readBytes().decodeToString()
        }

    private fun children(documentId: String): List<String> =
        requireNotNull(
            resolver.query(DocumentsContract.buildChildDocumentsUri(authority, documentId), null, null, null, null),
        ).use { cursor ->
            val names = ArrayList<String>()
            val column = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            while (cursor.moveToNext()) names += cursor.getString(column)
            names
        }

    private fun apply(): RimeConfigApplyResult = runBlocking { RimeConfigHolder.current.apply() }

    @Test
    fun theFolderIsCreatedWrittenReadRenamedAndDeletedThroughTheProvider() {
        val roots =
            requireNotNull(
                resolver.query(DocumentsContract.buildRootsUri(authority), null, null, null, null),
            ).use { it.count }
        assertEquals(1, roots)

        val uri = create("rime_ice.custom.yaml", PAGE_SIZE_PATCH)
        assertEquals("rime_ice.custom.yaml", DocumentsContract.getDocumentId(uri).substringAfter('/'))
        assertEquals(PAGE_SIZE_PATCH, read(uri))
        assertEquals(listOf("rime_ice.custom.yaml"), children(RimeConfigProvider.EDIT))

        val again = create("rime_ice.custom.yaml", "patch: {}\n")
        assertEquals(
            "a second file never replaces the first",
            "edit/rime_ice.custom-2.yaml",
            DocumentsContract.getDocumentId(again),
        )

        val renamed = requireNotNull(DocumentsContract.renameDocument(resolver, again, "default.custom.yaml"))
        assertEquals("edit/default.custom.yaml", DocumentsContract.getDocumentId(renamed))
        assertTrue(DocumentsContract.deleteDocument(resolver, renamed))
        assertEquals(listOf("rime_ice.custom.yaml"), children(RimeConfigProvider.EDIT))

        // The source in use can be read, not written.
        val sources = children(RimeConfigProvider.ACTIVE)
        assertTrue(sources.toString(), "default.yaml" in sources)
        assertFalse("the app's own files stay hidden", "songyan-source.json" in sources)
        val schema =
            DocumentsContract.buildDocumentUri(
                authority,
                RimeConfigProvider.ACTIVE + "/songyan_quanpin.schema.yaml",
            )
        assertTrue(read(schema).contains("songyan_quanpin"))
        assertFailsToOpen { resolver.openOutputStream(schema, "wt")?.also { it.close() } }
    }

    @Test
    fun theProviderRefusesPathsOutsideItsFoldersAndOversizedOrForeignFiles() {
        for (id in listOf("active/../../shared_prefs", "edit/../applied/x.yaml", "sources/../../databases")) {
            assertFailsToOpen {
                resolver
                    .openInputStream(
                        DocumentsContract.buildDocumentUri(authority, id),
                    )?.also { it.close() }
            }
        }
        assertFailsToOpen { DocumentsContract.createDocument(resolver, editFolder, "image/png", "photo") }
        assertFailsToOpen {
            DocumentsContract.createDocument(
                resolver,
                DocumentsContract.buildDocumentUri(authority, RimeConfigProvider.ACTIVE),
                "text/plain",
                "x.yaml",
            )
        }

        val uri = create("custom_phrase.txt", "松烟\tsy\t1\n")
        requireNotNull(resolver.openOutputStream(uri, "wt")).use { stream ->
            val chunk = ByteArray(64 * 1024) { 'a'.code.toByte() }
            repeat(((UserConfigFiles.MAX_FILE_BYTES / chunk.size) + 2).toInt()) { stream.write(chunk) }
        }
        Thread.sleep(500)
        assertEquals("an oversized write is discarded", "松烟\tsy\t1\n", read(uri))
    }

    @Test
    fun aPatchChangesTheRunningEngineOnlyOnceApplied() {
        val before = requireNotNull(publisher.readActive())
        assertEquals(5, candidates(before, "songyan_quanpin", "ni").size)

        create("songyan_quanpin.custom.yaml", PAGE_SIZE_PATCH)
        assertEquals("editing alone changes nothing", before.generation, publisher.readActive()!!.generation)

        assertEquals(RimeConfigApplyResult.Applied, apply())

        val after = requireNotNull(publisher.readActive())
        assertTrue(publisher.isComplete(after))
        assertEquals(3, candidates(after, "songyan_quanpin", "ni").size)
        assertTrue(File(storage.generationDir(after.generation), "resources/songyan_quanpin.custom.yaml").isFile)
        assertFalse(runBlocking { RimeConfigHolder.current.info() }.pending)
    }

    @Test
    fun aDefaultPatchSwitchesToDoublePinyinOnTwentySixKeys() {
        create("default.custom.yaml", "patch:\n  schema_list:\n    - schema: songyan_flypy\n")

        assertEquals(RimeConfigApplyResult.Applied, apply())

        val active = requireNotNull(publisher.readActive())
        assertEquals(listOf("songyan_flypy"), active.schemaIds)
        assertTrue(candidates(active, "songyan_flypy", "nihc").contains("你好"))
    }

    @Test
    fun aBrokenPatchKeepsTheKeyboardOnItsGeneration() {
        val before = requireNotNull(publisher.readActive())
        create("songyan_quanpin.custom.yaml", "patch:\n  menu/page_size: [3\n")

        val refused = apply()

        assertTrue(refused is RimeConfigApplyResult.Failure)
        assertTrue((refused as RimeConfigApplyResult.Failure).message.orEmpty().contains("songyan_quanpin.custom.yaml"))
        assertEquals(before.generation, publisher.readActive()!!.generation)
        assertNotNull(runBlocking { RimeConfigHolder.current.info() }.failure)

        // Valid YAML that leaves librime nothing to deploy fails in the deployment instead.
        clearEdits()
        create("default.custom.yaml", "patch:\n  schema_list:\n    - schema: no_such_schema\n")
        assertTrue(apply() is RimeConfigApplyResult.Failure)
        val active = requireNotNull(publisher.readActive())
        assertEquals("the keyboard keeps its generation", before.generation, active.generation)
        assertTrue(candidates(active, "songyan_quanpin", "nihao").contains("你好"))
    }

    /**
     * The example a desktop Rime user gives: rime-ice is full pinyin until `default.custom.yaml`
     * picks one of its double pinyin input methods. Needs the package, which is not bundled:
     * `-e schemePackage <rime-ice ZIP the app can read>`.
     */
    @Test
    fun rimeIceSwitchesToDoublePinyinWithADefaultPatch() {
        val path = InstrumentationRegistry.getArguments().getString("schemePackage")
        assumeTrue("no schemePackage argument", path != null)
        val result = runBlocking { SchemeImportHolder.current.importFromUri(Uri.fromFile(File(requireNotNull(path)))) }
        assertTrue("import failed: $result", result is SchemeImportResult.Success)
        imported = publisher.readActive()!!.sourceId
        create("default.custom.yaml", "patch:\n  schema_list:\n    - schema: double_pinyin_flypy\n")

        assertEquals(RimeConfigApplyResult.Applied, apply())

        val active = requireNotNull(publisher.readActive())
        assertEquals(imported, active.sourceId)
        assertEquals(listOf("double_pinyin_flypy"), active.schemaIds)
        assertTrue(candidates(active, "double_pinyin_flypy", "nihc").contains("你好"))
    }

    @Test
    fun aFilePastedIntoAReadOnlyFolderIsRefusedWithAMessageThatNamesTheWritableOne() {
        // Third-party file managers offer to paste into any folder and show the provider's message as is.
        val scheme = DocumentsContract.buildDocumentUri(authority, RimeConfigProvider.ACTIVE)
        val message =
            try {
                DocumentsContract.createDocument(resolver, scheme, "text/plain", "custom_phrase_double.txt")
                null
            } catch (error: UnsupportedOperationException) {
                error.message
            }
        assertEquals(context.getString(R.string.rime_config_refused_read_only), message)
    }

    /** The provider refuses: it throws, or the framework call reports nothing done (null). */
    private fun assertFailsToOpen(block: () -> Any?) {
        val failed =
            try {
                block() == null
            } catch (_: FileNotFoundException) {
                true
            } catch (_: SecurityException) {
                true
            } catch (_: IllegalArgumentException) {
                true
            } catch (_: IllegalStateException) {
                true
            } catch (_: UnsupportedOperationException) {
                true
            }
        assertTrue("expected the provider to refuse", failed)
    }

    /** The first page of candidates the published build offers for [keys]. */
    private fun candidates(
        record: PublicationRecord,
        schema: String,
        keys: String,
    ): List<String> =
        runBlocking {
            val generation = storage.generationDir(record.generation)
            val user = File(context.cacheDir, "config-test-user").also { it.deleteRecursively() }
            val engine =
                RimeEngine.createPrebuilt(
                    sharedDataDir = File(generation, "resources"),
                    userDataDir = user,
                    appVersion = "test",
                    compiledDir = File(generation, "build"),
                )
            try {
                assertTrue(engine.selectSchema(schema))
                for (char in keys) engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
                engine.readSnapshot().candidates.map { it.text }
            } finally {
                engine.close()
                user.deleteRecursively()
            }
        }

    private companion object {
        const val PAGE_SIZE_PATCH = "patch:\n  menu/page_size: 3\n"
    }
}
