package app.songyan.ime

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.content.SettingsInForeground
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.DeployRequestStore
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.deploy.RequestStatus
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.RecommendedScheme
import app.songyan.ime.settings.state.RecommendedSchemes
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Installs every traditional catalogue entry the way the settings screen does, but from the archives
 * `scripts/fetch-test-schemes.sh` already downloaded and pushed: the pinned ZIPs go into the deploy
 * request's incoming directory, the real :deploy process merges them, installs and publishes the
 * package, and a real engine started on the published generation produces candidates. Nothing here
 * downloads anything.
 *
 * This fixture installs and removes sources, so it requires an isolated app profile containing
 * only the bundled source. Provision the archives in a directory readable by the app, then run
 * `am instrument -w -e class app.songyan.ime.TraditionalCatalogInstallDeviceTest
 * <test package>/androidx.test.runner.AndroidJUnitRunner`; `-e schemeDir` overrides the app fixture cache.
 */
@RunWith(AndroidJUnit4::class)
class TraditionalCatalogInstallDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val app = context.applicationContext as SongyanApplication
    private val storage: AppStorage = app.storage
    private val requests = DeployRequestStore(storage)
    private val publisher = GenerationPublisher(storage)
    private val catalog = SourceCatalog(storage)
    private val settings = SettingsInForeground()
    private val installed = mutableListOf<String>()

    @Before
    fun archivesAreProvisioned() {
        assertTrue(
            "use an isolated app profile; existing imported sources must stay untouched",
            catalog.list().all { it.sourceId == SourceIds.BUILTIN },
        )
        assumeTrue(
            "run scripts/fetch-test-schemes.sh --push, then pass -e $DIR_ARGUMENT <dir>: " +
                "no pinned archives in " + archiveDirectory(),
            hasEveryArchive(archiveDirectory()),
        )
        settings.open()
    }

    @After
    fun restore() =
        runBlocking {
            try {
                installed.reversed().forEach { id ->
                    StoredSourcesHolder.current.delete(id)
                    assertTrue("fixture source was not removed: " + id, catalog.read(id) == null)
                }
            } finally {
                installed.clear()
                settings.close()
            }
        }

    @Test
    fun bopomofoInstallsFromItsPinnedArchivesAndTypes() {
        installAndType(entry("rime-bopomofo"), code = "su3", expected = "你")
    }

    @Test
    fun cangjieInstallsFromItsPinnedArchivesAndTypes() {
        installAndType(entry("rime-cangjie"), code = "onf", expected = "你")
    }

    @Test
    fun quickInstallsFromItsPinnedArchivesAndTypes() {
        installAndType(entry("rime-quick"), code = "of", expected = "你")
    }

    @Test
    fun cantoneseInstallsFromItsPinnedArchivesAndTypes() {
        installAndType(entry("rime-cantonese"), code = "neihou", expected = "你好")
    }

    /**
     * Stages the entry's archives exactly as the pinned download leaves them, asks :deploy to import
     * them, waits for the published generation, then types on the real engine.
     */
    private fun installAndType(
        entry: RecommendedScheme,
        code: String,
        expected: String,
    ) = runBlocking<Unit> {
        val requestId = DeployContract.newRequestId()
        val incoming = storage.incomingDir(requestId).also { it.mkdirs() }
        var archiveBytes = 0L
        val names =
            entry.archives.mapIndexed { index, archive ->
                val source = File(archiveDirectory(), fileNameOf(archive.url))
                assertTrue("missing " + source, source.isFile)
                assertEquals(
                    "the pushed archive is not the one this entry pins: " + source.name,
                    archive.sha256,
                    sha256(source),
                )
                archiveBytes += source.length()
                val name = "pinned-" + index + ".zip"
                source.copyTo(File(incoming, name), overwrite = true)
                name
            }
        val request =
            DeployRequest(
                requestId,
                DeployMode.IMPORT_SCHEME,
                packageFiles = names,
                advertisedSchemas = entry.advertisedSchemas,
            )
        val started = SystemClock.elapsedRealtime()
        ContextCompat.startForegroundService(context, DeployContract.intent(context, request))

        val status = awaitTerminal(requestId)
        val installMs = SystemClock.elapsedRealtime() - started
        assertEquals("deploy problem: " + status.problem, RequestState.SUCCEEDED, status.state)
        val sourceId = requireNotNull(status.sourceId) { "no package after " + entry.id }
        installed += sourceId
        val record = awaitPublished(sourceId)
        val manifest = requireNotNull(catalog.read(sourceId))
        assertEquals("only the catalogue's input methods are offered", entry.advertisedSchemas, manifest.schemaIds)

        val candidates = candidates(record, manifest.primarySchemaId, code)
        println(
            "catalog " + entry.id + " bytes=" + archiveBytes + " installMs=" + installMs +
                " schema=" + manifest.primarySchemaId + " candidates=" + candidates.take(5),
        )
        assertTrue("no candidates for " + code + " in " + entry.id, candidates.isNotEmpty())
        assertTrue(
            entry.id + " typing " + code + " gave " + candidates,
            candidates.any { it == expected },
        )
    }

    private suspend fun awaitTerminal(requestId: String): RequestStatus {
        val deadline = SystemClock.elapsedRealtime() + DEPLOY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = withContext(Dispatchers.IO) { requests.read(requestId) }
            if (status != null && status.state.isTerminal) return status
            delay(POLL_MS)
        }
        throw AssertionError("request " + requestId + " did not finish in " + DEPLOY_TIMEOUT_MS + " ms")
    }

    /** The published generation of [sourceId], once its compact layouts are complete too. */
    private suspend fun awaitPublished(sourceId: String): PublicationRecord {
        val deadline = SystemClock.elapsedRealtime() + DEPLOY_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val active = publisher.readActive()
            if (active != null && active.sourceId == sourceId && publisher.isComplete(active)) return active
            delay(POLL_MS)
        }
        throw AssertionError("no complete generation of " + sourceId + " within " + DEPLOY_TIMEOUT_MS + " ms")
    }

    /** Types [code] on an engine over the published generation; its learning data stays in the cache. */
    private suspend fun candidates(
        record: PublicationRecord,
        schemaId: String,
        code: String,
    ): List<String> {
        val generation = storage.generationDir(record.generation)
        val userData = File(context.cacheDir, "traditional-catalog-engine")
        userData.deleteRecursively()
        userData.mkdirs()
        val engine =
            RimeEngine.createPrebuilt(
                sharedDataDir = File(generation, "resources"),
                userDataDir = userData,
                appName = RimeBindings.APP_NAME,
                appVersion = "test",
                compiledDir = File(generation, "build"),
            )
        return try {
            assertTrue("the published generation has no " + schemaId, engine.selectSchema(schemaId))
            engine.clearComposition()
            for (char in code) {
                engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
            }
            engine.readSnapshot().candidates.map { it.text }
        } finally {
            engine.close()
        }
    }

    private fun entry(id: String): RecommendedScheme = RecommendedSchemes.catalog.first { it.id == id }

    /** The archives of every traditional entry, named as the script writes them. */
    private fun hasEveryArchive(directory: File): Boolean =
        RecommendedSchemes.catalog
            .filter { it.id in TRADITIONAL_IDS }
            .flatMap { it.archives }
            .all { File(directory, fileNameOf(it.url)).isFile }

    private fun archiveDirectory(): File =
        InstrumentationRegistry.getArguments().getString(DIR_ARGUMENT)?.let(::File)
            ?: File(context.cacheDir, "songyan-test-schemes")

    /**
     * A codeload link names the repository zip "&lt;repository&gt;-&lt;ref&gt;.zip", which is how the
     * script writes it; a release asset keeps its own file name.
     */
    private fun fileNameOf(url: String): String {
        val parts = url.removeSuffix("/").split("/")
        val last = parts.last()
        if (last.endsWith(".zip")) return last
        return parts[parts.size - 3] + "-" + last + ".zip"
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        val TRADITIONAL_IDS = setOf("rime-bopomofo", "rime-cangjie", "rime-quick", "rime-cantonese")
        const val DIR_ARGUMENT = "schemeDir"
        const val DEPLOY_TIMEOUT_MS = 15L * 60 * 1000
        const val POLL_MS = 250L
    }
}
