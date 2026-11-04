package app.songyan.ime.settings.ui

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModelStore
import app.songyan.ime.settings.state.RimeConfigApplyResult
import app.songyan.ime.settings.state.RimeConfigEditResult
import app.songyan.ime.settings.state.RimeConfigFileResult
import app.songyan.ime.settings.state.RimeConfigGateway
import app.songyan.ime.settings.state.RimeConfigInfo
import app.songyan.ime.settings.state.RimeConfigSaveResult
import app.songyan.ime.settings.state.RimeScriptCreateResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** The in-app editor: unsaved text, refused saves that keep it, and the way out to another app. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RimeFileEditorViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    private class FakeGateway : RimeConfigGateway {
        var load: RimeConfigFileResult =
            RimeConfigFileResult.Loaded(
                "内置全拼",
                "songyan_quanpin.custom.yaml",
                "patch: {}\n",
            )
        var save: RimeConfigSaveResult = RimeConfigSaveResult.Saved
        var external: RimeConfigEditResult = RimeConfigEditResult.Failure(null)
        val saved = mutableListOf<String>()
        var grammarExample = false
        var saveGate: CompletableDeferred<Unit>? = null

        override val available = true

        override fun openFolderIntents(): List<Intent> = emptyList()

        override suspend fun info() = RimeConfigInfo(fileCount = 0, pending = false)

        override suspend fun apply(confirmed: List<String>): RimeConfigApplyResult = RimeConfigApplyResult.Applied

        override suspend fun restore(path: String) = Unit

        override suspend fun acknowledge(paths: List<String>) = Unit

        override suspend fun loadFile(
            key: String,
            grammarExample: Boolean,
        ): RimeConfigFileResult {
            this.grammarExample = grammarExample
            return load
        }

        override suspend fun createScript(name: String): RimeScriptCreateResult = RimeScriptCreateResult.Failure(null)

        override suspend fun saveFile(
            key: String,
            text: String,
        ): RimeConfigSaveResult {
            saved += text
            saveGate?.await()
            return save
        }

        override suspend fun externalEditIntents(key: String) = external
    }

    private val gateway = FakeGateway()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model(grammar: Boolean = false) = RimeFileEditorViewModel(gateway, "songyan_quanpin", grammar)

    @Test
    fun theFileOpensCleanAndEditsMakeItDirty() =
        runTest(dispatcher) {
            val model = model(grammar = true)
            advanceUntilIdle()
            val opened = model.uiState.value
            assertFalse(opened.loading)
            assertEquals("内置全拼", opened.title)
            assertFalse(opened.dirty)
            assertTrue("the grammar entry asks for the example", gateway.grammarExample)

            model.edit("patch:\n  menu/page_size: 7\n")
            assertTrue(model.uiState.value.dirty)
            model.edit("patch: {}\n")
            assertFalse("back to what was opened", model.uiState.value.dirty)
        }

    @Test
    fun aRefusedSaveKeepsTheTextAndOriginalError() =
        runTest(dispatcher) {
            val message = "songyan_quanpin.custom.yaml: expected a closing bracket\nline 3, column 1"
            gateway.save = RimeConfigSaveResult.Failure(message)
            val model = model()
            advanceUntilIdle()
            model.edit("patch:\n  a: [\n")

            model.save()
            advanceUntilIdle()

            val state = model.uiState.value
            assertEquals(RimeFileSaveError(message), state.error)
            assertEquals("patch:\n  a: [\n", state.text)
            assertTrue(state.dirty)
            assertFalse(state.saved)

            model.edit("patch:\n  a: 1\n")
            assertNull("typing clears the message", model.uiState.value.error)
        }

    @Test
    fun aSavedFileIsCleanAndSavingNothingDoesNotWrite() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            model.save()
            advanceUntilIdle()
            assertTrue("nothing changed, nothing saved", gateway.saved.isEmpty())

            model.edit("patch:\n  menu/page_size: 7\n")
            model.save()
            advanceUntilIdle()
            assertEquals(listOf("patch:\n  menu/page_size: 7\n"), gateway.saved)
            assertTrue(model.uiState.value.saved)
            assertFalse(model.uiState.value.dirty)
        }

    @Test
    fun aFileThatCannotBeOpenedIsNotEditable() =
        runTest(dispatcher) {
            gateway.load = RimeConfigFileResult.Failure("too large")
            val model = model()
            advanceUntilIdle()

            assertTrue(model.uiState.value.failed)
            assertEquals("too large", model.uiState.value.loadFailure)
            model.edit("x")
            model.save()
            advanceUntilIdle()
            assertTrue(gateway.saved.isEmpty())
        }

    @Test
    fun externalReturnReloadsDiskAndDirtyHandoffRequiresDiscard() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            gateway.external = RimeConfigEditResult.Ready(emptyList())
            model.edit("unsaved")
            model.openElsewhere()
            advanceUntilIdle()
            assertNull(model.uiState.value.externalIntents)
            model.discardDraft()
            model.openElsewhere()
            advanceUntilIdle()
            model.externalLaunched(true)
            gateway.load = RimeConfigFileResult.Loaded("内置全拼", "songyan_quanpin.custom.yaml", "external")
            model.onResume()
            advanceUntilIdle()
            assertEquals("external", model.uiState.value.text)
            assertFalse(model.uiState.value.dirty)

            model.externalLaunched(true)
            model.edit("local edits")
            gateway.load = RimeConfigFileResult.Loaded("内置全拼", "songyan_quanpin.custom.yaml", "external changes")
            model.onResume()
            advanceUntilIdle()
            assertEquals("local edits", model.uiState.value.text)
            assertEquals("external", model.uiState.value.baseline)
            assertEquals("external changes", model.uiState.value.externalConflict)
            model.save()
            advanceUntilIdle()
            assertTrue(gateway.saved.isEmpty())

            model.resolveExternalConflict(loadFile = false)
            assertNull(model.uiState.value.externalConflict)
            assertEquals("local edits", model.uiState.value.text)
            assertEquals("external changes", model.uiState.value.baseline)
            assertTrue(model.uiState.value.dirty)

            model.externalLaunched(true)
            gateway.load = RimeConfigFileResult.Loaded("内置全拼", "songyan_quanpin.custom.yaml", "new external changes")
            model.onResume()
            advanceUntilIdle()
            model.resolveExternalConflict(loadFile = true)
            assertEquals("new external changes", model.uiState.value.text)
            assertFalse(model.uiState.value.dirty)
            assertNull(model.uiState.value.externalConflict)
        }

    @Test
    fun editsDuringSaveRemainDirtyAndCannotBeHandedOffAsSaved() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            gateway.saveGate = CompletableDeferred()
            model.edit("first")
            model.save()
            model.save()
            runCurrent()
            model.edit("second")
            gateway.saveGate?.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("first"), gateway.saved)
            assertEquals("second", model.uiState.value.text)
            assertEquals("first", model.uiState.value.baseline)
            assertTrue(model.uiState.value.dirty)
            assertFalse(model.uiState.value.saved)
        }

    @Test
    fun privateDraftRestoresTextWithoutPuttingItInSavedState() =
        runTest(dispatcher) {
            val directory = Files.createTempDirectory("config-draft").toFile()
            try {
                val handle = SavedStateHandle()
                val drafts = EditorDraftStore(directory, handle)
                val first = RimeFileEditorViewModel(gateway, "songyan_quanpin", false, handle, drafts)
                first.uiState.first { !it.loading }
                first.edit("draft text")
                first.persistDraft()
                drafts.close().join()
                ViewModelStore().apply {
                    put("editor", first)
                    clear()
                }
                val restoredHandle = SavedStateHandle(handle.keys().associateWith { handle.get<Any?>(it) })
                val restoredDrafts = EditorDraftStore(directory, restoredHandle)
                val restored =
                    RimeFileEditorViewModel(gateway, "songyan_quanpin", false, restoredHandle, restoredDrafts)
                val state = restored.uiState.first { !it.loading }
                assertEquals("draft text", state.text)
                assertTrue(state.dirty)
                assertEquals(setOf("editorDraftToken"), handle.keys())
                restored.discardDraft()
                restored.persistDraft()
                restoredDrafts.close().join()
                ViewModelStore().apply {
                    put("editor", restored)
                    clear()
                }
            } finally {
                directory.deleteRecursively()
            }
        }

    @Test
    fun openingElsewhereHandsOverIntentsOrSaysThereIsNoApp() =
        runTest(dispatcher) {
            val model = model()
            advanceUntilIdle()
            gateway.external = RimeConfigEditResult.Ready(emptyList())

            model.openElsewhere()
            advanceUntilIdle()
            assertEquals(emptyList<Intent>(), model.uiState.value.externalIntents)

            model.externalLaunched(opened = false)
            assertNull(model.uiState.value.externalIntents)
            assertEquals(EditorNotice.NoEditor, model.uiState.value.notice)
            model.noticeShown()
            assertNull(model.uiState.value.notice)
        }
}
