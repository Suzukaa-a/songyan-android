package app.songyan.ime.settings

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.LocaleList
import android.util.Log
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.support.CandidateTextSize
import app.songyan.ime.keyboard.support.KeyFeedbackSettings
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.settings.state.onboardingProgress
import app.songyan.ime.settings.ui.DictionaryImportUiState
import app.songyan.ime.settings.ui.SchemeImportUiState
import app.songyan.ime.settings.ui.SettingsUiState
import app.songyan.ime.settings.ui.screens.FuzzyPinyinDialog
import app.songyan.ime.settings.ui.screens.HomeScreen
import app.songyan.ime.settings.ui.screens.InputOptionsScreen
import app.songyan.ime.settings.ui.screens.KeyboardFeelState
import app.songyan.ime.settings.ui.screens.OnboardingScreen
import app.songyan.ime.settings.ui.screens.SchemesScreen
import app.songyan.ime.settings.ui.screens.SystemFeedbackState
import app.songyan.ime.settings.ui.screens.TryOutScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.io.FileOutputStream

/**
 * Long translated labels must fit the same compact viewport at the largest system font size.
 * A localized context and matching density keep resource selection and text measurement on the
 * same configuration while each language is rendered.
 */
class LargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    /** The app languages this test renders; the first is the default the other screens assume. */
    private val language = mutableStateOf(APP_LANGUAGES.first().first)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent {
            // The base configuration comes from the composition, not from LocalContext: reading it
            // off the Context is what LocalContextConfigurationRead forbids.
            val baseConfiguration = LocalConfiguration.current
            val context = LocalContext.current
            // The localized Context below is not an Activity, so the activity-result registry owner
            // has to be read before it replaces LocalContext and handed back to the screens.
            val activityResultOwner =
                checkNotNull(LocalActivityResultRegistryOwner.current) {
                    "The test activity must provide its activity-result registry."
                }
            val tag by language
            val configuration =
                remember(baseConfiguration, tag) {
                    Configuration(baseConfiguration).apply {
                        // The largest system font size the visual check asks for, kept in the same
                        // configuration the Density is taken from, so both describe the same device.
                        fontScale = FONT_SCALE
                        setLocales(LocaleList.forLanguageTags(tag))
                    }
                }
            val localized = remember(baseConfiguration, tag) { context.createConfigurationContext(configuration) }
            CompositionLocalProvider(
                // Platform density for that context: on Android 14+ this carries the non-linear
                // converter for the 200% scale, which a hand-built linear Density would not.
                LocalDensity provides Density(localized),
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalActivityResultRegistryOwner.provides(activityResultOwner),
            ) {
                // A real per-app language change recreates the Activity; keying the content on the tag
                // mirrors that rebuild for each language. It only resets the composition, it is not a
                // fix for incorrect measurements.
                key(tag) {
                    SongyanTheme(dynamicColor = false) {
                        // A 280 dp wide window: a compact phone, so the large text has to wrap.
                        Box(Modifier.width(280.dp)) { content() }
                    }
                }
            }
        }
    }

    /** Every screen above is rendered once per language; the largest font has to fit in all of them. */
    private fun assertEveryLanguageFits() {
        for ((tag, _) in APP_LANGUAGES) {
            compose.runOnIdle { language.value = tag }
            compose.waitForIdle()
            assertNoTruncatedText(tag)
        }
    }

    private fun assertNoTruncatedText(tag: String) {
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
                if (!layout.didOverflowHeight && !cutByLineLimit) continue
                val input = layout.layoutInput
                truncated +=
                    buildString {
                        append(input.text.text)
                        append(" [bounds=").append(node.boundsInRoot)
                        append(", nodeSize=").append(node.size)
                        append(", layoutSize=").append(layout.size.width).append('x').append(layout.size.height)
                        append(", lineCount=").append(layout.lineCount)
                        append(", maxLines=").append(input.maxLines)
                        append(", didOverflowHeight=").append(layout.didOverflowHeight)
                        append(", didOverflowWidth=").append(layout.didOverflowWidth)
                        append(", cutByLineLimit=").append(cutByLineLimit)
                        append(", layoutsOfNode=").append(results.size)
                        append(", fontScale=").append(input.density.fontScale)
                        append(", paragraphHeight=").append(layout.multiParagraph.height)
                        append(", heightOverflowPx=")
                        append(layout.multiParagraph.height - layout.size.height)
                        if (layout.lineCount > 0) {
                            append(", lastLineBottom=").append(layout.getLineBottom(layout.lineCount - 1))
                        }
                        append(", constraints=")
                        append(input.constraints.minWidth).append('x').append(input.constraints.minHeight)
                        append("..")
                        append(input.constraints.maxWidth).append('x').append(input.constraints.maxHeight)
                        append(']')
                    }
            }
        }
        if (truncated.isNotEmpty()) captureFailureScreenshot(tag)
        assertTrue("truncated in $tag at font scale $FONT_SCALE: $truncated", truncated.isEmpty())
    }

    /**
     * Keeps what the failing language actually rendered. The compose view draws in software, so the
     * image holds the real layout even when another app's window covers the test activity.
     */
    private fun captureFailureScreenshot(tag: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "largefont-$tag.png")
        val result =
            runCatching {
                FileOutputStream(file).use { out ->
                    val image = compose.onRoot().captureToImage().asAndroidBitmap()
                    image.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
            }
        if (result.isSuccess) {
            Log.i(SCREENSHOT_TAG, "largefont screenshot: ${file.absolutePath}")
        } else {
            Log.w(SCREENSHOT_TAG, "largefont screenshot failed for $tag: ${result.exceptionOrNull()}")
        }
    }

    /** Guards the injection itself: a missing translation would silently fall back to English. */
    @Test
    fun everyAppLanguageReachesTheScreens() {
        show { Text(stringResource(R.string.row_tryout_title)) }
        for ((tag, tryOutTitle) in APP_LANGUAGES) {
            compose.runOnIdle { language.value = tag }
            compose.waitForIdle()
            compose.onNodeWithText(tryOutTitle).assertExists()
        }
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
        assertEveryLanguageFits()
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
        assertEveryLanguageFits()
    }

    /** The densest page: every row is a title plus a subtitle that has to wrap. */
    @Test
    fun theInputOptionsPageShowsItsRowsInFull() {
        show {
            InputOptionsScreen(
                keyboardFeel = KeyboardFeelState(),
                onKeyHeightChanged = {},
                onOneHandChanged = {},
                onKeyPreviewChanged = {},
                onReleaseHapticChanged = {},
                candidateTextSize = CandidateTextSize.STANDARD,
                onCandidateTextSizeChanged = {},
                keyFeedback = KeyFeedbackSettings(),
                onKeyFeedbackChanged = {},
                onBack = {},
                system = SystemFeedbackState(soundEnabled = true, hapticsEnabled = true),
            )
        }
        assertEveryLanguageFits()
    }

    @Test
    fun theTryOutPageShowsEveryFieldKindInFull() {
        show {
            TryOutScreen(
                ime = ImeStatus(enabled = true, selected = true),
                onTyped = {},
                onEnableIme = {},
                onBack = null,
            )
        }
        assertEveryLanguageFits()
    }

    /** Long scheme names and status subtitles in one scrollable list. */
    @Test
    fun theSchemesPageShowsItsRowsInFull() {
        show {
            SchemesScreen(
                schemes = listOf(InstalledScheme("songyan-builtin", "松煙内蔵")),
                inUseSchemeId = "songyan-builtin",
                importedDictionary = null,
                dictionaryImport = DictionaryImportUiState.Idle,
                dictionaryImportAvailable = true,
                onImportDictionaryFile = { _, _ -> },
                onImportDictionaryFromUrl = { _, _ -> },
                onRemoveImportedDictionary = {},
                onDismissDictionaryImport = {},
                schemeImport = SchemeImportUiState.Idle,
                schemeImportAvailable = true,
                onImportScheme = {},
                onDismissSchemeImport = {},
                onCancelImport = {},
                onRetryImport = {},
                onUseScheme = {},
                onBack = null,
                sources =
                    listOf(
                        StoredSourceInfo("songyan-builtin", "松煙内蔵", StoredSourceKind.BUILTIN, inUse = true),
                        StoredSourceInfo(
                            "scheme-1",
                            "雾凇拼音",
                            StoredSourceKind.SCHEME,
                            inUse = false,
                            status = StoredSourceStatus.UPDATE_AVAILABLE,
                            updatable = true,
                        ),
                        StoredSourceInfo(
                            "scheme-2",
                            "万象拼音",
                            StoredSourceKind.SCHEME,
                            inUse = false,
                            status = StoredSourceStatus.DAMAGED,
                        ),
                    ),
            )
        }
        assertEveryLanguageFits()
    }

    @Test
    fun theFuzzyPinyinChoicesAreAtLeast48DpTallAtNormalFont() = assertFuzzyChoicesAtLeast48Dp(1f)

    @Test
    fun theFuzzyPinyinChoicesAreAtLeast48DpTallAtLargeFont() = assertFuzzyChoicesAtLeast48Dp(2f)

    private fun assertFuzzyChoicesAtLeast48Dp(scale: Float) {
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

    private companion object {
        /** The interface languages the app ships; zh-Hant is the Taiwanese set. */
        val APP_LANGUAGES =
            listOf(
                // Language tag to the try-out page's title in that language; a fallback would be English.
                "zh-Hans" to "试打",
                "ja" to "試し打ち",
                "zh-Hant" to "試打",
            )

        /** The largest system font size under test. */
        const val FONT_SCALE = 2f

        /** Logcat tag for the failure screenshot path. */
        private const val SCREENSHOT_TAG = "LargeFontTest"
    }
}
