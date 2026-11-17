package app.songyan.ime.deploy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Work that kills the deploy process is not restarted forever: after a rime-stroke import the
 * remaining layouts took about 1.3 GB, the low-memory killer ended the process and opening the
 * keyboard started the same work again every half minute.
 */
class DeployAttemptsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun attempts() = DeployAttempts(File(temp.root, DeployAttempts.FILE_NAME))

    @Test
    fun workThatNeverEndsIsRefusedAfterTheLimit() {
        val key = requireNotNull(DeployAttempts.keyOf(DeployMode.COMPLETE_COMPANIONS, false, "g1"))
        // Each begin without an end is a process that died mid-run; a fresh instance is a new process.
        repeat(DeployAttempts.LIMIT) { assertTrue(attempts().begin(key)) }
        assertTrue(attempts().exhausted(key))
        assertFalse(attempts().begin(key))
        assertFalse("a refusal does not count as a run that ended", attempts().begin(key))
    }

    @Test
    fun aRunThatEndsStartsTheCountAgain() {
        val key = requireNotNull(DeployAttempts.keyOf(DeployMode.BUILTIN, false, null))
        repeat(DeployAttempts.LIMIT - 1) { assertTrue(attempts().begin(key)) }
        attempts().end(key)
        repeat(DeployAttempts.LIMIT) { assertTrue(attempts().begin(key)) }
        assertFalse(attempts().begin(key))
    }

    @Test
    fun aNewGenerationOrOtherWorkIsNotRefused() {
        val stuck = requireNotNull(DeployAttempts.keyOf(DeployMode.COMPLETE_COMPANIONS, false, "g1"))
        repeat(DeployAttempts.LIMIT) { attempts().begin(stuck) }
        val next = requireNotNull(DeployAttempts.keyOf(DeployMode.COMPLETE_COMPANIONS, false, "g2"))
        assertTrue(attempts().begin(next))
        assertFalse(attempts().exhausted(stuck))
    }

    @Test
    fun workTheUserAskedForIsNeverCounted() {
        for (mode in listOf(DeployMode.IMPORT_SCHEME, DeployMode.REDEPLOY_ACTIVE, DeployMode.ACTIVATE_SOURCE)) {
            assertNull(mode.name, DeployAttempts.keyOf(mode, false, "g1"))
        }
        assertEquals(
            DeployAttempts.keyOf(DeployMode.BUILTIN, true, "g1"),
            DeployAttempts.keyOf(DeployRequest("r", DeployMode.BUILTIN, force = true), "g1"),
        )
    }
}
