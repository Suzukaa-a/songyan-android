package app.songyan.ime.settings.ui

import android.net.Uri
import app.songyan.ime.settings.state.LearningBackupGateway
import app.songyan.ime.settings.state.LearningBackupResult
import app.songyan.ime.settings.state.LearningBackupSummary
import app.songyan.ime.settings.state.LearningImportCheck
import app.songyan.ime.settings.state.LearningPrepared
import app.songyan.ime.settings.state.QuarantinedAvailability
import app.songyan.ime.settings.state.QuarantinedLearningSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LearningBackupViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeGateway : LearningBackupGateway {
        val calls = mutableListOf<String>()
        var prepared: LearningPrepared = LearningPrepared.Ready("songyan-learning-backup-2026-10-28.json")
        var merge: LearningBackupResult = LearningBackupResult.Success
        var safety: LearningBackupSummary? = null
        var sets =
            mutableListOf(
                QuarantinedLearningSummary("100", 100L, "内置输入方案", QuarantinedAvailability.READY),
                QuarantinedLearningSummary("90", 90L, "内置输入方案", QuarantinedAvailability.UNREADABLE),
            )
        var restoreCheck: LearningImportCheck = LearningImportCheck.Ready(LearningBackupSummary("内置输入方案", 100L))
        var delete: LearningBackupResult = LearningBackupResult.Success

        override val available = true

        override suspend fun currentSourceName() = "内置输入方案"

        override suspend fun safetyBackup() = safety

        override suspend fun prepareBackup() = prepared.also { calls += "prepare" }

        override suspend fun prepareSafetyBackup() = prepared.also { calls += "prepare safety" }

        override suspend fun writePrepared(uri: Uri): LearningBackupResult = LearningBackupResult.Success

        override fun discardPrepared() {
            calls += "discard prepared"
        }

        override suspend fun inspect(uri: Uri): LearningImportCheck = LearningImportCheck.Failure("")

        override suspend fun mergeInspected(): LearningBackupResult {
            calls += "merge"
            safety = LearningBackupSummary("内置输入方案", 1L)
            return merge
        }

        override fun discardInspected() {
            calls += "discard inspected"
        }

        /** What a picked file turns out to be; files of another Rime input method describe themselves. */
        var fileCheck: LearningImportCheck = LearningImportCheck.Failure("")

        override suspend fun inspectSnapshot(id: String): LearningImportCheck =
            fileCheck.also { calls += "inspect $id" }

        override suspend fun quarantined() = sets.toList()

        override suspend fun inspectQuarantined(id: String): LearningImportCheck {
            calls += "inspect quarantined $id"
            return restoreCheck
        }

        override suspend fun deleteQuarantined(id: String): LearningBackupResult {
            calls += "delete $id"
            if (delete == LearningBackupResult.Success) sets.removeAll { it.id == id }
            return delete
        }
    }

    @Test
    fun opensWithTheSchemeInUse() =
        runTest(dispatcher) {
            val viewModel = LearningBackupViewModel(FakeGateway())
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertFalse(state.loading)
            assertEquals("内置输入方案", state.sourceName)
            assertNull(state.safetyBackup)
        }

    @Test
    fun aPreparedBackupOpensTheSavePickerOnceAndBackingOutDiscardsIt() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = LearningBackupViewModel(gateway)
            viewModel.exportBackup()
            advanceUntilIdle()
            assertEquals("songyan-learning-backup-2026-10-28.json", viewModel.uiState.value.saveFileName)
            viewModel.onSavePickerShown()
            assertNull(viewModel.uiState.value.saveFileName)
            viewModel.onSaveTarget(null)
            assertEquals(listOf("prepare", "discard prepared"), gateway.calls)
        }

    @Test
    fun nothingToBackUpIsReportedWithoutAPicker() =
        runTest(dispatcher) {
            val gateway = FakeGateway().apply { prepared = LearningPrepared.Failure("没有学到的词") }
            val viewModel = LearningBackupViewModel(gateway)
            viewModel.exportBackup()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertNull(state.saveFileName)
            assertEquals(LearningNotice.Failure("没有学到的词"), state.notice)
            assertFalse(state.busy)
        }

    @Test
    fun aConfirmedMergeReportsTheResultAndShowsTheBackupFromBefore() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = LearningBackupViewModel(gateway)
            advanceUntilIdle()
            viewModel.confirmMerge()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(LearningNotice.Merged, state.notice)
            assertEquals(LearningBackupSummary("内置输入方案", 1L), state.safetyBackup)
            viewModel.consumeNotice()
            assertNull(viewModel.uiState.value.notice)
        }

    @Test
    fun aFailedMergeShowsItsMessageAndCancellingDiscardsTheFile() =
        runTest(dispatcher) {
            val gateway = FakeGateway().apply { merge = LearningBackupResult.Failure("合并没有完成") }
            val viewModel = LearningBackupViewModel(gateway)
            viewModel.confirmMerge()
            advanceUntilIdle()
            assertEquals(LearningNotice.Failure("合并没有完成"), viewModel.uiState.value.notice)
            viewModel.cancelMerge()
            assertEquals(listOf("merge", "discard inspected"), gateway.calls)
        }

    @Test
    fun setAsideLearningDataIsListedNewestFirstAsTheGatewayReturnsIt() =
        runTest(dispatcher) {
            val viewModel = LearningBackupViewModel(FakeGateway())
            advanceUntilIdle()
            assertEquals(
                listOf("100", "90"),
                viewModel.uiState.value.quarantined
                    .map { it.id },
            )
        }

    @Test
    fun restoreAsksForConfirmationAndOnlyThenMerges() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = LearningBackupViewModel(gateway)
            advanceUntilIdle()
            viewModel.onQuarantinedRestore("100")
            advanceUntilIdle()
            var state = viewModel.uiState.value
            assertEquals(LearningBackupSummary("内置输入方案", 100L), state.confirmMerge)
            assertTrue(state.confirmIsRestore)
            assertFalse("nothing is merged before the user confirms", "merge" in gateway.calls)

            viewModel.confirmMerge()
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertEquals(LearningNotice.Restored, state.notice)
            assertNull(state.confirmMerge)
            assertFalse(state.confirmIsRestore)
            assertEquals(listOf("inspect quarantined 100", "merge"), gateway.calls)
        }

    @Test
    fun backingOutOfARestoreKeepsEverythingAndMergesNothing() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = LearningBackupViewModel(gateway)
            viewModel.onQuarantinedRestore("100")
            advanceUntilIdle()
            viewModel.cancelMerge()
            val state = viewModel.uiState.value
            assertNull(state.confirmMerge)
            assertFalse(state.confirmIsRestore)
            assertEquals(2, state.quarantined.size)
            assertEquals(listOf("inspect quarantined 100", "discard inspected"), gateway.calls)
        }

    @Test
    fun aRestoreTheEngineRefusesShowsItsMessageWithoutAConfirmation() =
        runTest(dispatcher) {
            val gateway = FakeGateway().apply { restoreCheck = LearningImportCheck.Failure("无法安全读取") }
            val viewModel = LearningBackupViewModel(gateway)
            viewModel.onQuarantinedRestore("100")
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(LearningNotice.Failure("无法安全读取"), state.notice)
            assertNull(state.confirmMerge)
            assertFalse(state.busy)
        }

    @Test
    fun aFailedRestoreMergeIsNotReportedAsRestored() =
        runTest(dispatcher) {
            val gateway = FakeGateway().apply { merge = LearningBackupResult.Failure("合并没有完成") }
            val viewModel = LearningBackupViewModel(gateway)
            viewModel.onQuarantinedRestore("100")
            advanceUntilIdle()
            viewModel.confirmMerge()
            advanceUntilIdle()
            assertEquals(LearningNotice.Failure("合并没有完成"), viewModel.uiState.value.notice)
        }

    @Test
    fun deleteNeedsConfirmationAndRemovesOnlyThatSet() =
        runTest(dispatcher) {
            val gateway = FakeGateway()
            val viewModel = LearningBackupViewModel(gateway)
            advanceUntilIdle()
            viewModel.onQuarantinedDelete("90")
            assertEquals(
                "90",
                viewModel.uiState.value.confirmDelete
                    ?.id,
            )
            assertEquals(emptyList<String>(), gateway.calls)

            viewModel.cancelDelete()
            assertNull(viewModel.uiState.value.confirmDelete)
            assertEquals(2, gateway.sets.size)

            viewModel.onQuarantinedDelete("90")
            viewModel.confirmDelete()
            advanceUntilIdle()
            val state = viewModel.uiState.value
            assertEquals(LearningNotice.Deleted, state.notice)
            assertEquals(listOf("100"), state.quarantined.map { it.id })
            assertEquals(listOf("delete 90"), gateway.calls)
        }

    @Test
    fun aFailedDeleteKeepsTheSetListed() =
        runTest(dispatcher) {
            val gateway = FakeGateway().apply { delete = LearningBackupResult.Failure("没能删除") }
            val viewModel = LearningBackupViewModel(gateway)
            advanceUntilIdle()
            viewModel.onQuarantinedDelete("90")
            viewModel.confirmDelete()
            advanceUntilIdle()
            assertEquals(LearningNotice.Failure("没能删除"), viewModel.uiState.value.notice)
            assertEquals(2, viewModel.uiState.value.quarantined.size)
        }

    @Test
    fun dictionaryFilesShowWhatWillMergeAndMergeOnlyWhenConfirmed() =
        runTest(dispatcher) {
            val gateway =
                FakeGateway().apply {
                    fileCheck = LearningImportCheck.Files("2 份词库，共 10 条", canMerge = true)
                    merge = LearningBackupResult.Reported("已并入 2 份词库，共 10 条。")
                }
            val viewModel = LearningBackupViewModel(gateway)
            viewModel.onSnapshotPicked("files")
            advanceUntilIdle()
            var state = viewModel.uiState.value
            assertEquals("2 份词库，共 10 条", state.confirmFiles)
            assertTrue(state.filesCanMerge)
            assertNull(state.confirmMerge)
            assertFalse("merge" in gateway.calls)

            viewModel.confirmMerge()
            advanceUntilIdle()
            state = viewModel.uiState.value
            assertNull(state.confirmFiles)
            assertEquals(LearningNotice.Report("已并入 2 份词库，共 10 条。"), state.notice)
            assertEquals(listOf("inspect files", "merge"), gateway.calls)
        }

    @Test
    fun dictionaryFilesWithNothingToMergeAreOnlyAcknowledged() =
        runTest(dispatcher) {
            val gateway = FakeGateway().apply { fileCheck = LearningImportCheck.Files("没有能并入的词库", canMerge = false) }
            val viewModel = LearningBackupViewModel(gateway)
            viewModel.onSnapshotPicked("files")
            advanceUntilIdle()
            assertEquals("没有能并入的词库", viewModel.uiState.value.confirmFiles)
            assertFalse(viewModel.uiState.value.filesCanMerge)
            viewModel.cancelMerge()
            assertNull(viewModel.uiState.value.confirmFiles)
            assertEquals(listOf("inspect files", "discard inspected"), gateway.calls)
        }
}
