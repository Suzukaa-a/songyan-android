package app.songyan.ime.learning

import app.songyan.ime.content.LearningBackupArchive
import app.songyan.ime.engine.EngineOwnership
import app.songyan.ime.engine.QuarantineRecord
import app.songyan.ime.rime.RimeSnapshotFile
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.Instant

/**
 * The file-level guarantees of a learning merge, with a fake engine that stores each "userdb" as a
 * directory holding one line per entry. The real engine is covered by the :app device test.
 */
class LearningBackupServiceTest {
    /** Stands in for librime: `<name>.userdb/entries` holds one learned entry per line. */
    private class FakeEngine : LearningEngine {
        var failMergeAfterFirstWrite = false
        var failExport = false

        /** Runs inside [export] with the directory the engine was handed; tests look at the marker there. */
        var onExport: (File) -> Unit = {}
        val declared = mutableSetOf("songyan_shared")

        override suspend fun export(
            generationDir: File,
            userDataDir: File,
        ): LearningEngine.Export {
            onExport(userDataDir)
            if (failExport) throw LearningEngineException(LearningEngineFailure.FAILED)
            val snapshots =
                userDataDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.endsWith(".userdb") }
                    .sortedBy { it.name }
                    .map { LearningSnapshot(it.name.removeSuffix(".userdb"), File(it, "entries").readBytes()) }
            return LearningEngine.Export("1.17.0", snapshots)
        }

        override suspend fun merge(
            generationDir: File,
            userDataDir: File,
            snapshots: List<LearningSnapshot>,
        ) {
            if (snapshots.any { it.name !in declared }) {
                throw LearningEngineException(
                    LearningEngineFailure.OTHER_SOURCE,
                )
            }
            for (snapshot in snapshots) {
                val db = File(userDataDir, snapshot.name + ".userdb").also { it.mkdirs() }
                val entries = File(db, "entries")
                val merged =
                    (
                        entries
                            .takeIf {
                                it.isFile
                            }?.readLines()
                            .orEmpty() + String(snapshot.data).lines()
                    ).filter { it.isNotEmpty() }
                if (failMergeAfterFirstWrite) {
                    entries.writeText(merged.first() + "\n")
                    throw LearningEngineException(LearningEngineFailure.FAILED)
                }
                entries.writeText(merged.distinct().sorted().joinToString("\n", postfix = "\n"))
            }
        }

        /** Directories the engine listed the declared dictionaries in; each must be empty scratch. */
        val declaredReadIn = mutableListOf<List<String>>()

        override suspend fun declaredDictionaries(
            generationDir: File,
            userDataDir: File,
        ): Set<String> {
            declaredReadIn += userDataDir.list().orEmpty().toList()
            return declared.toSet()
        }

        /** librime's restore, reduced: the snapshot's entry rows join the dictionary its header names. */
        override suspend fun mergeSnapshotFiles(
            generationDir: File,
            userDataDir: File,
            snapshots: List<RimeSnapshotFile>,
        ) {
            if (snapshots.any { it.dictionary !in declared }) {
                throw LearningEngineException(LearningEngineFailure.OTHER_SOURCE)
            }
            merge(
                generationDir,
                userDataDir,
                snapshots.map { snapshot ->
                    val rows = snapshot.file.readLines().filterNot { it.startsWith("#") || it.isEmpty() }
                    LearningSnapshot(snapshot.dictionary, rows.joinToString("\n").toByteArray())
                },
            )
        }
    }

    private class Fixture(
        sourceId: String = SourceIds.BUILTIN,
        val engine: FakeEngine = FakeEngine(),
        beforeSwap: suspend () -> Unit = {},
    ) {
        val storage =
            AppStorage(
                Files.createTempDirectory("learning-files").toFile(),
            ).also { it.ensureBaseDirectories() }
        val ownership = EngineOwnership()
        val publisher = GenerationPublisher(storage)
        val service =
            LearningBackupService(
                storage = storage,
                publisher = publisher,
                ownership = ownership,
                engine = engine,
                sourceName = { "内置输入方案" },
                clock = { Instant.ofEpochSecond(1_767_225_600) },
                ioDispatcher = Dispatchers.Unconfined,
                beforeSwap = beforeSwap,
            )
        val live: File = LearningData.directory(storage, sourceId)

        init {
            val source = Files.createTempDirectory("source").toFile()
            File(source, "default.yaml").writeText("config_version: \"1\"\n")
            File(source, "build").mkdirs()
            File(source, "build/default.yaml").writeText("build_info:\n")
            publisher.publish(
                resourcesDir = source,
                compiledDir = File(source, "build"),
                sourceId = sourceId,
                schemaId = "songyan_quanpin",
                revision = "1",
            )
        }

        fun learn(vararg entries: String) {
            val db = File(live, "songyan_shared.userdb").also { it.mkdirs() }
            File(db, "entries").writeText(entries.joinToString("\n", postfix = "\n"))
        }

        fun entries(directory: File = live): List<String> =
            File(directory, "songyan_shared.userdb/entries").readLines().filter {
                it.isNotEmpty()
            }
    }

    private fun incoming(
        vararg entries: String,
        sourceId: String = "userDictionary",
        name: String = "songyan_shared",
    ) = LearningBackupArchive(
        sourceId = sourceId,
        resourceRevision = "ios-revision",
        sourceName = "iOS",
        engineVersion = "1.17.0",
        createdAt = Instant.ofEpochSecond(1_700_000_000),
        dictionaries = listOf(LearningBackupArchive.Dictionary(name, entries.joinToString("\n").toByteArray())),
    )

    private fun expectProblem(
        problem: LearningTransferProblem,
        block: suspend () -> Unit,
    ) = runBlocking {
        try {
            block()
            fail("expected $problem")
        } catch (e: LearningTransferException) {
            assertEquals(problem, e.problem)
        }
    }

    private fun leftovers(storage: AppStorage) =
        storage.userRoot
            .list()
            .orEmpty()
            .filter { it.startsWith(".merge-") }

    @Test
    fun backupCarriesTheIosSourceKeyAndEveryLearnedDictionary() =
        runBlocking {
            val fixture = Fixture()
            fixture.learn("ni hao\t你好")
            val archive = fixture.service.backup()
            assertEquals("userScheme:songyan-builtin", archive.sourceId)
            assertEquals("内置输入方案", archive.sourceName)
            assertEquals("ni hao\t你好\n", String(archive.dictionaries.single().data))
            assertEquals(archive, LearningBackupArchive.decode(archive.encoded()))
        }

    @Test
    fun nothingLearnedIsNotABackup() {
        val fixture = Fixture()
        expectProblem(LearningTransferProblem.NOTHING_TO_BACK_UP) { fixture.service.backup() }
    }

    @Test
    fun mergeKeepsExistingEntriesAddsTheBackupsAndSavesTheDataFromBefore() =
        runBlocking {
            val fixture = Fixture()
            fixture.learn("a", "b")
            fixture.service.merge(fixture.service.inspect(incoming("b", "c").encoded()))
            assertEquals(listOf("a", "b", "c"), fixture.entries())
            val safety = fixture.service.safetyBackup()
            assertNotNull(safety)
            assertEquals("a\nb\n", String(safety!!.dictionaries.single().data))
            assertTrue(leftovers(fixture.storage).isEmpty())
        }

    @Test
    fun mergeIntoNothingLearnedYetNeedsNoSafetyBackup() =
        runBlocking {
            val fixture = Fixture()
            fixture.service.merge(incoming("c"))
            assertEquals(listOf("c"), fixture.entries())
            assertNull(fixture.service.safetyBackup())
        }

    @Test
    fun aMergeThatFailsHalfwayLeavesTheLearningDataAsItWas() {
        val fixture = Fixture(engine = FakeEngine().apply { failMergeAfterFirstWrite = true })
        fixture.learn("a", "b")
        expectProblem(LearningTransferProblem.FAILED) { fixture.service.merge(incoming("c")) }
        assertEquals(listOf("a", "b"), fixture.entries())
        assertTrue(leftovers(fixture.storage).isEmpty())
    }

    @Test
    fun aMergeInterruptedBeforeTheSwapLeavesTheLearningDataAsItWas() {
        val fixture = Fixture(beforeSwap = { throw IOException("process killed") })
        fixture.learn("a")
        expectProblem(LearningTransferProblem.FAILED) { fixture.service.merge(incoming("c")) }
        assertEquals(listOf("a"), fixture.entries())
        assertTrue(leftovers(fixture.storage).isEmpty())
    }

    @Test
    fun aCrashBetweenTheTwoRenamesIsUndoneOnTheNextStart() {
        val fixture = Fixture()
        fixture.learn("a")
        val namespace = LearningData.namespace(SourceIds.BUILTIN)
        val previous = LearningData.mergePreviousDir(fixture.storage, namespace)
        val work = LearningData.mergeWorkDir(fixture.storage, namespace)
        LearningBackupService.copyLearningDirectory(fixture.live, work)
        Files.move(fixture.live.toPath(), previous.toPath())

        LearningData.recoverInterruptedMerges(fixture.storage)

        assertEquals(listOf("a"), fixture.entries())
        assertTrue(leftovers(fixture.storage).isEmpty())
    }

    @Test
    fun aCrashAfterTheSwapKeepsTheMergedData() {
        val fixture = Fixture()
        fixture.learn("merged")
        val previous = LearningData.mergePreviousDir(fixture.storage, LearningData.PINYIN)
        File(previous, "songyan_shared.userdb").mkdirs()
        File(previous, "songyan_shared.userdb/entries").writeText("old\n")

        LearningData.recoverInterruptedMerges(fixture.storage)

        assertEquals(listOf("merged"), fixture.entries())
        assertFalse(previous.exists())
    }

    @Test
    fun aCorruptedArchiveIsRefusedAndChangesNothing() {
        val fixture = Fixture()
        fixture.learn("a")
        val bytes = incoming("c").encoded()
        val text = String(bytes).replace("\"producer\"", "\"producr\"")
        expectProblem(LearningTransferProblem.INVALID_ARCHIVE) { fixture.service.inspect(text.toByteArray()) }
        expectProblem(LearningTransferProblem.INVALID_ARCHIVE) { fixture.service.inspect(bytes.copyOf(bytes.size - 3)) }
        assertEquals(listOf("a"), fixture.entries())
        assertNull(runBlocking { fixture.service.safetyBackup() })
    }

    @Test
    fun anArchiveFromAnotherSchemeIsRefusedAndChangesNothing() {
        val fixture = Fixture()
        fixture.learn("a")
        expectProblem(LearningTransferProblem.OTHER_SOURCE) {
            fixture.service.inspect(incoming("c", sourceId = "userScheme:scheme-0123abcd").encoded())
        }
        // A source key that fits but a dictionary the input methods do not learn into: the engine refuses it.
        expectProblem(LearningTransferProblem.OTHER_SOURCE) { fixture.service.merge(incoming("c", name = "rime_ice")) }
        assertEquals(listOf("a"), fixture.entries())
        assertTrue(leftovers(fixture.storage).isEmpty())
    }

    @Test
    fun aFailedSafetyBackupStopsTheMergeBeforeAnyWrite() {
        val fixture = Fixture(engine = FakeEngine().apply { failExport = true })
        fixture.learn("a")
        expectProblem(LearningTransferProblem.SAFETY_BACKUP_FAILED) { fixture.service.merge(incoming("c")) }
        assertEquals(listOf("a"), fixture.entries())
        assertTrue(leftovers(fixture.storage).isEmpty())
    }

    @Test
    fun theKeyboardEngineIsSetAsideForTheWorkAndStartedAgainAfter() =
        runBlocking {
            val fixture = Fixture()
            fixture.learn("a")
            val events = mutableListOf<String>()
            fixture.ownership.register(
                object : EngineOwnership.Holder {
                    override suspend fun releaseForExclusiveUse() {
                        events += "released"
                    }

                    override fun exclusiveUseEnded() {
                        events += "resumed"
                    }
                },
            )
            fixture.service.backup()
            expectProblem(
                LearningTransferProblem.OTHER_SOURCE,
            ) { fixture.service.merge(incoming("c", name = "rime_ice")) }
            assertEquals(listOf("released", "resumed", "released", "resumed"), events)
        }

    /** Writes what the start guard leaves behind: `rime/quarantine/<id>/user-pinyin` plus its record. */
    private fun Fixture.quarantine(
        id: String = "1767225600000",
        namespace: String = LearningData.PINYIN,
        vararg entries: String,
    ): File {
        val set = File(storage.rimeRoot, "quarantine/$id").also { it.mkdirs() }
        val db = File(set, "user-$namespace/songyan_shared.userdb").also { it.mkdirs() }
        File(db, "entries").writeText(entries.joinToString("\n", postfix = "\n"))
        File(set, "quarantine.json").writeText(
            QuarantineRecord(
                timeEpochMs = id.substringBefore('-').toLong(),
                generation = "g",
                sourceId = if (namespace == LearningData.PINYIN) SourceIds.BUILTIN else namespace,
                movedLearningData = true,
                switchedToBuiltin = false,
            ).toJson(),
        )
        return set
    }

    @Test
    fun quarantinedSetsAreListedNewestFirstAndIgnoreWhatIsNotOne() =
        runBlocking {
            val fixture = Fixture()
            fixture.quarantine("1000", LearningData.PINYIN, "a")
            fixture.quarantine("2000", "scheme-ab12", "b")
            File(fixture.storage.rimeRoot, "quarantine/not-a-set").mkdirs()
            File(fixture.storage.rimeRoot, "quarantine/3000").mkdirs()
            File(fixture.storage.rimeRoot, "quarantine/4000").apply {
                mkdirs()
                File(this, "quarantine.json").writeText("{ torn")
            }
            val sets = fixture.service.quarantined()
            assertEquals(listOf("2000", "1000"), sets.map { it.id })
            assertEquals(listOf("scheme-ab12", LearningData.PINYIN), sets.map { it.namespace })
            assertTrue(sets.all { it.state == QuarantineState.HELD })
        }

    @Test
    fun restoreReadsACopyAndMergesOnlyTheExportedWordsIntoTheLiveData() =
        runBlocking {
            val fixture = Fixture()
            fixture.learn("live")
            val set = fixture.quarantine("1000", LearningData.PINYIN, "old", "older")
            var handed: File? = null
            fixture.engine.onExport = { directory ->
                // The merge's safety backup exports the live directory through the same engine.
                if (directory.name == "quarantine-work") {
                    handed = directory
                    assertTrue("the read is recorded before the engine opens it", File(set, "restore-attempt").exists())
                }
            }

            val archive = fixture.service.inspectQuarantined("1000")
            assertEquals("old\nolder\n", String(archive.dictionaries.single().data))
            assertNotEquals("the engine never opens the set-aside directory itself", set.resolve("user-pinyin"), handed)
            assertFalse("the read finished cleanly, so the marker is gone", File(set, "restore-attempt").exists())
            assertFalse(File(fixture.storage.rimeRoot, "quarantine-work").exists())
            assertEquals("nothing is live until the user confirms", listOf("live"), fixture.entries())

            fixture.service.merge(archive, restoredFrom = "1000")
            assertEquals(listOf("live", "old", "older"), fixture.entries())
            assertEquals(
                "the set-aside data is untouched",
                "old\nolder\n",
                File(set, "user-pinyin/songyan_shared.userdb/entries").readText(),
            )
            assertEquals(
                QuarantineState.RESTORED,
                fixture.service
                    .quarantined()
                    .single()
                    .state,
            )
            expectProblem(LearningTransferProblem.QUARANTINE_GONE) { fixture.service.inspectQuarantined("1000") }
        }

    @Test
    fun aSetThatKilledAnEngineReadBeforeIsNeverOpenedAgain() {
        val fixture = Fixture()
        val set = fixture.quarantine("1000", LearningData.PINYIN, "old")
        // A native crash during the read leaves exactly this behind.
        File(set, "restore-attempt").writeBytes(byteArrayOf(1))
        var opened = false
        fixture.engine.onExport = { opened = true }
        assertEquals(QuarantineState.UNREADABLE, runBlocking { fixture.service.quarantined() }.single().state)
        expectProblem(LearningTransferProblem.QUARANTINE_UNREADABLE) { fixture.service.inspectQuarantined("1000") }
        assertFalse(opened)
        assertTrue("kept", File(set, "user-pinyin/songyan_shared.userdb/entries").isFile)
    }

    @Test
    fun aSetTheEngineRefusesCleanlyIsReportedUnreadableAndKept() {
        val fixture = Fixture()
        val set = fixture.quarantine("1000", LearningData.PINYIN, "old")
        fixture.engine.failExport = true
        expectProblem(LearningTransferProblem.QUARANTINE_UNREADABLE) { fixture.service.inspectQuarantined("1000") }
        assertFalse(File(set, "restore-attempt").exists())
        assertTrue(File(set, "user-pinyin/songyan_shared.userdb/entries").isFile)
        assertFalse(File(fixture.storage.rimeRoot, "quarantine-work").exists())
    }

    @Test
    fun aSetOfAnotherInputSchemeWaitsUntilThatSchemeIsInUse() {
        val fixture = Fixture()
        fixture.quarantine("1000", "scheme-ab12", "old")
        var opened = false
        fixture.engine.onExport = { opened = true }
        expectProblem(LearningTransferProblem.QUARANTINE_NOT_IN_USE) { fixture.service.inspectQuarantined("1000") }
        assertFalse(opened)
    }

    @Test
    fun restoreWithoutAnyPublishedSchemeIsRefused() {
        val fixture = Fixture()
        fixture.quarantine("1000", LearningData.PINYIN, "old")
        fixture.storage.activeFile.delete()
        expectProblem(LearningTransferProblem.NO_SOURCE) { fixture.service.inspectQuarantined("1000") }
    }

    @Test
    fun aSetWithNoWordsTheSchemeTakesIsNotRestored() {
        val fixture = Fixture()
        fixture.quarantine("1000", LearningData.PINYIN, "old")
        // The engine exports nothing for dictionaries the scheme in use does not declare.
        val noWords = LearningEngine.Export("1.17.0", emptyList())
        val service =
            LearningBackupService(
                storage = fixture.storage,
                publisher = fixture.publisher,
                ownership = fixture.ownership,
                engine =
                    object : LearningEngine {
                        override suspend fun export(
                            generationDir: File,
                            userDataDir: File,
                        ) = noWords

                        override suspend fun merge(
                            generationDir: File,
                            userDataDir: File,
                            snapshots: List<LearningSnapshot>,
                        ) = Unit

                        override suspend fun declaredDictionaries(
                            generationDir: File,
                            userDataDir: File,
                        ): Set<String> = emptySet()

                        override suspend fun mergeSnapshotFiles(
                            generationDir: File,
                            userDataDir: File,
                            snapshots: List<RimeSnapshotFile>,
                        ) = Unit
                    },
                sourceName = { "x" },
                ioDispatcher = Dispatchers.Unconfined,
            )
        expectProblem(LearningTransferProblem.QUARANTINE_EMPTY) { service.inspectQuarantined("1000") }
    }

    @Test
    fun deleteRemovesTheLearningDataKeepsTheRecordAndRejectsBadIds() =
        runBlocking {
            val fixture = Fixture()
            val set = fixture.quarantine("1000", LearningData.PINYIN, "old")
            fixture.quarantine("2000", LearningData.PINYIN, "keep")
            assertFalse(fixture.service.deleteQuarantined("../2000"))
            assertFalse(fixture.service.deleteQuarantined("9999"))
            assertEquals(2, fixture.service.quarantined().size)

            assertTrue(fixture.service.deleteQuarantined("1000"))
            assertEquals(listOf("2000"), fixture.service.quarantined().map { it.id })
            assertFalse(File(set, "user-pinyin").exists())
            assertEquals(
                "the record still tells the About page what happened",
                false,
                QuarantineRecord.fromJson(File(set, "quarantine.json").readText())!!.movedLearningData,
            )
            assertTrue(File(fixture.storage.rimeRoot, "quarantine/2000/user-pinyin").isDirectory)
        }

    private fun rimeSnapshot(
        name: String,
        vararg rows: String,
    ) = PickedFile("$name.userdb.txt") {
        (
            "# Rime user dictionary\n#@/db_name\t$name\n#@/rime_version\t1.11.2\n#@/user_id\tweasel\n" +
                rows.joinToString("") { "$it\tc=9\n" }
        ).byteInputStream()
    }

    private fun stagingLeftovers(storage: AppStorage) =
        listOf("snapshot-import", "declared-scratch").filter { !File(storage.rimeRoot, it).list().isNullOrEmpty() }

    @Test
    fun rimeSnapshotsMergeIntoTheirDictionaryAndTheRestAreSkippedWithAReason() =
        runBlocking {
            val fixture = Fixture()
            fixture.learn("a \t阿")
            val plan =
                fixture.service.inspectRimeSnapshots(
                    listOf(
                        rimeSnapshot("songyan_shared", "b \t不", "c \t从"),
                        rimeSnapshot("rime_ice", "d \t的"),
                        PickedFile("notes.txt") { "hello".byteInputStream() },
                    ),
                    byContent = true,
                )
            assertEquals(listOf("songyan_shared.userdb.txt"), plan.merges.map { it.fileName })
            assertEquals(1, plan.dictionaryCount)
            assertEquals(2L, plan.entryCount)
            assertEquals(
                listOf(
                    "notes.txt" to SnapshotSkipReason.NOT_A_SNAPSHOT,
                    "rime_ice.userdb.txt" to SnapshotSkipReason.NOT_INSTALLED,
                ),
                plan.skipped.map { it.fileName to it.reason },
            )
            // The declared dictionaries are read over an empty directory, never the live one.
            assertEquals(listOf(emptyList<String>()), fixture.engine.declaredReadIn)

            val report = fixture.service.mergeRimeSnapshots(plan.merges)

            assertEquals(1, report.dictionaryCount)
            assertEquals(2L, report.entryCount)
            assertEquals(listOf("a \t阿", "b \t不\tc=9", "c \t从\tc=9"), fixture.entries())
            assertEquals(
                "a \t阿\n",
                String(
                    fixture.service
                        .safetyBackup()!!
                        .dictionaries
                        .single()
                        .data,
                ),
            )
            assertTrue(leftovers(fixture.storage).isEmpty())
            fixture.service.discardRimeSnapshots(plan)
            assertEquals(emptyList<String>(), stagingLeftovers(fixture.storage))
        }

    @Test
    fun snapshotsNoInputMethodLearnsIntoChangeNothingAndSaveNothing() =
        runBlocking {
            val fixture = Fixture()
            fixture.learn("a \t阿")
            val plan =
                fixture.service.inspectRimeSnapshots(listOf(rimeSnapshot("rime_ice", "d \t的")), byContent = true)
            assertTrue(plan.merges.isEmpty())
            assertEquals(SnapshotSkipReason.NOT_INSTALLED, plan.skipped.single().reason)

            // A caller that merges them anyway (the scheme changed meanwhile, or a package import)
            // gets them back as skipped, with nothing saved or written.
            val staged =
                RimeSnapshotCollector.collect(
                    listOf(rimeSnapshot("rime_ice", "d \t的")),
                    File(fixture.storage.rimeRoot, "t"),
                    true,
                )
            val report = fixture.service.mergeRimeSnapshots(staged.staged)
            assertTrue(report.merged.isEmpty())
            assertEquals(SnapshotSkipReason.NOT_INSTALLED, report.skipped.single().reason)
            assertEquals(listOf("a \t阿"), fixture.entries())
            assertNull(fixture.service.safetyBackup())
            assertTrue(leftovers(fixture.storage).isEmpty())
        }

    @Test
    fun aSnapshotMergeInterruptedBeforeTheSwapLeavesTheLearningData() =
        runBlocking {
            var interrupt = true
            val fixture = Fixture(beforeSwap = { if (interrupt) throw IOException("killed") })
            fixture.learn("a \t阿")
            val plan = fixture.service.inspectRimeSnapshots(listOf(rimeSnapshot("songyan_shared", "b \t不")), true)
            expectProblem(LearningTransferProblem.FAILED) { fixture.service.mergeRimeSnapshots(plan.merges) }
            assertEquals(listOf("a \t阿"), fixture.entries())
            assertTrue(leftovers(fixture.storage).isEmpty())
            interrupt = false
            fixture.service.mergeRimeSnapshots(plan.merges)
            assertEquals(listOf("a \t阿", "b \t不\tc=9"), fixture.entries())
        }

    @Test
    fun tooManyPickedSnapshotsAreRefusedBeforeAnyEngineWork() {
        val fixture = Fixture()
        val many = (0..RimeSnapshotCollector.MAXIMUM_FILES).map { rimeSnapshot("d$it", "a \t阿") }
        expectProblem(LearningTransferProblem.TOO_LARGE) { fixture.service.inspectRimeSnapshots(many, true) }
        assertTrue(fixture.engine.declaredReadIn.isEmpty())
        assertEquals(emptyList<String>(), stagingLeftovers(fixture.storage))
    }
}
