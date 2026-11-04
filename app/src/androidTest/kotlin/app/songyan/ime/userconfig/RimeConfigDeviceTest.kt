package app.songyan.ime.userconfig

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.R
import app.songyan.ime.SongyanApplication
import app.songyan.ime.content.SettingsInForeground
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.RimeConfigApplyResult
import app.songyan.ime.settings.state.RimeConfigEditResult
import app.songyan.ime.settings.state.RimeConfigFileResult
import app.songyan.ime.settings.state.RimeConfigHolder
import app.songyan.ime.settings.state.RimeConfigSaveResult
import app.songyan.ime.settings.state.RimeEditTarget
import app.songyan.ime.settings.state.RimeScriptCreateResult
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
        storage.userConfigDir.listFiles()?.forEach { it.deleteRecursively() }
        storage.overrideMarksFile.delete()
        storage.scriptsConfirmedFile.delete()
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

        // The source in use can be read; its text files take writes through the user's copy.
        val sources = children(RimeConfigProvider.ACTIVE)
        assertTrue(sources.toString(), "default.yaml" in sources)
        assertFalse("the app's own files stay hidden", "songyan-source.json" in sources)
        val schema =
            DocumentsContract.buildDocumentUri(
                authority,
                RimeConfigProvider.ACTIVE + "/songyan_quanpin.schema.yaml",
            )
        assertTrue(read(schema).contains("songyan_quanpin"))
        // Anything that is not a text configuration stays read-only.
        val binary =
            storage
                .sourceDir(SourceIds.BUILTIN)
                .walkTopDown()
                .firstOrNull { it.isFile && it.extension !in setOf("yaml", "txt", "lua") && !it.name.startsWith(".") }
        assumeTrue("the built-in source has no non-text file", binary != null)
        val path = requireNotNull(binary).relativeTo(storage.sourceDir(SourceIds.BUILTIN)).invariantSeparatorsPath
        val other = DocumentsContract.buildDocumentUri(authority, RimeConfigProvider.ACTIVE + "/" + path)
        assertFailsToOpen { resolver.openOutputStream(other, "wt")?.also { it.close() } }
    }

    @Test
    fun writingAPackageFileMakesTheUsersCopyAndAppliesWhileThePackageStaysAsItWas() {
        val sourceDir = storage.sourceDir(SourceIds.BUILTIN)
        val packaged = File(sourceDir, "default.yaml")
        val original = packaged.readText()
        assertTrue(original.contains("page_size: 5"))
        val doc = DocumentsContract.buildDocumentUri(authority, RimeConfigProvider.ACTIVE + "/default.yaml")
        val mine = original.replace("page_size: 5", "page_size: 3")

        requireNotNull(resolver.openOutputStream(doc, "wt")).use { it.write(mine.toByteArray()) }
        val copy = File(storage.userConfigDir, "default.yaml")
        waitFor { copy.isFile && copy.readText() == mine }

        assertEquals("the package is never modified", original, packaged.readText())
        assertEquals("reading shows the file in effect", mine, read(doc))
        assertTrue("default.yaml" in children(RimeConfigProvider.EDIT))
        val info = runBlocking { RimeConfigHolder.current.info() }
        assertEquals(listOf("default.yaml"), info.overrides.map { it.path })
        assertEquals(listOf("default.yaml"), info.unacknowledged)

        val before = requireNotNull(publisher.readActive())
        assertEquals(RimeConfigApplyResult.Applied, apply())
        val after = requireNotNull(publisher.readActive())
        assertEquals(3, candidates(after, "songyan_quanpin", "ni").size)
        assertTrue(before.generation != after.generation)

        runBlocking { RimeConfigHolder.current.acknowledge(listOf("default.yaml")) }
        assertTrue(runBlocking { RimeConfigHolder.current.info() }.unacknowledged.isEmpty())

        runBlocking { RimeConfigHolder.current.restore("default.yaml") }
        assertFalse(copy.exists())
        assertEquals("the package's own file is read again", original, read(doc))
        assertEquals(RimeConfigApplyResult.Applied, apply())
        assertEquals(5, candidates(requireNotNull(publisher.readActive()), "songyan_quanpin", "ni").size)
    }

    @Test
    fun aPackageFileInAFolderGetsTheSameFolderInTheUsersFolder() {
        val sourceDir = storage.sourceDir(SourceIds.BUILTIN)
        val probe = File(sourceDir, "lua/probe.lua").also { it.parentFile?.mkdirs() }
        probe.writeText("return 'package'\n")
        val big = File(sourceDir, "probe-big.txt")
        big.writeBytes(ByteArray((UserConfigFiles.MAX_FILE_BYTES + 1).toInt()) { 'a'.code.toByte() })
        try {
            val doc = DocumentsContract.buildDocumentUri(authority, RimeConfigProvider.ACTIVE + "/lua/probe.lua")
            requireNotNull(resolver.openOutputStream(doc, "wt")).use { it.write("return 'mine'\n".toByteArray()) }
            waitFor { File(storage.userConfigDir, "lua/probe.lua").isFile }

            assertEquals("return 'mine'\n", read(doc))
            assertEquals("return 'package'\n", probe.readText())
            assertTrue("lua" in children(RimeConfigProvider.EDIT))
            assertEquals(listOf("probe.lua"), children(RimeConfigProvider.EDIT + "/lua"))
            assertTrue(
                runBlocking { RimeConfigHolder.current.info() }.overrides.any { it.path == "lua/probe.lua" },
            )

            // A file over the limit is refused with the size message, and nothing is created.
            val tooBig = DocumentsContract.buildDocumentUri(authority, RimeConfigProvider.ACTIVE + "/probe-big.txt")
            val error =
                try {
                    resolver.openOutputStream(tooBig, "wt")?.close()
                    null
                } catch (e: IllegalStateException) {
                    e.message
                } catch (e: SecurityException) {
                    e.message
                }
            assertEquals(context.getString(R.string.rime_config_refused_large, "probe-big.txt"), error)
            assertFalse(File(storage.userConfigDir, "probe-big.txt").exists())

            val own = DocumentsContract.buildDocumentUri(authority, "edit/lua/probe.lua")
            assertTrue(DocumentsContract.deleteDocument(resolver, own))
            assertFalse("the emptied folder goes too", File(storage.userConfigDir, "lua").exists())
            assertEquals("return 'package'\n", read(doc))
        } finally {
            probe.parentFile?.deleteRecursively()
            big.delete()
        }
    }

    @Test
    fun theInAppEditorLoadsSavesAndRefusesThroughTheRealStorage() {
        val gateway = RimeConfigHolder.current
        val targets = runBlocking { gateway.info() }.editTargets
        assertEquals("songyan_quanpin", targets.first().key)

        val loaded = runBlocking { gateway.loadFile("songyan_quanpin") } as RimeConfigFileResult.Loaded
        val file = File(storage.userConfigDir, "songyan_quanpin.custom.yaml")
        assertEquals("songyan_quanpin.custom.yaml", loaded.fileName)
        assertTrue(loaded.text.endsWith("patch: {}\n"))
        assertFalse(file.exists())

        val invalid = "patch:\n  menu/page_size: [3\n"
        val original = requireNotNull(UserConfigFiles.check(loaded.fileName, invalid.toByteArray())).detail
        val broken = runBlocking { gateway.saveFile("songyan_quanpin", invalid) }
        assertTrue(broken is RimeConfigSaveResult.Failure)
        assertEquals(
            context.getString(R.string.rime_config_error_detail, loaded.fileName, original),
            (broken as RimeConfigSaveResult.Failure).message,
        )
        assertFalse("a refused save creates no override", file.exists())
        val outside = runBlocking { gateway.saveFile("songyan_quanpin", "patch:\n  translator/dictionary: ../../x\n") }
        assertTrue(outside is RimeConfigSaveResult.Failure)
        assertFalse(file.exists())

        assertEquals(RimeConfigSaveResult.Saved, runBlocking { gateway.saveFile("songyan_quanpin", PAGE_SIZE_PATCH) })
        assertEquals(PAGE_SIZE_PATCH, file.readText())
        assertEquals(
            PAGE_SIZE_PATCH,
            (runBlocking { gateway.loadFile("songyan_quanpin") } as RimeConfigFileResult.Loaded).text,
        )
        assertEquals(RimeConfigApplyResult.Applied, apply())
        assertEquals(3, candidates(requireNotNull(publisher.readActive()), "songyan_quanpin", "ni").size)

        // The grammar entry adds commented-out parameters and writes nothing.
        val grammar =
            runBlocking {
                gateway.loadFile(
                    "songyan_quanpin",
                    grammarExample = true,
                )
            } as RimeConfigFileResult.Loaded
        assertTrue(grammar.text.contains("#   grammar/collocation_max_length: 6"))
        assertEquals(PAGE_SIZE_PATCH, file.readText())
        runBlocking { gateway.loadFile(RimeEditTarget.GLOBAL_KEY) }
        assertFalse(File(storage.userConfigDir, "default.custom.yaml").exists())
    }

    @Test
    fun theFileOpensInAnotherAppThroughAWritableDocumentAddress() {
        val ready =
            runBlocking {
                RimeConfigHolder.current.externalEditIntents(
                    "songyan_quanpin",
                )
            } as RimeConfigEditResult.Ready
        val file = File(storage.userConfigDir, "songyan_quanpin.custom.yaml")
        val intent = ready.intents.first()
        assertEquals(Intent.ACTION_EDIT, intent.action)
        assertEquals("text/plain", intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION != 0)
        assertEquals(Intent.ACTION_VIEW, ready.intents.last().action)
        assertEquals(file.readText(), read(requireNotNull(intent.data)))

        write(requireNotNull(intent.data), PAGE_SIZE_PATCH)
        assertEquals(PAGE_SIZE_PATCH, file.readText())
        assertEquals(RimeConfigApplyResult.Applied, apply())
        assertEquals(3, candidates(requireNotNull(publisher.readActive()), "songyan_quanpin", "ni").size)
    }

    @Test
    fun aScriptWaitsForTheUsersYesBeforeAnythingIsApplied() {
        val sourceDir = storage.sourceDir(SourceIds.BUILTIN)
        val probe = File(sourceDir, "lua/probe.lua").also { it.parentFile?.mkdirs() }
        probe.writeText("return 'package'\n")
        try {
            val doc = DocumentsContract.buildDocumentUri(authority, RimeConfigProvider.ACTIVE + "/lua/probe.lua")
            requireNotNull(resolver.openOutputStream(doc, "wt")).use { it.write("return 'mine'\n".toByteArray()) }
            waitFor { File(storage.userConfigDir, "lua/probe.lua").isFile }
            val before = requireNotNull(publisher.readActive())

            val first = apply() as RimeConfigApplyResult.NeedsConfirmation
            assertEquals(listOf("lua/probe.lua"), first.scripts)
            assertTrue(first.firstTime)
            assertEquals("nothing was applied", before.generation, publisher.readActive()!!.generation)

            val confirmed = runBlocking { RimeConfigHolder.current.apply(first.keys) }
            assertEquals(RimeConfigApplyResult.Applied, confirmed)
            val after = requireNotNull(publisher.readActive())
            assertEquals(
                "return 'mine'\n",
                File(storage.generationDir(after.generation), "resources/lua/probe.lua").readText(),
            )
            assertEquals("the same script is not asked about again", RimeConfigApplyResult.Applied, apply())
        } finally {
            probe.parentFile?.deleteRecursively()
        }
    }

    @Test
    fun traversalEncodedTraversalAndNulIdsAreRefusedForReadingAndWriting() {
        for (id in listOf(
            "edit/../applied/x.yaml",
            "edit/%2e%2e/x.yaml",
            "edit/lua/..%2fx.yaml",
            "edit/a\u0000.yaml",
            "active/../../shared_prefs/x.xml",
            "active/%2e%2e/%2e%2e/shared_prefs/x.xml",
            "active/default.yaml\u0000.txt",
            "sources/../../databases/x",
            "sources/%2e%2e/x.yaml",
        )) {
            val uri = DocumentsContract.buildDocumentUri(authority, id)
            assertFailsToOpen { resolver.openInputStream(uri)?.also { it.close() } }
            assertFailsToOpen { resolver.openOutputStream(uri, "wt")?.also { it.close() } }
        }
        assertEquals("nothing was made", emptyList<String>(), children(RimeConfigProvider.EDIT))
    }

    @Test
    fun aFileManagerMakesTheLuaFolderAndScriptsInItButNothingElse() {
        // A script of the user's own goes in `lua/` (or is `rime.lua`); nowhere else, and no other folders.
        assertFailsToOpen { DocumentsContract.createDocument(resolver, editFolder, "text/plain", "date.lua") }
        assertFailsToOpen {
            DocumentsContract.createDocument(resolver, editFolder, DocumentsContract.Document.MIME_TYPE_DIR, "other")
        }
        val lua =
            requireNotNull(
                DocumentsContract.createDocument(resolver, editFolder, DocumentsContract.Document.MIME_TYPE_DIR, "lua"),
            )
        assertEquals("edit/lua", DocumentsContract.getDocumentId(lua))
        assertTrue("an empty lua folder shows, so scripts can be put in it", "lua" in children(RimeConfigProvider.EDIT))
        assertFailsToOpen { DocumentsContract.createDocument(resolver, lua, "text/plain", "notes.txt") }
        val script =
            requireNotNull(DocumentsContract.createDocument(resolver, lua, "application/octet-stream", "date.lua"))
        assertEquals("edit/lua/date.lua", DocumentsContract.getDocumentId(script))
        val twin =
            requireNotNull(DocumentsContract.createDocument(resolver, lua, "application/octet-stream", "Date.lua"))
        assertEquals("edit/lua/Date-2.lua", DocumentsContract.getDocumentId(twin))
        val renamed = requireNotNull(DocumentsContract.renameDocument(resolver, script, "day.lua"))
        assertEquals("edit/lua/day.lua", DocumentsContract.getDocumentId(renamed))
        assertFailsToOpen { DocumentsContract.renameDocument(resolver, renamed, "day.yaml")?.also { error("renamed") } }
        assertEquals(setOf("day.lua", "Date-2.lua"), children("edit/lua").toSet())
        val rime = requireNotNull(DocumentsContract.createDocument(resolver, editFolder, "text/plain", "rime.lua"))
        assertEquals("edit/rime.lua", DocumentsContract.getDocumentId(rime))
        assertFailsToOpen { DocumentsContract.createDocument(resolver, editFolder, "text/plain", "rime.lua") }

        // Plain files stay flat, and a name that differs only in case is made unique.
        val first = create("custom_phrase.txt", "x\n")
        val phrase =
            requireNotNull(DocumentsContract.createDocument(resolver, editFolder, "text/plain", "Custom_Phrase.txt"))
        assertEquals("edit/Custom_Phrase-2.txt", DocumentsContract.getDocumentId(phrase))
        assertTrue(DocumentsContract.deleteDocument(resolver, first))
    }

    @Test
    fun aUsersScriptNamedInAPatchRunsOnlyAfterTheUserAcceptedTheRisk() {
        val lua =
            requireNotNull(
                DocumentsContract.createDocument(resolver, editFolder, DocumentsContract.Document.MIME_TYPE_DIR, "lua"),
            )
        val script =
            requireNotNull(
                DocumentsContract.createDocument(resolver, lua, "application/octet-stream", "probe_filter.lua"),
            )
        write(script, PROBE_FILTER)
        create(
            "songyan_quanpin.custom.yaml",
            "patch:\n  engine/filters/+:\n    - lua_filter@*probe_filter\n",
        )
        val before = requireNotNull(publisher.readActive())

        val first = apply() as RimeConfigApplyResult.NeedsConfirmation
        assertTrue("the first time explains the risk", first.firstTime)
        assertEquals(
            listOf("lua/probe_filter.lua", "songyan_quanpin.custom.yaml: lua_filter@*probe_filter"),
            first.scripts,
        )
        assertEquals("nothing was applied", before.generation, publisher.readActive()!!.generation)
        assertFalse("declining sets nothing", storage.scriptsConfirmedFile.exists())
        assertTrue("it asks again", apply() is RimeConfigApplyResult.NeedsConfirmation)
        assertTrue(
            "a yes to something else is not a yes to this",
            runBlocking {
                RimeConfigHolder.current.apply(
                    listOf("lua/other.lua@0"),
                )
            } is RimeConfigApplyResult.NeedsConfirmation,
        )
        assertFalse(storage.scriptsConfirmedFile.exists())

        assertEquals(
            RimeConfigApplyResult.Applied,
            runBlocking { RimeConfigHolder.current.apply(first.keys) },
        )
        val after = requireNotNull(publisher.readActive())
        assertTrue(candidates(after, "songyan_quanpin", "ni").contains("LUAPROBE"))
        assertEquals("nothing new to ask about", RimeConfigApplyResult.Applied, apply())

        // A later change is asked about again, without the long explanation.
        write(script, PROBE_FILTER + "-- changed\n")
        val later = apply() as RimeConfigApplyResult.NeedsConfirmation
        assertFalse(later.firstTime)
        assertEquals(listOf("lua/probe_filter.lua"), later.scripts)
    }

    @Test
    fun aRunawayWriterIsCutOffAndItsWriteIsDiscarded() {
        val uri = create("custom_phrase.txt", "x\n")
        val stream = requireNotNull(resolver.openOutputStream(uri, "wt"))
        val chunk = ByteArray(1024 * 1024) { 'a'.code.toByte() }
        repeat(24) { stream.write(chunk) }
        stream.flush()
        Thread.sleep(600)

        val temp =
            storage.configRoot
                .listFiles { f -> f.name.startsWith(".write-") }
                .orEmpty()
                .single()
        val used =
            android.system.Os
                .stat(temp.path)
                .st_blocks * 512
        assertTrue("the temporary file gave its space back: $used bytes", used < UserConfigFiles.MAX_FILE_BYTES)

        stream.close()
        waitFor { storage.configRoot.listFiles { f -> f.name.startsWith(".write-") }.isNullOrEmpty() }
        assertEquals("x\n", read(uri))
    }

    @Test
    fun onlyAFewWritersCanBeOpenAtOnce() {
        val uri = create("custom_phrase.txt", "x\n")
        val open = ArrayList<java.io.OutputStream>()
        try {
            repeat(8) { open += requireNotNull(resolver.openOutputStream(uri, "wt")) }
            val refused =
                try {
                    resolver.openOutputStream(uri, "wt")?.close()
                    false
                } catch (_: FileNotFoundException) {
                    true
                }
            assertTrue("a ninth writer is refused", refused)
        } finally {
            open.forEach { it.close() }
        }
        waitFor { storage.configRoot.listFiles { f -> f.name.startsWith(".write-") }.isNullOrEmpty() }
        requireNotNull(resolver.openOutputStream(uri, "wt")).close()
    }

    @Test
    fun aNewScriptIsMadeFromTheTemplateAndWaitsForTheUsersYes() {
        val gateway = RimeConfigHolder.current
        val created = runBlocking { gateway.createScript("probe_new") } as RimeScriptCreateResult.Created
        assertEquals("script:probe_new", created.key)
        val file = File(storage.userConfigDir, "lua/probe_new.lua")
        assertTrue(file.readText().contains("lua_filter@*probe_new"))
        val loaded = runBlocking { gateway.loadFile(created.key) } as RimeConfigFileResult.Loaded
        assertEquals("lua/probe_new.lua", loaded.fileName)
        assertEquals(
            RimeConfigSaveResult.Saved,
            runBlocking { gateway.saveFile(created.key, loaded.text + "-- edit\n") },
        )

        val first = apply() as RimeConfigApplyResult.NeedsConfirmation
        assertEquals(listOf("lua/probe_new.lua"), first.scripts)
        assertTrue(first.firstTime)
        assertEquals(RimeConfigApplyResult.Applied, runBlocking { gateway.apply(first.keys) })
        assertTrue(runBlocking { gateway.info() }.unusedScripts.isEmpty())
    }

    @Test
    fun scriptsLeftOutBecauseNobodyConfirmedThemAreReported() {
        val gateway = RimeConfigHolder.current
        runBlocking { gateway.createScript("probe_unused") }
        val first = apply() as RimeConfigApplyResult.NeedsConfirmation
        runBlocking { gateway.apply(first.keys) }
        // A restored backup has the applied copy but not the confirmations.
        storage.scriptsConfirmedFile.delete()
        assertEquals(listOf("lua/probe_unused.lua"), runBlocking { gateway.info() }.unusedScripts)
    }

    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertTrue("timed out waiting for the provider", condition())
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
            (context.applicationContext as SongyanApplication).engineOwnership.exclusive {
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
        }

    private companion object {
        const val PAGE_SIZE_PATCH = "patch:\n  menu/page_size: 3\n"

        /** A filter that puts one extra candidate first, so a script that ran is visible in the candidates. */
        const val PROBE_FILTER =
            "return {\n  func = function(input, env)\n" +
                "    yield(Candidate('probe', 0, 2, 'LUAPROBE', ''))\n" +
                "    for cand in input:iter() do yield(cand) end\n  end,\n}\n"
    }
}
