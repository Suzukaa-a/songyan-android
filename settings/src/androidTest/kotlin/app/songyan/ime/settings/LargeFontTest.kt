package app.songyan.ime.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.onboardingProgress
import app.songyan.ime.settings.ui.SettingsUiState
import app.songyan.ime.settings.ui.screens.FuzzyPinyinDialog
import app.songyan.ime.settings.ui.screens.HomeScreen
import app.songyan.ime.settings.ui.screens.OnboardingScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** P7-1: at the largest system font size no text on the key screens is truncated or clipped. */
class LargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = 2f)) {
                SongyanTheme(dynamicColor = false) {
                    // A 280 dp wide window: a compact phone, so the large text has to wrap.
                    Box(Modifier.width(280.dp)) { content() }
                }
            }
        }
    }

    private fun assertNoTruncatedText() {
        val truncated = mutableListOf<String>()
        for (node in compose
            .onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher("has text layout") {
                    true
                },
            ).fetchSemanticsNodes()) {
            val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: continue
            val results = mutableListOf<TextLayoutResult>()
            if (!action(results)) continue
            for (layout in results) {
                // A line limit that cut the text: the last permitted line is narrower than the text
                // it had to show (didOverflowWidth and isLineEllipsized do not report this reliably).
                val cutByLineLimit =
                    layout.lineCount == layout.layoutInput.maxLines &&
                        layout.multiParagraph.intrinsics.maxIntrinsicWidth > layout.size.width + 1f
                if (layout.didOverflowHeight || cutByLineLimit) truncated += layout.layoutInput.text.text
            }
        }
        assertTrue("truncated at font scale 2.0: $truncated", truncated.isEmpty())
    }

    @Test
    fun theOnboardingGuideShowsEveryStepTitleInFull() {
        show {
            OnboardingScreen(
                progress = onboardingProgress(ImeStatus(enabled = false, selected = false), false),
                onOpenInputMethodSettings = {},
                onShowPicker = {},
                onRetry = {},
                onTyped = {},
                onBack = {},
            )
        }
        assertNoTruncatedText()
    }

    @Test
    fun theHomeScreenShowsItsRowsInFull() {
        show {
            HomeScreen(
                state =
                    SettingsUiState(
                        engine = EngineStatus.Ready,
                        ime = ImeStatus(enabled = false, selected = false),
                    ),
                onOpen = {},
                onRetryImeStatus = {},
                onCancelImport = {},
                onRetryImport = {},
                onDismissDictionaryImport = {},
                onDismissSchemeImport = {},
            )
        }
        assertNoTruncatedText()
    }

    @Test
    fun theFuzzyPinyinChoicesAreAtLeast48DpTallAtNormalFont() = assertFuzzyChoicesAtLeast48Dp(1f)

    @Test
    fun theFuzzyPinyinChoicesAreAtLeast48DpTallAtLargeFont() = assertFuzzyChoicesAtLeast48Dp(2f)

    private fun assertFuzzyChoicesAtLeast48Dp(scale: Float) {
        run {
            compose.setContent {
                val base = LocalDensity.current
                CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale = scale)) {
                    SongyanTheme(dynamicColor = false) {
                        FuzzyPinyinDialog(initial = emptySet(), onDismiss = {}, onSave = {})
                    }
                }
            }
            val checkboxes = compose.onAllNodes(isToggleable()).fetchSemanticsNodes()
            assertTrue(checkboxes.isNotEmpty())
            val minimum = 48 * compose.density.density
            for (node in checkboxes) {
                assertTrue(
                    "row is ${node.size.height} px tall at font scale $scale",
                    node.size.height >= minimum - 1f,
                )
            }
        }
    }
}
