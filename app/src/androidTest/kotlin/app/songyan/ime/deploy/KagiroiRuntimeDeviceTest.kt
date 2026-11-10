package app.songyan.ime.deploy

import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.SongyanApplication
import app.songyan.ime.content.SettingsInForeground
import app.songyan.ime.prefs.KeyboardPreferences
import app.songyan.ime.settings.state.StoredSourceResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

/**
 * Prepares the installed debug build for a real input-method latency run on kagiroi, and puts the
 * bundled pinyin input back afterwards.
 *
 * Opt-in because the fixture is the prebuilt deployment the mode B probe left in the app cache, a
 * few hundred megabytes, and publishing it copies that into the app's own storage. Neither method
 * starts an engine or taps a key: they publish data and set preferences only, so the measured run
 * afterwards goes through the input method's own first start, which is the path being measured.
 */
@RunWith(AndroidJUnit4::class)
class KagiroiRuntimeDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val app = context.applicationContext as SongyanApplication
    private val storage: AppStorage = app.storage
    private val publisher = GenerationPublisher(storage)
    private val preferences: KeyboardPreferences = app.keyboardPreferences
    private val settings = SettingsInForeground()

    @Before
    fun asked() {
        assumeTrue(
            "pass -e $ARG_OPT_IN true to prepare the input-method data",
            argument(ARG_OPT_IN) == "true",
        )
    }

    /**
     * Publishes the prebuilt kagiroi deployment as the active generation. The input method's next
     * start loads those compiled tables as they are, so nothing is compiled or downloaded on the
     * device for the measured run.
     */
    @Test
    fun prepareKagiroi() =
        runBlocking {
            val fixture = File(context.cacheDir, FIXTURE)
            val resources = File(fixture, "shared")
            val compiled = File(fixture, "work/build")
            assertTrue("run the kagiroi deploy probe in mode B first: missing $resources", resources.isDirectory)
            assertTrue("prebuilt compiled tables missing: $compiled", compiled.isDirectory)
            val compiledBytes = compiled.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            assertTrue("prebuilt compiled tables are empty: $compiled", compiledBytes > 0L)
            // A deployment in the other process publishes under the same lock.
            val record =
                withDeployLock {
                    publisher.publish(
                        resourcesDir = resources,
                        compiledDir = compiled,
                        sourceId = SOURCE_ID,
                        schemaId = SCHEMA_ID,
                        revision = REVISION,
                        stamp = BuildStamps.current(context),
                    )
                }
            assertEquals(
                "the new generation did not become active",
                record.generation,
                publisher.readActive()?.generation,
            )
            assertTrue("the published kagiroi generation is incomplete", publisher.isComplete(record))
            assertTrue(
                "its build stamp is stale, so the input method would rebuild it",
                BuildStamps.isCurrent(record, BuildStamps.current(context)),
            )
            // The input method must accept the generation as current, or opening the keyboard would
            // start a deployment instead of loading it.
            assertTrue("the input method would not reuse the published generation", DeploymentStarter.isReady(context))
            // The prebuilt tables only carry the QWERTY layout the romaji speller is written for.
            preferences.setLayoutId(LAYOUT_ID)
            preferences.setActiveSchemaId(SCHEMA_ID)
            report("kagiroi", record, "compiledBytes=$compiledBytes layout=$LAYOUT_ID schema=$SCHEMA_ID")
        }

    /**
     * Restores the bundled input methods through the settings screen's own deploy request, then
     * leaves the keyboard on the layout and input method the baseline run uses.
     */
    @Test
    fun preparePinyin() =
        runBlocking {
            settings.open()
            try {
                // The request the source list sends: ACTIVATE_SOURCE(builtin), answered after the
                // deploy process published the generation. A missing generation is built then.
                val restored = StoredSourcesHolder.current.use(SourceIds.BUILTIN)
                assertTrue(
                    "restoring the bundled input methods failed: $restored",
                    restored is StoredSourceResult.Success,
                )
                val complete = awaitComplete(awaitActive { it.sourceId == SourceIds.BUILTIN })
                assertTrue("the bundled generation is incomplete", publisher.isComplete(complete))
                assertTrue(
                    "the bundled generation has a stale build stamp",
                    BuildStamps.isCurrent(complete, BuildStamps.current(context)),
                )
                preferences.setLayoutId(LAYOUT_ID)
                preferences.setActiveSchemaId(complete.schemaId)
                report("pinyin", complete, "layout=$LAYOUT_ID")
            } finally {
                settings.close()
            }
        }

    /** Publishes under the lock the deploy process takes, so no deployment can interleave. */
    private suspend fun <T> withDeployLock(block: suspend () -> T): T {
        storage.ensureBaseDirectories()
        return FileChannel
            .open(
                storage.deployLockFile.toPath(),
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
            ).use { channel ->
                channel.lock().use { block() }
            }
    }

    private suspend fun awaitActive(predicate: (PublicationRecord) -> Boolean): PublicationRecord {
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val active = publisher.usablePublication()
            if (active != null && predicate(active)) return active
            delay(POLL_MS)
        }
        throw AssertionError("no matching published generation within $TIMEOUT_MS ms")
    }

    /** Waits until the generation no longer lacks the compact layouts of the other input methods. */
    private suspend fun awaitComplete(record: PublicationRecord): PublicationRecord {
        var current = record
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
        while (current.companionsPending && SystemClock.elapsedRealtime() < deadline) {
            delay(POLL_MS)
            current = publisher.usablePublication() ?: current
        }
        assertFalse("layouts still pending after $TIMEOUT_MS ms", current.companionsPending)
        return current
    }

    private fun report(
        what: String,
        record: PublicationRecord,
        detail: String,
    ) {
        val line =
            "prepared $what source=" + record.sourceId +
                " schema=" + record.schemaId +
                " revision=" + record.revision +
                " generation=" + record.generation +
                " sequence=" + record.sequence +
                " " + detail
        Log.i(TAG, line)
        instrumentation.sendStatus(0, Bundle().apply { putString("stream", "$line\n") })
    }

    private companion object {
        const val ARG_OPT_IN = "kagiroiRuntime"
        const val SOURCE_ID = "scheme-kagiroi-perf"
        const val SCHEMA_ID = "kagiroi"
        const val LAYOUT_ID = "qwerty26"
        const val REVISION = "kagiroi-perf"
        const val FIXTURE = "kagiroi-probe/B"
        const val TAG = "KagiroiRuntime"
        const val TIMEOUT_MS = 300_000L
        const val POLL_MS = 100L

        fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)
    }
}
