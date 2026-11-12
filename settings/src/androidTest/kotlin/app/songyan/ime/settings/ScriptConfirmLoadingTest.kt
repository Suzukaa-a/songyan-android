package app.songyan.ime.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import app.songyan.ime.settings.ui.ScriptsViewModel
import app.songyan.ime.settings.ui.screens.ScriptConfirmScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The confirmation page before its catalog has been read. The page is reached from a list that has
 * already loaded, but a restored process can compose it first: "still loading" must show the
 * loading frame, and only "loaded and really missing" may leave.
 */
class ScriptConfirmLoadingTest {
    @get:Rule
    val compose = createComposeRule()

    private fun loadingGate(): CompletableDeferred<Unit> = CompletableDeferred()

    private fun showConfirmScreen(
        registry: FakeScriptRegistry,
        gate: CompletableDeferred<Unit>,
        done: AtomicBoolean,
    ) {
        registry.reloadGate = gate
        val viewModel = ScriptsViewModel(registry, Dispatchers.Unconfined)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                ScriptConfirmScreen(
                    scriptId = "script-1",
                    onDone = { done.set(true) },
                    viewModel = viewModel,
                )
            }
        }
    }

    @Test
    fun aStillLoadingCatalogShowsTheLoadingFrameInsteadOfLeaving() {
        val gate = loadingGate()
        val registry = FakeScriptRegistry().apply { store(declaredEntry(pending = true)) }
        val done = AtomicBoolean(false)
        showConfirmScreen(registry, gate, done)
        compose.waitForIdle()

        // Not loaded yet: no confirmation form, no onDone, and the frame says it is waiting.
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertDoesNotExist()
        assertFalse("the page must not close while the catalog is still loading", done.get())
        compose
            .onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
            .assertExists()

        // The catalog arrives: the page renders, and it still does not close itself.
        compose.runOnIdle { gate.complete(Unit) }
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(str(R.string.scripts_confirm_action))).fetchSemanticsNodes().isNotEmpty()
        }
        assertFalse("a loaded entry is confirmed, not closed", done.get())
        compose.onNodeWithText(str(R.string.scripts_confirm_action)).assertExists()
    }

    @Test
    fun anEntryThatIsReallyMissingLeavesAfterTheCatalogLoads() {
        val gate = loadingGate()
        val registry = FakeScriptRegistry()
        val done = AtomicBoolean(false)
        showConfirmScreen(registry, gate, done)
        compose.waitForIdle()

        // Still loading: not "missing", so the page stays.
        assertFalse(done.get())
        compose.runOnIdle { gate.complete(Unit) }

        // Loaded and absent (deleted elsewhere): the page says so instead of vanishing unseen.
        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText(str(R.string.scripts_missing_title))).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.scripts_missing_title)).assertExists()
        assertFalse("a missing entry must not close the page without telling the user", done.get())
    }
}
