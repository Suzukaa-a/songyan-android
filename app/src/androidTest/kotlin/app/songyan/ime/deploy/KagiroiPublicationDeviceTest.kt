package app.songyan.ime.deploy

import android.os.Bundle
import android.system.Os
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Opt-in because it copies the kagiroi compiled data twice, about 400 MB, through the real
 * publication path.
 *
 * The deploy probe left a prebuilt kagiroi deployment in the app cache. Publishing it twice with a
 * new revision is what happens when the user changes an option and republishes without rebuilding:
 * each generation holds a full copy of the resources and compiled tables, so the disk cost doubles
 * unless the publisher shares files. Hard links would make several paths report one device and
 * inode, so the measurement counts each inode once.
 *
 * The sharing threshold is only reported: this phase measures the cost, a later change makes the
 * generations share files.
 */
@RunWith(AndroidJUnit4::class)
class KagiroiPublicationDeviceTest {
    @Before
    fun asked() {
        assumeTrue(
            "pass -e $ARG_OPT_IN true to measure publication disk usage",
            argument(ARG_OPT_IN) == "true",
        )
    }

    @Test
    fun twoPrebuiltPublicationsMeasureDiskAmplification() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixtureRoot = argument(ARG_FIXTURE) ?: File(context.cacheDir, DEFAULT_FIXTURE).path
        val fixture = File(fixtureRoot)
        val resources = File(fixture, "shared")
        val compiled = File(fixture, "work/build")
        assertTrue("fixture shared data missing: $resources", resources.isDirectory)
        assertTrue("fixture compiled data missing: $compiled", compiled.isDirectory)
        assertTrue("fixture compiled data is empty: $compiled", compiled.listFiles().orEmpty().isNotEmpty())

        // A cache root of its own keeps the measurement out of the product's files/rime.
        val root = File(context.cacheDir, ROOT)
        root.deleteRecursively()
        val storage = AppStorage(root).also { it.ensureBaseDirectories() }
        val publisher = GenerationPublisher(storage)
        val first = publisher.publish(resources, compiled, SOURCE_ID, SCHEMA_ID, "1")
        val second = publisher.publish(resources, compiled, SOURCE_ID, SCHEMA_ID, "2")

        assertTrue("the first publication is incomplete", publisher.isComplete(first))
        assertTrue("the second publication is incomplete", publisher.isComplete(second))
        assertEquals("the second publication is active", second.generation, publisher.readActive()?.generation)
        assertEquals("the active publication keeps its revision", "2", publisher.readActive()?.revision)

        val files =
            storage.rimeRoot
                .walkTopDown()
                .filter { it.isFile }
                .toList()
        val logicalBytes = files.sumOf { it.length() }
        // One inode may back several paths once publications share files; count it only once.
        val uniqueBytes =
            files
                .distinctBy { file ->
                    val stat = Os.stat(file.path)
                    stat.st_dev to stat.st_ino
                }.sumOf { it.length() }
        val compiledBytes = compiled.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        assertTrue("compiled data has no bytes", compiledBytes > 0L)
        assertEquals("both generations remain published", 2, publisher.publishedRecords().size)
        val ratio = uniqueBytes.toDouble() / compiledBytes
        val records = JSONArray()
        for (record in listOf(first, second)) records.put(recordJson(publisher, storage, record))
        val report =
            JSONObject()
                .put("sourceId", SOURCE_ID)
                .put("schemaId", SCHEMA_ID)
                .put("fixture", fixture.absolutePath)
                .put("logicalBytes", logicalBytes)
                .put("uniqueBytes", uniqueBytes)
                .put("compiledBytes", compiledBytes)
                .put("ratio", ratio)
                .put("needsSharing", ratio > SHARING_THRESHOLD)
                .put("publishedGenerations", publisher.publishedRecords().size)
                .put("records", records)
        File(root, REPORT_FILE).writeText(report.toString())
        Log.i(TAG, "kagiroi publication: $report")
        val stream = Bundle().apply { putString("stream", "kagiroi publication $report\n") }
        InstrumentationRegistry.getInstrumentation().sendStatus(0, stream)
    }

    private fun recordJson(
        publisher: GenerationPublisher,
        storage: AppStorage,
        record: PublicationRecord,
    ): JSONObject {
        val files =
            storage
                .generationDir(record.generation)
                .walkTopDown()
                .filter { it.isFile }
                .toList()
        return JSONObject()
            .put("generation", record.generation)
            .put("revision", record.revision)
            .put("sequence", record.sequence)
            .put("fileCount", files.size)
            .put("bytes", files.sumOf { it.length() })
            .put("complete", publisher.isComplete(record))
    }

    private companion object {
        const val ARG_OPT_IN = "kagiroiPublication"
        const val ARG_FIXTURE = "publicationFixtureRoot"
        const val SOURCE_ID = "scheme-kagiroi"
        const val SCHEMA_ID = "kagiroi"
        const val DEFAULT_FIXTURE = "kagiroi-probe/B"
        const val ROOT = "kagiroi-publication"
        const val REPORT_FILE = "report.json"
        const val SHARING_THRESHOLD = 1.3
        const val TAG = "KagiroiPublication"

        fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)
    }
}
