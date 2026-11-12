package app.songyan.ime.settings.ui

import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptHeader
import app.songyan.ime.script.ScriptRegistry
import app.songyan.ime.script.ScriptRegistryUnavailable
import app.songyan.ime.script.ScriptRunOutcome
import app.songyan.ime.script.ScriptSourceOrigin
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 设置 → 脚本: the four ways to import a script, the confirmation gate, the update check that asks
 * for a fresh confirmation when the declarations changed, the list and trial-run actions, and the
 * failure codes the screen renders.
 *
 * The Android I/O shims (the picked file's [android.content.Context], the clipboard and the share
 * sheet) stay outside: the import pipeline is driven through [ScriptsViewModel.importSource].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ScriptsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val registry = FakeRegistry()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = ScriptsViewModel(registry, dispatcher)

    // --- the catalog ---

    @Test
    fun theListShowsTheCachedCatalogThenStopsRefreshing() =
        runTest(dispatcher) {
            registry.store(entry("script-1"), entry("script-2"))
            val viewModel = viewModel()

            assertEquals(true, viewModel.uiState.value.loading)
            assertEquals(true, viewModel.uiState.value.refreshing)
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.loading)
            assertEquals(false, viewModel.uiState.value.refreshing)
            assertEquals(
                listOf("script-1", "script-2"),
                viewModel.uiState.value.entries
                    .map { it.id },
            )
            assertEquals(1, registry.reloads)
        }

    @Test
    fun refreshRereadsTheCatalogTheKeyboardMayHaveChanged() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            assertEquals(emptyList<ScriptEntry>(), viewModel.uiState.value.entries)

            registry.store(entry("script-1", origin = ScriptSourceOrigin.File("note.js")))
            viewModel.refresh()
            advanceUntilIdle()

            assertEquals(2, registry.reloads)
            assertEquals(
                listOf("script-1"),
                viewModel.uiState.value.entries
                    .map { it.id },
            )
            assertEquals(
                ScriptSourceOrigin.File("note.js"),
                viewModel.uiState.value.entries
                    .single()
                    .sourceOrigin,
            )
        }

    // --- the four imports ---

    @Test
    fun pastedTextIsStoredAsPastedAndAsksForConfirmation() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.addFromPaste("// @api 1\nconsole.log(1)")
            advanceUntilIdle()

            val (bytes, origin) = registry.adds.single()
            assertEquals("// @api 1\nconsole.log(1)", bytes.toString(Charsets.UTF_8))
            assertEquals(ScriptSourceOrigin.Pasted, origin)
            assertEquals(false, viewModel.uiState.value.busy)
            assertNull(viewModel.uiState.value.importFailure)
            val pending = viewModel.uiState.value.pendingConfirmation
            assertNotNull(pending)
            assertTrue(pending!!.confirmationPending)
            assertEquals(
                listOf("script-1"),
                viewModel.uiState.value.entries
                    .map { it.id },
            )
        }

    @Test
    fun anEmptyPasteIsIgnored() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.addFromPaste("")

            advanceUntilIdle()
            assertEquals(emptyList<ByteArray>(), registry.adds.map { it.first })
            assertNull(viewModel.uiState.value.pendingConfirmation)
        }

    @Test
    fun aPickedFileIsStoredWithItsDisplayName() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.importSource(ScriptSourceOrigin.File("rent.js")) { "console.log(2)".toByteArray() }
            advanceUntilIdle()

            val (bytes, origin) = registry.adds.single()
            assertEquals("console.log(2)", bytes.toString(Charsets.UTF_8))
            assertEquals(ScriptSourceOrigin.File("rent.js"), origin)
            assertEquals(
                "script-1",
                viewModel.uiState.value.pendingConfirmation
                    ?.id,
            )
        }

    @Test
    fun aFileThatCannotBeReadFailsWithItsMessage() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.importSource(ScriptSourceOrigin.File("rent.js")) { throw java.io.IOException("gone") }
            advanceUntilIdle()

            assertEquals(emptyList<ByteArray>(), registry.adds.map { it.first })
            assertEquals(ScriptFailure("script", 0, "gone"), viewModel.uiState.value.importFailure)
            assertEquals(false, viewModel.uiState.value.busy)
        }

    @Test
    fun aLinkIsTrimmedAndStoredAsALinkOrigin() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.addFromLink("  https://example.com/rent.js  ")
            advanceUntilIdle()

            assertEquals(listOf("https://example.com/rent.js"), registry.links)
            assertEquals(
                ScriptSourceOrigin.Link("https://example.com/rent.js", "https://example.com/rent.js"),
                viewModel.uiState.value.entries
                    .single()
                    .sourceOrigin,
            )
            assertEquals(
                true,
                viewModel.uiState.value.pendingConfirmation
                    ?.confirmationPending,
            )
            assertEquals(false, viewModel.uiState.value.busy)
        }

    @Test
    fun aBlankLinkIsIgnored() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.addFromLink("   ")
            advanceUntilIdle()

            assertEquals(emptyList<String>(), registry.links)
            assertEquals(false, viewModel.uiState.value.busy)
        }

    @Test
    fun aDownloadThatFailsSurfacesTheFailure() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            registry.failure = IllegalStateException("HTTP 404")

            viewModel.addFromLink("https://example.com/missing.js")
            advanceUntilIdle()

            assertEquals(ScriptFailure("script", 0, "HTTP 404"), viewModel.uiState.value.importFailure)
            assertNull(viewModel.uiState.value.pendingConfirmation)
            assertEquals(false, viewModel.uiState.value.busy)
        }

    @Test
    fun aSecondImportClearsThePreviousFailure() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            registry.failure = IllegalStateException("not a script")

            viewModel.addFromPaste("nope")
            advanceUntilIdle()
            assertNotNull(viewModel.uiState.value.importFailure)

            registry.failure = null
            viewModel.addFromPaste("// @api 1")
            advanceUntilIdle()

            assertNull(viewModel.uiState.value.importFailure)
            assertEquals(
                "script-1",
                viewModel.uiState.value.pendingConfirmation
                    ?.id,
            )
        }

    @Test
    fun editedSourceIsStoredAndReplacesTheOldScript() =
        runTest(dispatcher) {
            registry.store(entry("old-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.applyEdit("old-1", "// @api 1\nconsole.log(3)")
            advanceUntilIdle()

            assertEquals(
                "// @api 1\nconsole.log(3)",
                registry.adds
                    .single()
                    .first
                    .toString(Charsets.UTF_8),
            )
            assertEquals(ScriptSourceOrigin.Edited, registry.adds.single().second)
            assertEquals(listOf("old-1"), registry.deletes)
            assertEquals(
                "script-1",
                viewModel.uiState.value.pendingConfirmation
                    ?.id,
            )
            assertEquals(false, viewModel.uiState.value.busy)
            assertEquals(
                listOf("script-1"),
                viewModel.uiState.value.entries
                    .map { it.id },
            )
        }

    @Test
    fun editingANewScriptDeletesNothing() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.applyEdit("", "// @api 1")
            advanceUntilIdle()

            assertEquals(emptyList<String>(), registry.deletes)
            assertEquals(
                "script-1",
                viewModel.uiState.value.pendingConfirmation
                    ?.id,
            )
        }

    @Test
    fun anEditThatStoresUnderTheSameIdDeletesNothing() =
        runTest(dispatcher) {
            registry.store(entry("script-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.applyEdit("script-1", "// @api 1")
            advanceUntilIdle()

            assertEquals(emptyList<String>(), registry.deletes)
            assertEquals(
                "script-1",
                viewModel.uiState.value.pendingConfirmation
                    ?.id,
            )
        }

    @Test
    fun anEditWhoseStoreFailsKeepsTheOldScript() =
        runTest(dispatcher) {
            registry.store(entry("old-1"))
            val viewModel = viewModel()
            advanceUntilIdle()
            registry.failure = IllegalStateException("the header is refused")

            viewModel.applyEdit("old-1", "not a script")
            advanceUntilIdle()

            assertEquals(ScriptFailure("script", 0, "the header is refused"), viewModel.uiState.value.importFailure)
            assertEquals(emptyList<String>(), registry.deletes)
            assertEquals(
                listOf("old-1"),
                viewModel.uiState.value.entries
                    .map { it.id },
            )
        }

    // --- confirmation ---

    @Test
    fun confirmingTheScriptClearsThePendingState() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.addFromPaste("// @api 1")
            advanceUntilIdle()
            val pending = viewModel.uiState.value.pendingConfirmation!!
            assertTrue(pending.confirmationPending)

            viewModel.confirm(pending.id, mapOf("token" to "abc"))
            advanceUntilIdle()

            assertEquals(listOf(pending.id to mapOf("token" to "abc")), registry.confirms)
            assertNull(viewModel.uiState.value.pendingConfirmation)
            assertFalse(
                viewModel.uiState.value.entries
                    .single()
                    .confirmationPending,
            )
            assertEquals(false, viewModel.uiState.value.busy)
        }

    @Test
    fun aFailedConfirmationShowsTheFailureAndKeepsTheScriptPending() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.addFromPaste("// @api 1")
            advanceUntilIdle()
            registry.failure = IllegalStateException("the proxy did not answer")

            viewModel.confirm("script-1", emptyMap())
            advanceUntilIdle()

            assertEquals(ScriptFailure("script", 0, "the proxy did not answer"), viewModel.uiState.value.importFailure)
            assertEquals(
                "script-1",
                viewModel.uiState.value.pendingConfirmation
                    ?.id,
            )
            assertEquals(false, viewModel.uiState.value.busy)
        }

    // --- the update check ---

    @Test
    fun anUpdateWithNewDeclarationsAsksForConfirmationAgain() =
        runTest(dispatcher) {
            registry.store(
                entry("script-1", origin = ScriptSourceOrigin.Link("https://a.test/s.js", "https://a.test/s.js")),
            )
            val viewModel = viewModel()
            advanceUntilIdle()
            val updated =
                entry(
                    "script-1",
                    origin = ScriptSourceOrigin.Link("https://a.test/s.js", "https://a.test/s.js"),
                    pending = true,
                )
            registry.checkUpdateEntry = updated

            viewModel.checkUpdate("script-1")
            advanceUntilIdle()

            assertEquals(listOf("script-1"), registry.updates)
            assertEquals(ScriptsNotice.Updated(updated), viewModel.uiState.value.notice)
            assertTrue(
                viewModel.uiState.value.entries
                    .single()
                    .confirmationPending,
            )
            assertEquals(false, viewModel.uiState.value.busy)
        }

    @Test
    fun anUpdateWithoutNewDeclarationsSaysItIsUpToDate() =
        runTest(dispatcher) {
            registry.store(
                entry("script-1", origin = ScriptSourceOrigin.Link("https://a.test/s.js", "https://a.test/s.js")),
            )
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.checkUpdate("script-1")
            advanceUntilIdle()

            assertEquals(ScriptsNotice.UpToDate, viewModel.uiState.value.notice)
            assertFalse(
                viewModel.uiState.value.entries
                    .single()
                    .confirmationPending,
            )
            assertEquals(false, viewModel.uiState.value.busy)
        }

    @Test
    fun aFailedUpdateCheckShowsTheFailureNotice() =
        runTest(dispatcher) {
            registry.store(
                entry("script-1", origin = ScriptSourceOrigin.Link("https://a.test/s.js", "https://a.test/s.js")),
            )
            val viewModel = viewModel()
            advanceUntilIdle()
            registry.failure = IllegalStateException("the new header is refused")

            viewModel.checkUpdate("script-1")
            advanceUntilIdle()

            assertEquals(
                ScriptsNotice.ActionFailed(ScriptFailure("script", 0, "the new header is refused")),
                viewModel.uiState.value.notice,
            )
            assertEquals(false, viewModel.uiState.value.busy)
        }

    // --- failure codes ---

    @Test
    fun aMissingScriptHostMapsToTheUnavailableCode() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            registry.failure = ScriptRegistryUnavailable()

            viewModel.addFromPaste("// @api 1")
            advanceUntilIdle()
            assertEquals(ScriptFailure("unavailable", 0, null), viewModel.uiState.value.importFailure)
            viewModel.consumeImportFailure()
            assertNull(viewModel.uiState.value.importFailure)

            registry.store(
                entry("script-1", origin = ScriptSourceOrigin.Link("https://a.test/s.js", "https://a.test/s.js")),
            )
            viewModel.checkUpdate("script-1")
            advanceUntilIdle()
            assertEquals(
                ScriptsNotice.ActionFailed(ScriptFailure("unavailable", 0, null)),
                viewModel.uiState.value.notice,
            )
        }

    @Test
    fun theImportKeepsTheButtonsBusyUntilItFinishes() =
        runTest(dispatcher) {
            val viewModel = viewModel()
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            registry.addGate = gate

            viewModel.addFromPaste("// @api 1")
            advanceUntilIdle()

            assertEquals(true, viewModel.uiState.value.busy)
            assertNull(viewModel.uiState.value.pendingConfirmation)

            gate.complete(Unit)
            advanceUntilIdle()

            assertEquals(false, viewModel.uiState.value.busy)
            assertEquals(
                "script-1",
                viewModel.uiState.value.pendingConfirmation
                    ?.id,
            )
        }

    // --- the list actions ---

    @Test
    fun enablingAScriptReportsTheChangedList() =
        runTest(dispatcher) {
            registry.store(entry("script-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.setEnabled("script-1", false)
            advanceUntilIdle()

            assertEquals(listOf("script-1" to false), registry.enabled)
            assertFalse(
                viewModel.uiState.value.entries
                    .single()
                    .enabled,
            )
        }

    @Test
    fun aFailedToggleShowsTheFailureNotice() =
        runTest(dispatcher) {
            registry.store(entry("script-1"))
            val viewModel = viewModel()
            advanceUntilIdle()
            registry.failure = IllegalStateException("the store is read-only")

            viewModel.setEnabled("script-1", true)
            advanceUntilIdle()

            assertEquals(
                ScriptsNotice.ActionFailed(ScriptFailure("script", 0, "the store is read-only")),
                viewModel.uiState.value.notice,
            )
            viewModel.consumeNotice()
            assertNull(viewModel.uiState.value.notice)
        }

    @Test
    fun movingAScriptSendsTheWholeOrder() =
        runTest(dispatcher) {
            registry.store(
                entry("script-1", order = 0),
                entry("script-2", order = 1),
                entry("script-3", order = 2),
            )
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.move("script-3", up = true)
            advanceUntilIdle()

            assertEquals(listOf(listOf("script-1", "script-3", "script-2")), registry.reorders)
            assertEquals(
                listOf("script-1", "script-3", "script-2"),
                viewModel.uiState.value.entries
                    .map { it.id },
            )
        }

    @Test
    fun movingPastTheEndDoesNothing() =
        runTest(dispatcher) {
            registry.store(entry("script-1", order = 0), entry("script-2", order = 1))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.move("script-1", up = true)
            viewModel.move("script-2", up = false)
            advanceUntilIdle()

            assertEquals(emptyList<List<String>>(), registry.reorders)
        }

    @Test
    fun deletingAScriptSaysSoAndClosesItsTrialBox() =
        runTest(dispatcher) {
            registry.store(entry("script-1"))
            val viewModel = viewModel()
            advanceUntilIdle()
            viewModel.beginTest("script-1")

            viewModel.delete("script-1")
            advanceUntilIdle()

            assertEquals(listOf("script-1"), registry.deletes)
            assertEquals(ScriptsNotice.Deleted, viewModel.uiState.value.notice)
            assertNull(viewModel.test.value)
            assertEquals(emptyList<ScriptEntry>(), viewModel.uiState.value.entries)
        }

    // --- the trial run ---

    @Test
    fun aTrialRunShowsItsOutcome() =
        runTest(dispatcher) {
            registry.store(entry("script-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.beginTest("script-1")
            viewModel.editTestInput("hello")
            viewModel.runTest()
            advanceUntilIdle()

            assertEquals(listOf("script-1" to "hello"), registry.runs)
            assertEquals(
                ScriptRunOutcome.Success(listOf("script-1:hello"), emptyList(), false),
                viewModel.test.value?.outcome,
            )
            assertEquals(false, viewModel.test.value?.running)
        }

    @Test
    fun aFailedTrialRunKeepsTheEngineMessage() =
        runTest(dispatcher) {
            registry.store(entry("script-1"))
            val viewModel = viewModel()
            advanceUntilIdle()
            registry.failure = IllegalStateException("the engine is gone")

            viewModel.beginTest("script-1")
            viewModel.runTest()
            advanceUntilIdle()

            assertEquals(ScriptRunOutcome.Failed("script", "the engine is gone", 0), viewModel.test.value?.outcome)
            assertEquals(false, viewModel.test.value?.running)
        }

    @Test
    fun aTrialRunKeepsTheEngineFailureCode() =
        runTest(dispatcher) {
            registry.store(entry("script-1"))
            val viewModel = viewModel()
            advanceUntilIdle()
            registry.runResult = ScriptRunOutcome.Failed("addressNotAllowed", "the host is not declared", 4)

            viewModel.beginTest("script-1")
            viewModel.runTest()
            advanceUntilIdle()

            assertEquals(
                ScriptRunOutcome.Failed("addressNotAllowed", "the host is not declared", 4),
                viewModel.test.value?.outcome,
            )
        }

    @Test
    fun cancelTestClosesTheBoxAndCancelsTheRun() =
        runTest(dispatcher) {
            registry.store(entry("script-1"))
            val viewModel = viewModel()
            advanceUntilIdle()

            viewModel.beginTest("script-1")
            viewModel.cancelTest()
            advanceUntilIdle()

            assertEquals(listOf("script-1"), registry.cancels)
            assertNull(viewModel.test.value)
        }

    /** The in-memory registry the screen talks to; each call records its arguments so the test can
     *  assert what the ViewModel handed over. [failure] makes every mutating call throw, [addGate]
     *  parks a store so the busy state can be observed. */
    private class FakeRegistry : ScriptRegistry {
        private val stored = mutableListOf<ScriptEntry>()

        override var entries: List<ScriptEntry> = emptyList()
            private set

        override val refreshing: Boolean = false

        var reloads = 0
        var failure: Throwable? = null
        var nextId = 1
        var sourceBytes: ByteArray? = null
        var checkUpdateEntry: ScriptEntry? = null
        var addGate: CompletableDeferred<Unit>? = null
        var runResult: ScriptRunOutcome? = null

        val adds = mutableListOf<Pair<ByteArray, ScriptSourceOrigin>>()
        val links = mutableListOf<String>()
        val confirms = mutableListOf<Pair<String, Map<String, String>>>()
        val enabled = mutableListOf<Pair<String, Boolean>>()
        val reorders = mutableListOf<List<String>>()
        val deletes = mutableListOf<String>()
        val updates = mutableListOf<String>()
        val runs = mutableListOf<Pair<String, String>>()
        val cancels = mutableListOf<String>()

        fun store(vararg entries: ScriptEntry) {
            stored.clear()
            stored += entries
            this.entries = stored.toList()
        }

        private fun failIfAsked() {
            failure?.let { throw it }
        }

        override suspend fun reload() {
            reloads++
            failIfAsked()
            entries = stored.toList()
        }

        override suspend fun inspect(source: ByteArray): ScriptHeader = error("inspect is not used by this screen")

        override suspend fun add(
            source: ByteArray,
            origin: ScriptSourceOrigin,
        ): ScriptEntry {
            adds += source to origin
            addGate?.await()
            failIfAsked()
            val entry = entry("script-" + nextId++, origin = origin, pending = true)
            stored += entry
            entries = stored.toList()
            return entry
        }

        override suspend fun addFromLink(url: String): ScriptEntry {
            links += url
            failIfAsked()
            val entry =
                entry(
                    "script-" + nextId++,
                    origin = ScriptSourceOrigin.Link(url, url),
                    pending = true,
                )
            stored += entry
            entries = stored.toList()
            return entry
        }

        override suspend fun confirm(
            id: String,
            settings: Map<String, String>,
        ): ScriptEntry {
            confirms += id to settings
            failIfAsked()
            return replace(id) { it.copy(confirmationPending = false) }
        }

        override suspend fun updateSettings(
            id: String,
            settings: Map<String, String>,
        ): ScriptEntry = confirm(id, settings)

        override suspend fun setEnabled(
            id: String,
            enabled: Boolean,
        ): ScriptEntry {
            this.enabled += id to enabled
            failIfAsked()
            return replace(id) { it.copy(enabled = enabled) }
        }

        override suspend fun reorder(orderedIds: List<String>) {
            reorders += orderedIds
            failIfAsked()
            val byId = stored.associateBy { it.id }
            stored.clear()
            orderedIds.forEachIndexed { index, id ->
                byId[id]?.let { stored += it.copy(order = index) }
            }
            entries = stored.toList()
        }

        override suspend fun source(id: String): ByteArray? = sourceBytes

        override suspend fun export(id: String): ByteArray? = sourceBytes

        override suspend fun checkUpdate(id: String): ScriptEntry {
            updates += id
            failIfAsked()
            checkUpdateEntry?.let { fresh ->
                return replace(id) { fresh }
            }
            return replace(id) { it.copy(confirmationPending = false) }
        }

        override suspend fun delete(id: String) {
            deletes += id
            failIfAsked()
            stored.removeAll { it.id == id }
            entries = stored.toList()
        }

        override suspend fun runTrial(
            id: String,
            text: String,
        ): ScriptRunOutcome {
            runs += id to text
            failIfAsked()
            return runResult ?: ScriptRunOutcome.Success(listOf(id + ":" + text), emptyList(), false)
        }

        override suspend fun run(
            id: String,
            text: String,
        ): ScriptRunOutcome = runTrial(id, text)

        override suspend fun runAudio(
            id: String,
            wav: ByteArray,
        ): ScriptRunOutcome = runTrial(id, wav.size.toString())

        override suspend fun cancel(id: String) {
            cancels += id
        }

        private fun replace(
            id: String,
            change: (ScriptEntry) -> ScriptEntry,
        ): ScriptEntry {
            val index = stored.indexOfFirst { it.id == id }
            check(index >= 0) { "no script " + id }
            stored[index] = change(stored[index])
            entries = stored.toList()
            return stored[index]
        }
    }

    private companion object {
        fun entry(
            id: String,
            origin: ScriptSourceOrigin = ScriptSourceOrigin.Pasted,
            pending: Boolean = false,
            order: Int = 0,
        ) = ScriptEntry(
            id = id,
            name = "script " + id,
            description = null,
            enabled = !pending,
            order = order,
            apiVersion = 1,
            connects = emptyList(),
            needsAudio = false,
            availableOnThisHost = true,
            confirmationPending = pending,
            settings = emptyList(),
            sourceOrigin = origin,
        )
    }
}
