package app.songyan.ime.engine

import app.songyan.ime.engine.EngineStartGuard.Decision
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * A process that dies inside engine start leaves the marker behind; [EngineStartGuard] is created
 * anew for each simulated process, as after a real crash.
 */
class EngineStartGuardTest {
    private val storage =
        AppStorage(
            Files.createTempDirectory("start-guard").toFile(),
        ).also { it.ensureBaseDirectories() }
    private var now = 1_000L

    private var exit = EngineStartGuard.PreviousExit.UNKNOWN

    private fun guard() = EngineStartGuard(storage, clock = { now }, previousExit = { exit })

    private fun record(
        generation: String,
        sourceId: String,
    ) = PublicationRecord(
        version = 2,
        generation = generation,
        sourceId = sourceId,
        schemaId = "songyan_quanpin",
        revision = "1",
        createdAtEpochMs = 0,
    )

    private fun learningFile(sourceId: String): File =
        File(LearningData.directory(storage, sourceId), "songyan.userdb/data").also {
            it.parentFile.mkdirs()
            it.writeText("learned")
        }

    @Test
    fun aStartThatReturnsOrThrowsLeavesNothingBehind() {
        val scheme = record("2-scheme-a", "scheme-a")
        repeat(5) {
            assertEquals(Decision.Start, guard().beforeStart(scheme))
            guard().started()
        }
        repeat(5) {
            assertEquals(Decision.Start, guard().beforeStart(scheme))
            guard().finished()
        }
        assertTrue(guard().quarantines().isEmpty())
    }

    @Test
    fun oneProcessKillDuringStartIsNotACrashLoop() {
        val scheme = record("2-scheme-a", "scheme-a")
        val learned = learningFile("scheme-a")
        assertEquals(Decision.Start, guard().beforeStart(scheme))
        // Killed here. The next start finishes.
        assertEquals(Decision.Start, guard().beforeStart(scheme))
        guard().started()
        // Killed once more later: still counted from zero.
        assertEquals(Decision.Start, guard().beforeStart(scheme))
        assertEquals(Decision.Start, guard().beforeStart(scheme))
        guard().started()
        assertTrue(learned.isFile)
        assertTrue(guard().quarantines().isEmpty())
    }

    @Test
    fun anImportedSourceThatKeepsDyingMakesWayForTheBuiltInOneAndItsLearningIsMovedAside() {
        val scheme = record("2-scheme-a", "scheme-a")
        val learned = learningFile("scheme-a")
        assertEquals(Decision.Start, guard().beforeStart(scheme))
        assertEquals(Decision.Start, guard().beforeStart(scheme))

        assertEquals(Decision.SwitchToBuiltin, guard().beforeStart(scheme))
        assertFalse("the suspect learning data is no longer opened", learned.exists())
        val record = guard().quarantines().single()
        assertEquals("scheme-a", record.sourceId)
        assertTrue(record.movedLearningData)
        assertTrue(record.switchedToBuiltin)
        val kept = File(storage.rimeRoot, "quarantine").walkTopDown().single { it.name == "data" }
        assertEquals("moved aside, never deleted", "learned", kept.readText())

        // Until the built-in source is active the generation is never started again.
        assertEquals(Decision.SwitchToBuiltin, guard().beforeStart(scheme))
        assertEquals(1, guard().quarantines().size)

        // The built-in generation starts normally and clears the way.
        val builtin = record("3-builtin", SourceIds.BUILTIN)
        assertEquals(Decision.Start, guard().beforeStart(builtin))
        guard().started()
        assertEquals("a source the user picks again gets fresh attempts", Decision.Start, guard().beforeStart(scheme))
    }

    @Test
    fun theBuiltInSourceIsRetriedOnFreshLearningDataThenLeftAlone() {
        val builtin = record("3-builtin", SourceIds.BUILTIN)
        val learned = learningFile(SourceIds.BUILTIN)
        assertEquals(Decision.Start, guard().beforeStart(builtin))
        assertEquals(Decision.Start, guard().beforeStart(builtin))
        now = 2_000L
        assertEquals(Decision.Start, guard().beforeStart(builtin))
        assertFalse(learned.exists())
        assertFalse(guard().quarantines().single().switchedToBuiltin)

        // Still dying on fresh learning data: the generation itself is the problem.
        assertEquals(Decision.Start, guard().beforeStart(builtin))
        assertEquals(Decision.Blocked, guard().beforeStart(builtin))
        assertEquals(Decision.Blocked, guard().beforeStart(builtin))
        assertEquals(1, guard().quarantines().size)

        // A rebuilt generation (the keyboard's retry) starts again.
        assertEquals(Decision.Start, guard().beforeStart(record("4-builtin", SourceIds.BUILTIN)))
    }

    @Test
    fun aTornMarkerIsNoEvidence() {
        val scheme = record("2-scheme-a", "scheme-a")
        File(storage.rimeRoot, "engine-start.json").writeText("{\"generation\":")
        assertEquals(Decision.Start, guard().beforeStart(scheme))
        guard().started()
        assertTrue(guard().quarantines().isEmpty())
    }

    @Test
    fun deathsTheSystemReportsAsKillsNeverQuarantine() {
        // A vendor's background killer or a slow start under memory pressure, over and over.
        exit = EngineStartGuard.PreviousExit.NOT_A_CRASH
        val scheme = record("2-scheme-a", "scheme-a")
        val learned = learningFile("scheme-a")
        repeat(6) { assertEquals(Decision.Start, guard().beforeStart(scheme)) }
        assertTrue(learned.isFile)
        assertTrue(guard().quarantines().isEmpty())

        // Reported native crashes count as before.
        exit = EngineStartGuard.PreviousExit.CRASH
        assertEquals(Decision.Start, guard().beforeStart(scheme))
        assertEquals(Decision.SwitchToBuiltin, guard().beforeStart(scheme))
    }
}
