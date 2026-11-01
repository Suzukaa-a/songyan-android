package app.songyan.ime.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The snapshot/model contract: paging flags, schema names and default (fail-closed) backend members. */
class EngineSnapshotTest {
    @Test
    fun pagingFlagsFollowPageSizeAndIndex() {
        val single = EngineSnapshot()
        assertFalse(single.canPageBackward)
        assertFalse("no paging means no forward page either", single.canPageForward)

        val middle = EngineSnapshot(pageIndex = 1, pageSize = 5, isLastPage = false)
        assertTrue(middle.canPageBackward)
        assertTrue(middle.canPageForward)

        val last = EngineSnapshot(pageIndex = 2, pageSize = 5, isLastPage = true)
        assertTrue(last.canPageBackward)
        assertFalse(last.canPageForward)
    }

    @Test
    fun schemaDescriptorOnlyUsesDeclaredHumanNames() {
        assertEquals("雾凇拼音", KeyboardSchemaDescriptor("rime_ice", "").displayName)
        assertEquals("内置全拼", KeyboardSchemaDescriptor("songyan_quanpin", "").displayName)
        assertNull(
            "an id is never presented as a human name",
            KeyboardSchemaDescriptor("custom_x", "").humanReadableName,
        )
        assertNull(KeyboardSchemaDescriptor("custom_x", "custom_x").humanReadableName)
        assertEquals("我的方案", KeyboardSchemaDescriptor("custom_x", " 我的方案 ").humanReadableName)
        assertEquals("我的方案", KeyboardSchemaDescriptor("custom_x", "我的方案").displayName)
        assertEquals("custom_x", KeyboardSchemaDescriptor("custom_x", "").displayName)
    }

    @Test
    fun defaultBackendMembersFailClosed() {
        val engine = DemoEngine()
        assertFalse("read-only browsing is a real capability", engine.supportsReadOnlyBrowsing)
        assertFalse("learning suppression must be proven", engine.supportsLearningSuppression)
        assertNull(engine.spellingDemand?.invoke())
    }

    @Test
    fun defaultCancelFallsBackToEsc() =
        runTest {
            val engine = DemoEngine()
            engine.setup("/tmp/shared", "/tmp/user")
            engine.processKey("n")
            assertTrue(engine.currentSnapshot().isComposing)
            val cancelled = engine.cancelComposition()
            assertFalse(cancelled.isComposing)
            assertEquals("", cancelled.committed)
        }

    private fun runTest(body: suspend () -> Unit) = kotlinx.coroutines.test.runTest { body() }
}

/** Semantics of the ambiguity apply outcome (failure is never folded into success). */
class AmbiguityOutcomeTest {
    @Test
    fun onlyAppliedIsSuccess() {
        assertTrue(AmbiguityApplyOutcome.APPLIED.isSuccess)
        assertFalse(AmbiguityApplyOutcome.STALE.isSuccess)
        assertFalse(AmbiguityApplyOutcome.UNSUPPORTED.isSuccess)
        assertFalse(AmbiguityApplyOutcome.INVALID.isSuccess)
        assertFalse(AmbiguityApplyOutcome.FAILED.isSuccess)
        assertFalse(AmbiguityApplyOutcome.FAILED_ROLLBACK_UNVERIFIED.isSuccess)
    }

    @Test
    fun reportAmbiguityNeedsTwoLockableOptions() {
        val report =
            AmbiguitySpellingsReport(
                revisionInput = "gang",
                segment = 0..3,
                options = listOf("gang", "hang"),
                allSpellings = listOf("gang", "hang", "ghng"),
                lockMode = AmbiguityLockMode.TOKEN_ALIAS,
                isComplete = true,
            )
        assertTrue(report.isAmbiguous)
        assertTrue(report.isLockable)

        assertFalse(report.copy(options = listOf("gang")).isAmbiguous)
        assertFalse(report.copy(lockMode = AmbiguityLockMode.NONE).isLockable)
    }
}
