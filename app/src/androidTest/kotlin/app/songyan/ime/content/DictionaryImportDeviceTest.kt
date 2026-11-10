package app.songyan.ime.content

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.R
import app.songyan.ime.SongyanSettingsActivity
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployPipeline
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.DeployRequestStore
import app.songyan.ime.deploy.DeployService
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.packages.DictionaryImportBuilder
import app.songyan.ime.settings.state.DictionaryImportHolder
import app.songyan.ime.settings.state.DictionaryImportResult
import app.songyan.ime.settings.state.SchemeImportHolder
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.StoredSourceResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Runs the real dictionary import on a device: the settings gateway stages a source, the :deploy
 * process builds it with librime and publishes a generation, and a prebuilt engine is then asked
 * for candidates from the last generation.
 *
 * Regression coverage for device-only failures JVM tests cannot see: a build directory reused
 * across imports (tables and schema prism compiled from different dictionaries, so typing showed
 * preedit but no candidates) and a second import deleting the first one's staged source.
 *
 * Only one engine is created per process on purpose: librime allows a single engine per process,
 * and the keyboard's own engine may already be loaded in a process that hosted it.
 */
@RunWith(AndroidJUnit4::class)
class DictionaryImportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private lateinit var storage: AppStorage
    private lateinit var publisher: GenerationPublisher
    private var activity: Activity? = null

    @Before
    fun setUp() {
        storage = AppStorage(context.filesDir)
        publisher = GenerationPublisher(storage)
        // Settings asks for the notification permission on first launch; the system dialog would sit
        // on top of the task and keep every later launch of the activity from completing.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        // The deploy runs as a foreground service; Android 12+ only allows that from a visible app.
        activity =
            instrumentation.startActivitySync(
                Intent(context, SongyanSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
    }

    @After
    fun tearDown() {
        // A manual pass wants the imported dictionary left active on the device; CI keeps it clean.
        if (InstrumentationRegistry.getArguments().getString("leave-dictionary") != "1") {
            runCatching { runBlocking { DictionaryImportHolder.current.removeImportedDictionary() } }
        }
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        activity = null
    }

    @Test
    fun twoImportsInARowPublishFreshBuildsThatTranslate() =
        runBlocking {
            val gateway = DictionaryImportHolder.current
            assertTrue("the app installs the import gateway", gateway.available)

            val first = gateway.importFromUris(listOf(Uri.fromFile(dictFile("first", dictionary("test_a", WORD_A)))))
            assertTrue("first import failed: " + first, first is DictionaryImportResult.Success)
            // After the follow-up: it publishes again and the generation it replaces may be pruned.
            assertCleanedUp()
            val firstRecord = requireNotNull(publisher.readActive())
            assertEquals(DictionaryImportBuilder.SOURCE_ID, firstRecord.sourceId)
            assertTrue(publisher.isComplete(firstRecord))
            assertTrue(File(storage.generationDir(firstRecord.generation), "build/songyan_quanpin.prism.bin").isFile)
            // The next import of the same source supersedes this build, and pruning may drop it.
            val firstPrism = prismOf(firstRecord)
            val firstTable = tableOf(firstRecord)

            // A different dictionary must be built from scratch: with a reused build directory the
            // prism stayed compiled for the previous dictionary and lookups returned nothing.
            val second = gateway.importFromUris(listOf(Uri.fromFile(dictFile("second", dictionary("test_b", WORD_B)))))
            assertTrue("second import failed: " + second, second is DictionaryImportResult.Success)
            assertCleanedUp()
            val secondRecord = requireNotNull(publisher.readActive())
            assertNotEquals(firstRecord.generation, secondRecord.generation)
            assertTrue(publisher.isComplete(secondRecord))
            assertFalse(
                "a re-import must rebuild the prism, not inherit the previous one",
                firstPrism.contentEquals(prismOf(secondRecord)),
            )
            assertFalse(
                "a re-import must rebuild the tables, not inherit the previous ones",
                firstTable.contentEquals(tableOf(secondRecord)),
            )

            // Re-importing the exact same bytes keeps a stable revision; the request must still
            // publish a new generation instead of reporting the already active one as its result.
            val third =
                gateway.importFromUris(
                    listOf(Uri.fromFile(dictFile("first-again", dictionary("test_a", WORD_A)))),
                )
            assertTrue("re-import of identical bytes failed: " + third, third is DictionaryImportResult.Success)
            val thirdRecord = requireNotNull(publisher.readActive())
            assertEquals(firstRecord.revision, thirdRecord.revision)
            assertNotEquals(secondRecord.generation, thirdRecord.generation)
            assertCleanedUp()

            // A realistic dictionary (3000 rows plus 示例/狮力/果园/果实) is left active for the
            // manual keyboard pass; it also proves a large source still deploys and publishes.
            val big = gateway.importFromUris(listOf(Uri.fromFile(dictFile("big", bigDictionary()))))
            assertTrue("three-thousand-row import failed: " + big, big is DictionaryImportResult.Success)
            val bigRecord = requireNotNull(publisher.readActive())
            assertTrue(publisher.isComplete(bigRecord))
            assertEquals(DictionaryImportBuilder.SOURCE_ID, bigRecord.sourceId)
            assertCleanedUp()
        }

    @Test
    fun aSchemePackageIsStoredBesideTheDictionaryAndBothCanBeUsedAgain() =
        runBlocking {
            val dictionaries = DictionaryImportHolder.current
            val main =
                dictionary(
                    "test_main",
                    WORD_A,
                ).replace(
                    "use_preset_vocabulary: false\n",
                    "use_preset_vocabulary: false\nimport_tables:\n  - test_extra\n",
                )
            val imported =
                dictionaries.importFromUris(
                    listOf(
                        Uri.fromFile(dictFile("test_main", main)),
                        Uri.fromFile(dictFile("test_extra", dictionary("test_extra", WORD_B))),
                    ),
                )
            assertTrue("multi-file import failed: " + imported, imported is DictionaryImportResult.Success)
            val dictionaryRecord = requireNotNull(publisher.readActive())
            assertEquals(SourceIds.DICTIONARY, dictionaryRecord.sourceId)

            val scheme = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage()))
            assertTrue("scheme import failed: " + scheme, scheme is SchemeImportResult.Success)
            val schemeRecord = requireNotNull(publisher.readActive())
            assertTrue(SourceIds.isScheme(schemeRecord.sourceId))
            assertEquals(listOf("demo_scheme"), schemeRecord.schemaIds)
            assertTrue(File(storage.generationDir(schemeRecord.generation), "build/demo_scheme.prism.bin").isFile)
            assertTrue("importing a scheme keeps the dictionary", storage.sourceDir(SourceIds.DICTIONARY).isDirectory)

            val back = StoredSourcesHolder.current.use(SourceIds.DICTIONARY)
            assertEquals(StoredSourceResult.Success, back)
            assertEquals(SourceIds.DICTIONARY, publisher.readActive()?.sourceId)

            val deleted = StoredSourcesHolder.current.delete(schemeRecord.sourceId)
            assertEquals(StoredSourceResult.Success, deleted)
            assertEquals(
                "deleting an inactive source leaves the keyboard alone",
                SourceIds.DICTIONARY,
                publisher.readActive()?.sourceId,
            )
            assertEquals(null, SourceCatalog(storage).read(schemeRecord.sourceId))
            assertCleanedUp()
        }

    /**
     * A native crash in the :deploy process writes no terminal status. The import must report the
     * failure as soon as the process is gone instead of showing progress until its 20-minute
     * timeout, and the next import must work again.
     */
    @Test
    fun anImportEndsWhenTheDeployProcessDies() =
        runBlocking {
            val gateway = DictionaryImportHolder.current
            val known = requestFileNames()
            val doomed = Uri.fromFile(dictFile("doomed", bigDictionary()))
            val pending = async(Dispatchers.IO) { gateway.importFromUris(listOf(doomed)) }
            killDeployProcessWhileARequestRuns(known)
            val result = withTimeout(DEATH_REPORT_TIMEOUT_MS) { pending.await() }
            assertEquals(
                // A process that died is worth another try, so the result offers a retry.
                DictionaryImportResult.Failure(
                    context.getString(R.string.dictionary_import_error_deploy),
                    retryable = true,
                ),
                result,
            )
            assertEquals(
                "only the in-flight request may have a staging directory",
                emptyList<String>(),
                storage.importStagingDir
                    .listFiles()
                    .orEmpty()
                    .map { it.name },
            )

            val again = gateway.importFromUris(listOf(Uri.fromFile(dictFile("again", dictionary("test_a", WORD_A)))))
            assertTrue("an import after the crash failed: " + again, again is DictionaryImportResult.Success)
        }

    /**
     * Android 15+ allows dataSync foreground services 6 hours a day; after that starting one throws
     * and a running one gets onTimeout. Either used to crash :deploy, and the system's crash dialog
     * then kept the settings screen from launching. Imports must keep working without the budget.
     */
    @Test
    fun importsKeepWorkingWhenTheDailyDataSyncTimeIsUsedUp() =
        runBlocking {
            shell("device_config put activity_manager data_sync_fgs_timeout_duration 1")
            try {
                val gateway = DictionaryImportHolder.current
                for (round in 1..3) {
                    val words = if (round % 2 == 0) WORD_B else WORD_A
                    val result =
                        gateway.importFromUris(
                            listOf(Uri.fromFile(dictFile("budget", dictionary("test_budget", words)))),
                        )
                    assertTrue("import " + round + " failed: " + result, result is DictionaryImportResult.Success)
                }
                assertCleanedUp()
            } finally {
                shell("device_config delete activity_manager data_sync_fgs_timeout_duration")
            }
        }

    /**
     * The system may stop the deploy service with a request in flight, for example one from the
     * input method that runs without a foreground notification. Nobody is bound to watch for that,
     * so the service itself must leave a terminal status behind instead of a request that looks
     * running forever.
     */
    @Test
    fun aRequestStoppedWithTheServiceStillEnds() {
        val request = DeployRequest(DeployContract.newRequestId(), DeployMode.IMPORT_DICTIONARY)
        val files = File(storage.incomingDir(request.requestId), DeployPipeline.FILES).apply { mkdirs() }
        File(files, "stopped.dict.yaml").writeText(bigDictionary())
        val requests = DeployRequestStore(storage)
        ContextCompat.startForegroundService(context, DeployContract.intent(context, request))
        awaitState(request.requestId) { it == RequestState.RUNNING }
        context.stopService(Intent(context, DeployService::class.java))
        awaitState(request.requestId) { it.isTerminal }
        assertTrue(requests.read(request.requestId)!!.state.isTerminal)
    }

    private fun awaitState(
        requestId: String,
        accept: (RequestState) -> Boolean,
    ) {
        val requests = DeployRequestStore(storage)
        val deadline = SystemClock.elapsedRealtime() + RUNNING_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            requests.read(requestId)?.state?.let { if (accept(it)) return }
            Thread.sleep(5)
        }
        throw AssertionError("request " + requestId + " is still " + requests.read(requestId)?.state)
    }

    private fun shell(command: String) {
        instrumentation.uiAutomation.executeShellCommand(command).close()
    }

    /** Waits for a new request to be RUNNING in :deploy, then kills that process like a native crash would. */
    private fun killDeployProcessWhileARequestRuns(known: Set<String>) {
        val requests = DeployRequestStore(storage)
        val deadline = SystemClock.elapsedRealtime() + RUNNING_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val running =
                (requestFileNames() - known).any { name ->
                    requests.read(name.removeSuffix(".json"))?.state == RequestState.RUNNING
                }
            if (running) {
                val manager = context.getSystemService(ActivityManager::class.java)
                val deploy =
                    manager.runningAppProcesses.orEmpty().first { it.processName == context.packageName + ":deploy" }
                Process.killProcess(deploy.pid)
                return
            }
            Thread.sleep(5)
        }
        throw AssertionError("no import request started running")
    }

    private fun requestFileNames(): Set<String> =
        storage.requestsDir
            .listFiles()
            .orEmpty()
            .map { it.name }
            .filter { it.endsWith(".json") }
            .toSet()

    /** A minimal scheme package the way GitHub's "Download ZIP" wraps it, with Finder metadata. */
    private fun schemePackage(): File {
        val files =
            linkedMapOf(
                "demo-main/default.yaml" to "schema_list:\n  - schema: demo_scheme\n",
                "demo-main/demo_scheme.schema.yaml" to
                    "schema:\n  schema_id: demo_scheme\n  name: 演示方案\n" +
                    "engine:\n  processors:\n    - speller\n    - selector\n    - express_editor\n" +
                    "  segmentors:\n    - abc_segmentor\n  translators:\n    - script_translator\n" +
                    "speller:\n  alphabet: zyxwvutsrqponmlkjihgfedcba\ntranslator:\n  dictionary: demo_scheme\n",
                "demo-main/demo_scheme.dict.yaml" to
                    "---\nname: demo_scheme\nversion: \"1\"\nsort: by_weight\n...\n演示\tyan shi\t1\n",
                "demo-main/README.md" to "# demo\n",
                "__MACOSX/demo-main/._README.md" to "junk",
            )
        val target = File(context.cacheDir, "demo-scheme.zip")
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return target
    }

    private fun dictionary(
        name: String,
        rows: List<String>,
    ): String =
        buildString {
            appendLine("---")
            appendLine("name: " + name)
            appendLine("version: \"1.0\"")
            appendLine("sort: by_weight")
            appendLine("use_preset_vocabulary: false")
            appendLine("...")
            rows.forEach { appendLine(it) }
        }

    private fun dictFile(
        name: String,
        content: String,
    ): File =
        File(context.cacheDir, name + ".dict.yaml").apply {
            writeText(content)
        }

    /**
     * Builds a 3000-row dictionary from the app's own built-in entries: single rows first, then
     * two-word combinations. Deterministic, no third-party data, and large enough to be a realistic
     * import; the imported-only words used by the checks come first so they win on weight.
     */
    private fun bigDictionary(): String {
        val builtin =
            context.assets
                .open("rime/builtin/songyan_basic.dict.yaml")
                .bufferedReader()
                .useLines { lines -> lines.toList() }
        val end = builtin.indexOfFirst { it.trim() == "..." }
        val entries =
            builtin
                .drop(end + 1)
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .map { it.split('\t') }
                .filter { it.size >= 2 }
        val rows = mutableListOf<String>()
        rows += WORD_A
        rows += WORD_B
        for (entry in entries) {
            if (rows.size >= 3000) break
            rows += entry[0] + "\t" + entry[1] + "\t" + entry.getOrElse(2) { "1" }
        }
        var index = 0
        while (rows.size < 3000) {
            val first = entries[index % entries.size]
            val second = entries[(index / entries.size + index) % entries.size]
            rows += first[0] + second[0] + "\t" + first[1] + " " + second[1] + "\t1"
            index++
        }
        return dictionary("test_three_thousand", rows)
    }

    private fun prismOf(record: PublicationRecord): ByteArray =
        File(storage.generationDir(record.generation), "build/songyan_quanpin.prism.bin").readBytes()

    private fun tableOf(record: PublicationRecord): ByteArray =
        File(storage.generationDir(record.generation), "build/songyan_imported.table.bin").readBytes()

    private fun assertCleanedUp() {
        assertEquals(
            "only the in-flight request may have a staging directory",
            emptyList<String>(),
            storage.importStagingDir
                .listFiles()
                .orEmpty()
                .map { it.name },
        )
        assertFalse(
            "the scratch build directory must not outlive the deploy",
            storage.deployWorkDir(DictionaryImportBuilder.SOURCE_ID).exists(),
        )
    }

    private companion object {
        /** A dead process is reported by the binder at once; this only bounds a regression. */
        const val DEATH_REPORT_TIMEOUT_MS = 30_000L
        const val RUNNING_TIMEOUT_MS = 60_000L

        /** 示例(shí lì), 狮力(shī lì): two entries over the same syllables. */
        val WORD_A =
            listOf(
                "示例\tshi li\t100",
                "狮力\tshi li\t50",
            )

        /** 果园(guǒ yuán), 果实(guǒ shí): the second import must be able to look these up. */
        val WORD_B =
            listOf(
                "果园\tguo yuan\t100",
                "果实\tguo shi\t60",
            )
    }
}
