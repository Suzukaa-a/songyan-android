package app.songyan.ime.deploy

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.SongyanApplication
import app.songyan.ime.content.SettingsInForeground
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.EngineFeaturesHolder
import app.songyan.ime.settings.state.FeatureChangeResult
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.EngineFeatureStore
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * J1.4: installing kagiroi in production must publish once, survive a real input-option change,
 * and keep the app's data within the sharing budget while old generations are dropped.
 *
 * The package comes from a local, nico-free compile of the pinned kagiroi source on a machine that
 * can run librime; it is pushed into this app's cache as a ZIP (or a directory the helper zips).
 * Everything after that is the product's own path: the real `:deploy` process imports it as a
 * scheme source, publishes the generation, a real engine types `nihongo` on the published build,
 * the production settings gateway changes one engine option and the deployment republishes, and
 * the app's `rime/` tree is counted by device and inode against one compiled build, the denominator
 * J0.2 used, with the whole-product ratio reported beside it for diagnosis.
 *
 * The compiled tables weigh about 174 MB and are streamed throughout: the fixture is copied or
 * zipped in chunks, measured by length, and never read into the Java heap.
 *
 * Opt-in: the package reaches hundreds of megabytes and both publications copy it. The default
 * connected run leaves this class out (Root keeps it in the runner's notClass list); it runs as
 * `-e kagiroiInstall true -e kagiroiFixture <path>`. Optional: `-e kagiroiSchema <id>` when the
 * primary input method cannot take romaji, `-e kagiroiSchemas a,b` when the package has no
 * default.yaml. Once asked to run, a missing or unusable package fails; nothing is skipped.
 *
 * The app's own `rime/` tree, its input-method choice and its language are captured first and put
 * back afterwards, so the debug install ends exactly as it started.
 */
@RunWith(AndroidJUnit4::class)
class KagiroiInstallDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val app = context.applicationContext as SongyanApplication
    private val storage: AppStorage = app.storage
    private val requests = DeployRequestStore(storage)
    private val publisher = GenerationPublisher(storage)
    private val catalog = SourceCatalog(storage)
    private val featureStore = EngineFeatureStore(storage)
    private val settings = SettingsInForeground()
    private lateinit var fixture: KagiroiInstallFixture
    private lateinit var snapshot: KagiroiInstallSnapshot
    private lateinit var before: KagiroiInstallFixture.DeviceState

    @Before
    fun isolate() {
        fixture = KagiroiInstallFixture(context, storage)
        assumeTrue(
            "pass -e " + KagiroiInstallFixture.ARG_OPT_IN + " true to install the local kagiroi package",
            fixture.argument(KagiroiInstallFixture.ARG_OPT_IN) == "true",
        )
        // The install and the republish run through the real :deploy process, which always uses the
        // app's own storage. That is only acceptable on the debug install: the daily app, a release
        // build or another package name are never touched.
        assertTrue(
            "run this fixture against the debug build only; it would install into " + context.packageName,
            context.packageName.endsWith(".debug"),
        )
        // Fail before copying the app's data aside when the package was never pushed into the cache.
        fixture.requireFixture()
        snapshot = KagiroiInstallSnapshot(storage, File(fixture.cacheRoot(), KagiroiInstallFixture.SNAPSHOT))
        before = fixture.deviceState()
        snapshot.capture()
        settings.open()
    }

    @After
    fun restoreEverything() {
        if (!this::fixture.isInitialized) return
        val problems = StringBuilder()
        runCatching { settings.close() }.onFailure {
            problems.append("settings screen: ").append(it.message).append('\n')
        }
        if (this::snapshot.isInitialized) {
            runCatching { snapshot.restore() }.onFailure {
                problems.append("app data: ").append(it.message).append('\n')
            }
        }
        // The keyboard keeps its input method in preferences and the deploy mirrors it into rime/,
        // so both the stored tree and the device choice have to come back unchanged.
        val after = fixture.deviceState()
        if (this::before.isInitialized && after.active != before.active) {
            problems.append("the active generation was not restored\n")
        }
        if (this::before.isInitialized && after.keyboardSchema != before.keyboardSchema) {
            problems.append("the keyboard schema was not restored\n")
        }
        if (this::before.isInitialized && after.defaultIme != before.defaultIme) {
            problems.append("the default input method changed\n")
        }
        if (this::before.isInitialized && after.enabledImes != before.enabledImes) {
            problems.append("the enabled input methods changed\n")
        }
        if (this::before.isInitialized && after.locales != before.locales) {
            problems.append("the app language changed\n")
        }
        assertTrue(
            "the test could not put the app back into its previous state:\n" + problems,
            problems.isEmpty(),
        )
    }

    @Test
    fun aLocalKagiroiPackageInstallsPublishesAndRepublishesWhenAnOptionChanges() =
        runBlocking {
            val requestId = DeployContract.newRequestId()
            val staged = fixture.stage(storage.incomingDir(requestId))
            assertTrue(
                "the package has no root-level *.schema.yaml, so it is not a Rime source: " + staged.facts.oneLine(),
                staged.facts.schemaFiles.isNotEmpty(),
            )
            assertTrue(
                "the package ships neither *.dict.yaml nor prebuilt build/*.bin: nothing in it came from a " +
                    "local librime compile, and compiling kagiroi's source on the device is what J0.1 A " +
                    "found unusable: " + staged.facts.oneLine(),
                staged.facts.dictionaryFiles.isNotEmpty() || staged.facts.prebuiltFiles.isNotEmpty(),
            )

            val request =
                DeployRequest(
                    requestId,
                    DeployMode.IMPORT_SCHEME,
                    packageFiles = listOf(staged.name),
                    advertisedSchemas = advertisedSchemas(staged.facts),
                )
            val installStart = SystemClock.elapsedRealtime()
            ContextCompat.startForegroundService(context, DeployContract.intent(context, request))
            val status = awaitTerminal(requestId, installStart)
            val installMs = SystemClock.elapsedRealtime() - installStart
            if (status.state != RequestState.SUCCEEDED) {
                throw AssertionError(
                    "the real scheme import did not succeed: state=" + status.state +
                        " problem=" + status.problem +
                        " paths=" + status.paths +
                        " detail=" + status.detail +
                        " fixture[" + staged.facts.oneLine() + "]" +
                        " :deploy[" + (fixture.deployExitedSince(installStart) ?: "alive or no exit record") + "]" +
                        "; a package whose prebuilt build/ the importer does not stage is refused as a missing " +
                        "dictionary, and compiling the source instead exhausts the deploy process",
                )
            }
            val sourceId = requireNotNull(status.sourceId) { "a successful import named no source" }
            val manifest = requireNotNull(catalog.read(sourceId)) { "no manifest was stored for " + sourceId }
            assertEquals("the imported source is not a scheme package", SourceKind.SCHEME, manifest.kind)
            assertTrue("the package offers no input method: " + manifest.schemaIds, manifest.schemaIds.isNotEmpty())
            val installed = awaitPublished(sourceId, installStart)
            assertTrue("the published generation is incomplete", publisher.isComplete(installed))
            assertEquals(
                "the imported package is not the active generation",
                installed.generation,
                publisher.readActive()?.generation,
            )

            val schemaId = schemaOf(manifest, staged.facts)

            // The generation has to hold the package's own compiled tables: a stub or a placeholder
            // cannot pass this, and neither can a deployment that quietly compiled something else. The
            // input method's own table and prism must be there byte for byte; the other tables are
            // reported because a deployment may legitimately drop the prisms of input methods it did
            // not build.
            val generationBuild = File(storage.generationDir(installed.generation), BUILD)
            val prebuiltBins = staged.facts.prebuiltFiles.filter { it.first.endsWith(".bin") }
            assertTrue(
                "the package ships no prebuilt build/*.bin, so the deployment would compile the source on " +
                    "the device instead of installing the locally compiled tables: " + staged.facts.oneLine(),
                prebuiltBins.isNotEmpty(),
            )
            val present =
                prebuiltBins.filter { (name, size) ->
                    val compiled = File(generationBuild, name)
                    compiled.isFile && compiled.length() == size
                }
            val absent = prebuiltBins.map { it.first } - present.map { it.first }
            assertTrue(
                "the package's prebuilt tables for " + schemaId + " never reached the published build: " +
                    "missing " + absent + " of " + prebuiltBins.map { it.first } + ". " + staged.facts.oneLine(),
                present.isNotEmpty(),
            )
            val schemaBins = prebuiltBins.filter { (name, _) -> name.startsWith(schemaId + ".") }
            for ((name, _) in schemaBins) {
                assertTrue(
                    "the published build lacks " + name + ", the package's own compiled table of " + schemaId,
                    present.any { it.first == name },
                )
            }

            val typed = typeNihongo(installed, schemaId)
            assertTrue("no candidates for nihongo in " + schemaId, typed.candidates.isNotEmpty())
            assertTrue(
                "nihongo in " + schemaId + " gave " + typed.candidates.take(5) + ", none of the first three " +
                    "being " + JAPANESE,
                typed.candidates.take(3).contains(JAPANESE),
            )
            assertEquals("selecting " + JAPANESE + " committed something else", JAPANESE, typed.committed)

            // One real option change, through the settings gateway the user's screen uses: it stores the
            // choice and sends the production REDEPLOY_ACTIVE request, which republishes the package.
            val published = publisher.usablePublication() ?: installed
            val wasOn = featureStore.read().typoCorrection
            assertTrue(
                "the engine features gateway is unavailable in this build",
                EngineFeaturesHolder.current.available,
            )
            val changeStart = SystemClock.elapsedRealtime()
            val change = EngineFeaturesHolder.current.setTypoCorrection(!wasOn)
            val changeMs = SystemClock.elapsedRealtime() - changeStart
            if (change !is FeatureChangeResult.Success) {
                val reason = (change as? FeatureChangeResult.Failure)?.message ?: "no reason reported"
                throw AssertionError(
                    "changing one real input option did not republish: " + reason +
                        " :deploy[" + (fixture.deployExitedSince(changeStart) ?: "alive or no exit record") + "]",
                )
            }
            assertEquals(
                "the option change was not stored",
                !wasOn,
                featureStore.read().typoCorrection,
            )
            val republished =
                requireNotNull(publisher.usablePublication()) { "no usable generation after the option change" }
            assertEquals("the option change moved the keyboard off the package", sourceId, republished.sourceId)
            assertNotEquals(
                "the option change published the same generation again",
                published.generation,
                republished.generation,
            )
            assertNotEquals(
                "the option change kept the same revision, so the deployment did not run with it",
                published.revision,
                republished.revision,
            )
            assertEquals(
                "the republished generation is not active",
                republished.generation,
                publisher.readActive()?.generation,
            )
            assertTrue("the republished generation is incomplete", publisher.isComplete(republished))

            // Old generations: every directory under versions/ must belong to a complete publication,
            // and only the active generation plus its newest predecessor may be kept.
            val records = publisher.publishedRecords()
            val ours = records.filter { it.sourceId == sourceId }
            val generationDirs =
                storage.versionsDir
                    .listFiles()
                    .orEmpty()
                    .count { it.isDirectory && !it.name.startsWith(".") }
            assertEquals(
                "a generation directory without a complete publication was left behind: " +
                    records.map { it.sourceId + "/" + it.generation },
                records.size,
                generationDirs,
            )
            assertTrue(
                "more than the active generation and its newest predecessor are kept for " + sourceId + ": " +
                    ours.map { it.generation } + " of all " + records.map { it.sourceId },
                ours.size <= 2,
            )
            assertTrue("no published generation of " + sourceId + " is kept", ours.isNotEmpty())
            assertEquals(
                "the republished generation is not the newest of its source",
                republished.generation,
                ours.first().generation,
            )

            // J0.2's denominator stays the one compiled build, so the numbers stay comparable with its
            // 2.005x. The whole-product ratio is measured and reported beside it for diagnosis only.
            val disk = fixture.measure(storage.generationDir(republished.generation))
            assertTrue("the published build has no bytes", disk.buildBytes > 0L)

            val report =
                JSONObject()
                    .put("fixture", staged.facts.oneLine())
                    .put("sourceId", sourceId)
                    .put("schemaId", schemaId)
                    .put("prebuiltTables", prebuiltBins.size)
                    .put("prebuiltTablesPresent", present.size)
                    .put("prebuiltTablesAbsent", JSONArray(absent))
                    .put("installMs", installMs)
                    .put("optionChangeMs", changeMs)
                    .put("candidates", JSONArray(typed.candidates))
                    .put("committed", typed.committed)
                    .put("optionName", "typoCorrection")
                    .put("optionFrom", wasOn)
                    .put("optionTo", !wasOn)
                    .put("generationsPublished", records.size)
                    .put("generationsOfSource", ours.size)
                    .put("generationsOnDisk", generationDirs)
                    .put("diskLogicalBytes", disk.logicalBytes)
                    .put("diskUniqueBytes", disk.uniqueBytes)
                    .put("diskSharedBytes", disk.sharedBytes)
                    .put("diskUniqueInodes", disk.uniqueInodes)
                    .put("productBytes", disk.productBytes)
                    .put("buildBytes", disk.buildBytes)
                    .put("sourceTreeBytes", disk.sourceTreeBytes)
                    .put(
                        "compiledStoreFiles",
                        JSONArray(
                            File(storage.sourcesDir, ".compiled")
                                .walkTopDown()
                                .filter {
                                    it.isFile &&
                                        !java.nio.file.Files
                                            .isSymbolicLink(it.toPath())
                                }.map { it.relativeTo(storage.sourcesDir).path + ":" + it.length() }
                                .toList(),
                        ),
                    ).put("productRatio", disk.productRatio())
                    .put("buildRatio", disk.buildRatio())
                    .put("threshold", SHARING_THRESHOLD)
            val file = fixture.writeReport(report)
            Log.i(TAG, "kagiroi install: " + report)
            instrumentation.sendStatus(0, Bundle().apply { putString("stream", "kagiroi install " + report + "\n") })
            println("report " + file + " " + disk.oneLine())
            assertTrue(
                "the app keeps " + disk.uniqueBytes + " bytes of rime/ data for two publications of one " +
                    "package: " + disk.buildRatio() + "x one compiled build (" + disk.buildBytes + " bytes), " +
                    "over the " + SHARING_THRESHOLD + "x the large files must be shared to; the same data is " +
                    disk.productRatio() + "x one whole deployment product. " + disk.oneLine(),
                disk.buildRatio() <= SHARING_THRESHOLD,
            )
        }

    /** The input methods to advertise: the package's own default.yaml wins unless the caller overrides. */
    private fun advertisedSchemas(facts: KagiroiInstallFixture.Facts): List<String> {
        val given = fixture.argument(KagiroiInstallFixture.ARG_SCHEMAS)
        if (!given.isNullOrBlank()) {
            return given.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        }
        // A package without default.yaml would otherwise offer every root schema it ships, and its
        // generation would lack the default.yaml every published generation must hold.
        if (facts.defaultConfig) return emptyList()
        return facts.schemaFiles.map { it.removeSuffix(SCHEMA_SUFFIX) }
    }

    /** The input method to type the romaji probe with, defaulting to the kagiroi schemas J0 used. */
    private fun schemaOf(
        manifest: SourceManifest,
        facts: KagiroiInstallFixture.Facts,
    ): String {
        val wanted = fixture.argument(KagiroiInstallFixture.ARG_SCHEMA)?.trim()?.takeIf { it.isNotEmpty() }
        if (wanted != null) {
            assertTrue(
                "-e kagiroiSchema " + wanted + " is not an input method of the package: " + manifest.schemaIds,
                wanted in manifest.schemaIds,
            )
            return wanted
        }
        for (candidate in listOf(PRIMARY_SCHEMA, ROMAJI_SCHEMA)) {
            if (candidate in manifest.schemaIds) return candidate
        }
        throw AssertionError(
            "the package offers " + manifest.schemaIds + ", none of which the J1.4 romaji probe knows; " +
                "pass -e " + KagiroiInstallFixture.ARG_SCHEMA + " <id>. fixture[" + facts.oneLine() + "]",
        )
    }

    /** Types [NIHONGO] on the published generation and selects [JAPANESE] when the engine offers it. */
    private suspend fun typeNihongo(
        record: PublicationRecord,
        schemaId: String,
    ): Typed {
        val generation = storage.generationDir(record.generation)
        // Learning stays in the test's own cache, never in the app's user data.
        val userData = File(fixture.cacheRoot(), "engine-user").also { it.deleteRecursively() }
        userData.mkdirs()
        val engine =
            RimeEngine.createPrebuilt(
                sharedDataDir = File(generation, RESOURCES),
                userDataDir = userData,
                appName = RimeBindings.APP_NAME,
                appVersion = "test",
                compiledDir = File(generation, BUILD),
            )
        return try {
            assertTrue("the published generation has no " + schemaId, engine.selectSchema(schemaId))
            engine.clearComposition()
            for (char in NIHONGO) {
                engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
            }
            val candidates = engine.readSnapshot().candidates.map { it.text }
            val chosen = candidates.indexOf(JAPANESE).takeIf { it >= 0 }
            Typed(candidates, chosen?.let { engine.selectCandidate(it)?.commitText })
        } finally {
            engine.close()
        }
    }

    /** Waits for the request's own terminal status, and fails fast when the deploy process died. */
    private suspend fun awaitTerminal(
        requestId: String,
        startedAtMs: Long,
    ): RequestStatus {
        val deadline = SystemClock.elapsedRealtime() + INSTALL_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val status = withContext(Dispatchers.IO) { requests.read(requestId) }
            if (status != null && status.state.isTerminal) return status
            // The status file is written before the process ends, so read it once more before
            // blaming the exit: a killed :deploy writes nothing and would otherwise look running.
            val exit = fixture.deployExitedSince(startedAtMs)
            if (exit != null) {
                val late = withContext(Dispatchers.IO) { requests.read(requestId) }
                if (late != null && late.state.isTerminal) return late
                throw AssertionError("the deploy process died during the import: " + exit)
            }
            delay(POLL_MS)
        }
        throw AssertionError("the import request did not finish within " + INSTALL_TIMEOUT_MS / 60_000 + " minutes")
    }

    /** The published generation of [sourceId] once the real deploy process made it active. */
    private suspend fun awaitPublished(
        sourceId: String,
        startedAtMs: Long,
    ): PublicationRecord {
        val deadline = SystemClock.elapsedRealtime() + PUBLISH_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val active = publisher.usablePublication()
            if (active != null && active.sourceId == sourceId && publisher.isComplete(active)) return active
            val exit = fixture.deployExitedSince(startedAtMs)
            if (exit != null) {
                throw AssertionError("the deploy process died before publishing " + sourceId + ": " + exit)
            }
            delay(POLL_MS)
        }
        throw AssertionError("no complete generation of " + sourceId + " became active")
    }

    /** What the romaji probe saw: the candidates and what selecting [JAPANESE] committed. */
    private class Typed(
        val candidates: List<String>,
        val committed: String?,
    )

    private companion object {
        const val JAPANESE = "日本語"
        const val NIHONGO = "nihongo"
        const val PRIMARY_SCHEMA = "kagiroi"
        const val ROMAJI_SCHEMA = "kagiroi_romaji"
        const val RESOURCES = "resources"
        const val BUILD = "build"
        const val SCHEMA_SUFFIX = ".schema.yaml"

        /** J0.2 measured two publications at 2.005x one compiled build; J1.4 allows 1.3x. */
        const val SHARING_THRESHOLD = 1.3
        const val TAG = "KagiroiInstall"
        const val POLL_MS = 500L
        const val INSTALL_TIMEOUT_MS = 45L * 60 * 1000
        const val PUBLISH_TIMEOUT_MS = 5L * 60 * 1000
    }
}
