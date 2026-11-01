package app.songyan.ime.deploy

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.content.SettingsInForeground
import app.songyan.ime.settings.state.SchemeImportHolder
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationLease
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Times deployments through the real :deploy process: a rebuild of the bundled input methods, and
 * a large scheme package's first import (until the input method in use can type, and until every
 * layout is ready), its rebuild (what an app update does) and a re-import of the same file.
 *
 * A benchmark, not a check: the connected test tasks leave it out, and it only runs when asked,
 * `am instrument -w -e class app.songyan.ime.deploy.DeploySpeedBenchmark -e deployBenchmark true <test package>/androidx.test.runner.AndroidJUnitRunner`.
 * The package part needs a package, since none is bundled: `-e schemePackage <path the app can read>`.
 * The phases of each deployment are in the log under [DeployTimer.TAG].
 */
@RunWith(AndroidJUnit4::class)
class DeploySpeedBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val storage = AppStorage(context.filesDir)
    private val publisher = GenerationPublisher(storage)
    private val settings = SettingsInForeground()
    private var imported: String? = null

    @Before
    fun setUp() {
        assumeTrue("not asked to run", argument(BENCHMARK_ARGUMENT) == "true")
        settings.open()
    }

    @After
    fun tearDown() {
        // `-e keepPackage true` leaves the package in use, to time an app update over it afterwards.
        if (argument(KEEP_ARGUMENT) != "true") {
            imported?.let { id -> runCatching { runBlocking { StoredSourcesHolder.current.delete(id) } } }
        }
        settings.close()
    }

    /** The bundled input methods built again, as after an app update. */
    @Test
    fun theBuiltInInputMethods() =
        runBlocking {
            val before = requireNotNull(publisher.readActive())
            val start = SystemClock.elapsedRealtime()
            ContextCompat.startForegroundService(context, DeployContract.builtinIntent(context, force = true))
            awaitNewer(before)
            report("built-in rebuild", start)
        }

    @Test
    fun aLargeSchemePackage() =
        runBlocking {
            val path = argument(PACKAGE_ARGUMENT)
            assumeTrue("no $PACKAGE_ARGUMENT argument", path != null)
            val archive = File(requireNotNull(path))
            assertTrue("cannot read $archive", archive.canRead())

            forgetEarlierPackageBuilds()
            var start = SystemClock.elapsedRealtime()
            val first = import(archive)
            report("import usable", start)
            val complete = awaitComplete(first)
            report("import complete", start)
            imported = complete.sourceId

            start = SystemClock.elapsedRealtime()
            ContextCompat.startForegroundService(context, DeployContract.redeployActiveIntent(context))
            val rebuilt = awaitNewer(complete)
            report("rebuild usable", start)
            awaitComplete(rebuilt)
            report("rebuild complete", start)

            start = SystemClock.elapsedRealtime()
            val again = import(archive)
            report("re-import usable", start)
            awaitComplete(again)
            report("re-import complete", start)
        }

    /**
     * A first import has no earlier build of the package to start from; one left by an earlier run
     * (a deleted package keeps its last generation until newer ones replace it) is removed.
     */
    private fun forgetEarlierPackageBuilds() {
        val active = publisher.readActive()?.generation
        val leased = GenerationLease(storage).leased()
        publisher
            .publishedRecords()
            .filter { SourceIds.isScheme(it.sourceId) && it.generation != active && it.generation !in leased }
            .forEach { storage.generationDir(it.generation).deleteRecursively() }
    }

    private suspend fun import(archive: File): PublicationRecord {
        val result = SchemeImportHolder.current.importFromUri(Uri.fromFile(archive))
        assertTrue("scheme import failed: $result", result is SchemeImportResult.Success)
        return requireNotNull(publisher.readActive())
    }

    private suspend fun awaitNewer(previous: PublicationRecord): PublicationRecord {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val active = publisher.usablePublication()
            if (active != null && active.sequence > previous.sequence) return active
            delay(POLL_MS)
        }
        throw AssertionError("no new generation within $TIMEOUT_MS ms")
    }

    private suspend fun awaitComplete(record: PublicationRecord): PublicationRecord {
        var current = record
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (current.companionsPending && SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_MS)
            current = publisher.usablePublication() ?: current
        }
        assertTrue("layouts still pending after $TIMEOUT_MS ms", !current.companionsPending)
        return current
    }

    private fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    private fun report(
        what: String,
        start: Long,
    ) {
        val line = what + " " + (SystemClock.elapsedRealtime() - start) + " ms"
        Log.i(DeployTimer.TAG, "benchmark: $line")
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
    }

    private companion object {
        const val BENCHMARK_ARGUMENT = "deployBenchmark"
        const val PACKAGE_ARGUMENT = "schemePackage"
        const val KEEP_ARGUMENT = "keepPackage"
        const val TIMEOUT_MS = 300_000L
        const val POLL_MS = 100L
    }
}
