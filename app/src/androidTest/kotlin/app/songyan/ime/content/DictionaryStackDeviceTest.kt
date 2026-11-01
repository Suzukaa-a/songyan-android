package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.DictionaryImportHolder
import app.songyan.ime.settings.state.DictionaryImportResult
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * On a device: a dictionary imported for a scheme package is deployed by the real :deploy
 * process into that package's own dictionary, so the package's input method offers its words, and
 * removing it deploys the package without them again. The package files stay as imported.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryStackDeviceTest {
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
    fun theStackedDictionaryTranslatesInThePackagesInputMethodUntilItIsRemoved() =
        runBlocking {
            val imported = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage()))
            assertTrue("scheme import failed: $imported", imported is SchemeImportResult.Success)
            val target = requireNotNull(publisher.readActive()).sourceId
            scheme = target
            assertTrue(SourceIds.isScheme(target))
            val pristine = File(storage.sourceDir(target), "demo_stack.dict.yaml").readText()

            val stacked =
                DictionaryImportHolder.current.importFromUris(listOf(Uri.fromFile(dictionaryFile())), target)

            assertTrue("stacking failed: $stacked", stacked is DictionaryImportResult.Success)
            val withStack = requireNotNull(publisher.readActive())
            assertEquals(target, withStack.sourceId)
            assertTrue(publisher.isComplete(withStack))
            assertTrue("the imported word is offered", candidates(withStack, "songyan").contains(STACKED_WORD))
            assertTrue("the package's own words stay", candidates(withStack, "yanshi").contains("演示"))
            assertEquals(pristine, File(storage.sourceDir(target), "demo_stack.dict.yaml").readText())

            val removed = DictionaryImportHolder.current.removeImportedDictionary(target)

            assertTrue("removal failed: $removed", removed is DictionaryImportResult.Success)
            val without = requireNotNull(publisher.readActive())
            assertEquals(target, without.sourceId)
            assertFalse(storage.stackedDictionaryDir(target).exists())
            assertFalse("the word left with the dictionary", candidates(without, "songyan").contains(STACKED_WORD))
        }

    /** The candidates the published build offers for [keys] in the package's input method. */
    private suspend fun candidates(
        record: PublicationRecord,
        keys: String,
    ): List<String> {
        val generation = storage.generationDir(record.generation)
        val user = File(context.cacheDir, "stack-test-user").also { it.deleteRecursively() }
        val engine =
            RimeEngine.createPrebuilt(
                sharedDataDir = File(generation, "resources"),
                userDataDir = user,
                appVersion = "test",
                compiledDir = File(generation, "build"),
            )
        try {
            assertTrue(engine.selectSchema(SCHEMA))
            for (char in keys) engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
            return engine.readSnapshot().candidates.map { it.text }
        } finally {
            engine.close()
            user.deleteRecursively()
        }
    }

    private fun schemePackage(): File {
        val files =
            linkedMapOf(
                "demo-main/default.yaml" to "schema_list:\n  - schema: $SCHEMA\n",
                "demo-main/$SCHEMA.schema.yaml" to
                    "schema:\n  schema_id: $SCHEMA\n  name: 叠加演示\n" +
                    "engine:\n  processors:\n    - speller\n    - selector\n    - express_editor\n" +
                    "  segmentors:\n    - abc_segmentor\n  translators:\n    - script_translator\n" +
                    "speller:\n  alphabet: zyxwvutsrqponmlkjihgfedcba\ntranslator:\n  dictionary: demo_stack\n",
                "demo-main/demo_stack.dict.yaml" to
                    "---\nname: demo_stack\nversion: \"1\"\nsort: by_weight\n...\n演示\tyan shi\t1\n言\tyan\t1\n",
            )
        val target = File(context.cacheDir, "demo-stack.zip")
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return target
    }

    private fun dictionaryFile(): File =
        File(context.cacheDir, "stacked_words.dict.yaml").apply {
            writeText(
                "---\nname: stacked_words\nversion: \"1\"\nsort: by_weight\nuse_preset_vocabulary: false\n...\n" +
                    "$STACKED_WORD\tsong yan\t100\n",
            )
        }

    private companion object {
        const val SCHEMA = "demo_stack"
        const val STACKED_WORD = "松烟"
    }
}
