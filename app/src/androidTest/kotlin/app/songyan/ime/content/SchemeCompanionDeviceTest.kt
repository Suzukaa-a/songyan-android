package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeEngineBackend
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.SchemeImportHolder
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On a device: importing a scheme package makes the real :deploy process generate and compile
 * 14-, 18- and 9-key companions for its input method, and the keyboard's engine over the published
 * build offers them and types the package's words through them.
 */
@RunWith(AndroidJUnit4::class)
class SchemeCompanionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val storage = AppStorage(context.filesDir)
    private val publisher = GenerationPublisher(storage)
    private val settings = SettingsInForeground()
    private var scheme: String? = null

    @Before
    fun setUp() = settings.open()

    @After
    fun tearDown() {
        scheme?.let { id -> runCatching { runBlocking { StoredSourcesHolder.current.delete(id) } } }
        settings.close()
    }

    @Test
    fun anImportedSchemeTypesOnItsGeneratedCompactCompanions() =
        runBlocking {
            val imported = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage()))
            assertTrue("scheme import failed: $imported", imported is SchemeImportResult.Success)
            val record = requireNotNull(publisher.readActive())
            scheme = record.sourceId
            assertEquals(listOf(SCHEMA), record.schemaIds)

            val adapters = adaptersOf(record)
            assertEquals(
                listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9),
                adapters.keys.toList(),
            )
            assertTrue(candidates(record, adapters.getValue(KeyboardLayoutKind.MERGED14), "tabagu").contains(WORD))
            assertTrue(candidates(record, adapters.getValue(KeyboardLayoutKind.PHONE9), "926744").contains(WORD))
        }

    /**
     * A package with two input methods is usable as soon as the first has its layouts; the deploy
     * process then publishes a complete generation on its own, without another request.
     */
    @Test
    fun theOtherInputMethodsGetTheirLayoutsRightAfterTheImport() =
        runBlocking {
            val before = publisher.readActive()?.sequence ?: 0L
            val imported = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage(SCHEMA, SECOND)))
            assertTrue("scheme import failed: $imported", imported is SchemeImportResult.Success)
            // The completing generation may be out already; the one the import published is kept beside it.
            val first =
                publisher
                    .publishedRecords()
                    .filter { it.sequence > before }
                    .minBy { it.sequence }
            scheme = first.sourceId
            assertEquals(listOf(SCHEMA, SECOND), first.schemaIds)
            assertTrue("only the first input method is ready yet", first.companionsPending)
            assertEquals(setOf(SCHEMA), layoutsBySource(first).keys)

            var complete = requireNotNull(publisher.readActive())
            val deadline = System.currentTimeMillis() + 90_000
            while (complete.companionsPending && System.currentTimeMillis() < deadline) {
                delay(250)
                complete = requireNotNull(publisher.readActive())
            }

            assertFalse("the deploy process completed the generation", complete.companionsPending)
            val layouts = layoutsBySource(complete)
            val all = setOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9)
            assertEquals(mapOf(SCHEMA to all, SECOND to all), layouts)
        }

    private suspend fun layoutsBySource(record: PublicationRecord): Map<String, Set<KeyboardLayoutKind>> {
        val generation = storage.generationDir(record.generation)
        val user = File(context.cacheDir, "companion-test-user").also { it.deleteRecursively() }
        val backend =
            RimeEngineBackend.create(
                sharedDataDir = File(generation, "resources"),
                userDataDir = user,
                compiledDir = File(generation, "build"),
                appName = RimeEngine.DEFAULT_APP_NAME,
                appVersion = "test",
            )
        try {
            return backend.encodingAdapters
                .groupBy { it.sourceSchemaID }
                .mapValues { (_, adapters) -> adapters.map { it.layout }.toSet() }
        } finally {
            backend.shutdown()
            user.deleteRecursively()
        }
    }

    /** The companions the keyboard's backend reads from the published build, by layout. */
    private suspend fun adaptersOf(record: PublicationRecord): Map<KeyboardLayoutKind, String> {
        val generation = storage.generationDir(record.generation)
        val user = File(context.cacheDir, "companion-test-user").also { it.deleteRecursively() }
        val backend =
            RimeEngineBackend.create(
                sharedDataDir = File(generation, "resources"),
                userDataDir = user,
                compiledDir = File(generation, "build"),
                appName = RimeEngine.DEFAULT_APP_NAME,
                appVersion = "test",
            )
        try {
            assertEquals(listOf(SCHEMA), backend.availableSchemas.map { it.id })
            for (adapter in backend.encodingAdapters) {
                assertEquals(SCHEMA, adapter.sourceSchemaID)
                backend.validateSchema(adapter.schemaID)
            }
            return backend.encodingAdapters.associate { it.layout to it.schemaID }
        } finally {
            backend.shutdown()
            user.deleteRecursively()
        }
    }

    private suspend fun candidates(
        record: PublicationRecord,
        schema: String,
        keys: String,
    ): List<String> {
        val generation = storage.generationDir(record.generation)
        val user = File(context.cacheDir, "companion-test-user").also { it.deleteRecursively() }
        val engine =
            RimeEngine.createPrebuilt(
                sharedDataDir = File(generation, "resources"),
                userDataDir = user,
                appVersion = "test",
                compiledDir = File(generation, "build"),
            )
        try {
            assertTrue("cannot select $schema", engine.selectSchema(schema))
            for (char in keys) engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
            return engine.readSnapshot().candidates.map { it.text }
        } finally {
            engine.close()
            user.deleteRecursively()
        }
    }

    private fun schemePackage(vararg schemas: String = arrayOf(SCHEMA)): File {
        val files =
            linkedMapOf(
                "demo-main/default.yaml" to "schema_list:\n" + schemas.joinToString("") { "  - schema: $it\n" },
            )
        for (schema in schemas) {
            files["demo-main/$schema.schema.yaml"] =
                "schema:\n  schema_id: $schema\n  name: 配套演示\n" +
                "engine:\n  processors:\n    - speller\n    - selector\n    - express_editor\n" +
                "  segmentors:\n    - abc_segmentor\n  translators:\n    - script_translator\n" +
                "speller:\n  alphabet: zyxwvutsrqponmlkjihgfedcba\ntranslator:\n  dictionary: $SCHEMA\n"
        }
        files["demo-main/$SCHEMA.dict.yaml"] =
            "---\nname: $SCHEMA\nversion: \"1\"\nsort: by_weight\n...\n$WORD\tyan shi\t1\n言\tyan\t1\n"
        val target = File(context.cacheDir, "demo-companion.zip")
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return target
    }

    private companion object {
        const val SCHEMA = "demo_companion"
        const val SECOND = "demo_companion_two"
        const val WORD = "演示"
    }
}
