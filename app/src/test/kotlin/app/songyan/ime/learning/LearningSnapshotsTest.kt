package app.songyan.ime.learning

import app.songyan.ime.engine.EngineOwnership
import app.songyan.ime.rime.RimeSnapshotFile
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.Instant

/** Rotation, retention and restore of the automatic learning snapshots, with a fake engine. */
class LearningSnapshotsTest {
    private class FakeEngine : LearningEngine {
        var exports = 0

        override suspend fun export(
            generationDir: File,
            userDataDir: File,
        ): LearningEngine.Export {
            exports++
            val snapshots =
                userDataDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.endsWith(".userdb") }
                    .map { LearningSnapshot(it.name.removeSuffix(".userdb"), File(it, "entries").readBytes()) }
            return LearningEngine.Export("1.17.0", snapshots)
        }

        override suspend fun merge(
            generationDir: File,
            userDataDir: File,
            snapshots: List<LearningSnapshot>,
        ) {
            for (snapshot in snapshots) {
                val db = File(userDataDir, snapshot.name + ".userdb").also { it.mkdirs() }
                File(db, "entries").writeBytes(snapshot.data)
            }
        }

        override suspend fun declaredDictionaries(
            generationDir: File,
            userDataDir: File,
        ): Set<String> = emptySet()

        override suspend fun mergeSnapshotFiles(
            generationDir: File,
            userDataDir: File,
            snapshots: List<RimeSnapshotFile>,
        ) = Unit
    }

    private class Fixture {
        val engine = FakeEngine()
        var now = 1_767_225_600L
        val storage = AppStorage(Files.createTempDirectory("snap").toFile()).also { it.ensureBaseDirectories() }
        private val publisher = GenerationPublisher(storage)
        val service =
            LearningBackupService(
                storage = storage,
                publisher = publisher,
                ownership = EngineOwnership(),
                engine = engine,
                sourceName = { "内置输入方案" },
                clock = { Instant.ofEpochSecond(now) },
                ioDispatcher = Dispatchers.Unconfined,
            )
        val snapshots =
            LearningSnapshots(
                storage,
                service,
                clock = { Instant.ofEpochSecond(now) },
                ioDispatcher = Dispatchers.Unconfined,
            )
        val live: File = LearningData.directory(storage, SourceIds.BUILTIN)

        init {
            val source = Files.createTempDirectory("source").toFile()
            File(source, "default.yaml").writeText("config_version: \"1\"\n")
            File(source, "build").mkdirs()
            File(source, "build/default.yaml").writeText("build_info:\n")
            publisher.publish(source, File(source, "build"), SourceIds.BUILTIN, "songyan_quanpin", "1")
        }

        /** Learns [entries] and stamps every file with [atSeconds], as the engine's writes would. */
        fun learn(
            atSeconds: Long,
            vararg entries: String,
        ) {
            val db = File(live, "songyan_shared.userdb").also { it.mkdirs() }
            val file = File(db, "entries")
            file.writeText(entries.joinToString("\n", postfix = "\n"))
            for (f in listOf(file, db, live)) f.setLastModified(atSeconds * 1000)
        }

        fun stored(): List<String> =
            storage.learningSnapshotsDir
                .list()
                .orEmpty()
                .sorted()
    }

    @Test
    fun nothingLearnedTakesNoSnapshotAndOpensNoEngine() =
        runBlocking {
            val f = Fixture()
            assertFalse(f.snapshots.snapshotIfDue())
            assertEquals(0, f.engine.exports)
            assertTrue(f.stored().isEmpty())
        }

    @Test
    fun atMostOneSnapshotADayAndOnlyWhenTheDataChanged() =
        runBlocking {
            val f = Fixture()
            f.learn(f.now, "你好\t1")
            assertTrue(f.snapshots.snapshotIfDue())
            assertEquals(1, f.stored().size)

            f.now += 3600
            f.learn(f.now, "你好\t2")
            assertFalse("not due within a day", f.snapshots.snapshotIfDue())

            f.now += 25 * 3600
            assertTrue("changed and due", f.snapshots.snapshotIfDue())
            assertEquals(2, f.stored().size)

            f.now += 25 * 3600
            val before = f.engine.exports
            assertFalse("due but unchanged since the last snapshot", f.snapshots.snapshotIfDue())
            assertEquals(before, f.engine.exports)
        }

    @Test
    fun theUserPicksTheIntervalAndOffTakesNone() =
        runBlocking {
            val f = Fixture()
            f.learn(f.now, "你好\t1")
            assertFalse("off takes none", f.snapshots.snapshotIfDue(days = 0))
            assertTrue(f.snapshots.snapshotIfDue(days = 7))

            f.now += 3 * 24 * 3600
            f.learn(f.now, "你好\t2")
            assertFalse("a week has not passed", f.snapshots.snapshotIfDue(days = 7))
            assertTrue("every 3 days is due", f.snapshots.snapshotIfDue(days = 3))
            assertEquals(1, LearningSnapshotInterval.resolve(5))
            assertEquals(7, LearningSnapshotInterval.resolve(7))
        }

    @Test
    fun onlyTheNewestThreeSnapshotsStayAndTemporaryFilesAreCleared() =
        runBlocking {
            val f = Fixture()
            f.storage.learningSnapshotsDir.mkdirs()
            File(f.storage.learningSnapshotsDir, "snapshot-1.json.tmp").writeText("half")
            val taken = mutableListOf<Long>()
            repeat(5) { day ->
                f.now += 25 * 3600
                f.learn(f.now, "entry\t$day")
                assertTrue(f.snapshots.snapshotIfDue())
                taken += f.now
            }
            assertEquals(taken.takeLast(3).map { "snapshot-$it.json" }.sorted(), f.stored())
            assertEquals(3, f.snapshots.list().size)
            assertEquals(
                taken.last(),
                f.snapshots
                    .list()
                    .first()
                    .archive.createdAt.epochSecond,
            )
        }

    @Test
    fun aSnapshotRestoresTheLearningDataAfterItWasLost() =
        runBlocking {
            val f = Fixture()
            f.learn(f.now, "你好\t7", "世界\t3")
            assertTrue(f.snapshots.snapshotIfDue())
            f.live.deleteRecursively()

            val entry = f.snapshots.list().single()
            val bytes = f.snapshots.read(entry.id)!!
            val archive = f.service.inspect(bytes)
            f.service.merge(archive)

            assertEquals(
                listOf("你好\t7", "世界\t3"),
                File(f.live, "songyan_shared.userdb/entries").readLines().filter { it.isNotEmpty() },
            )
        }

    @Test
    fun onlySnapshotNamesCanBeRead() =
        runBlocking {
            val f = Fixture()
            assertNull(f.snapshots.read("../active.json"))
            assertNull(f.snapshots.read("snapshot-1.json"))
        }
}
