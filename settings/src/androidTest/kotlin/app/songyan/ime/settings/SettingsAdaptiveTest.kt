package app.songyan.ime.settings

import android.app.Application
import androidx.compose.material3.adaptive.Posture
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import app.songyan.ime.keyboard.support.SettingsTarget
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.InMemorySettingsAppStateSource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.SettingsAppState
import app.songyan.ime.settings.state.UnavailableDictionaryImportGateway
import app.songyan.ime.settings.state.UnavailableSchemeImportGateway
import app.songyan.ime.settings.ui.SettingsHost
import app.songyan.ime.settings.ui.SettingsRoutes
import app.songyan.ime.settings.ui.SettingsViewModel
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The list-detail layout on a wide window, the single-pane navigation on a narrow one. */
class SettingsAdaptiveTest {
    @get:Rule
    val compose = createComposeRule()

    /**
     * A window of the given width (dp), independent of the emulator's real window, bucketed the way
     * the platform reports it: the pane scaffold only knows the standard breakpoints.
     */
    private fun adaptiveInfo(widthDp: Int) =
        WindowAdaptiveInfo(
            windowSizeClass = WindowSizeClass.BREAKPOINTS_V1.computeWindowSizeClass(widthDp, 800),
            windowPosture = Posture(),
        )

    private fun show(wide: Boolean) = show(if (wide) EXPANDED_DP else COMPACT_DP)

    private fun model() =
        SettingsViewModel(
            application = ApplicationProvider.getApplicationContext<Application>(),
            appStateSource = InMemorySettingsAppStateSource(SettingsAppState(engine = EngineStatus.Ready)),
            imeStatusSource = { ImeStatus(enabled = true, selected = true) },
            preferences =
                InMemorySettingsPreferences().also {
                    kotlinx.coroutines.runBlocking {
                        it
                            .setOnboardingShown()
                    }
                },
            dictionaryGateway = UnavailableDictionaryImportGateway(),
            schemeGateway = UnavailableSchemeImportGateway(),
        )

    /** The settings in a window whose width the test changes as a rotation, fold or resize would. */
    private fun show(widthDp: Int): MutableState<Int> {
        val width = mutableStateOf(widthDp)
        val model = model()
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                SettingsHost(viewModel = model, versionName = "1", adaptiveInfo = adaptiveInfo(width.value))
            }
        }
        return width
    }

    private fun waitFor(text: String) = compose.waitUntil(5_000) { count(text) > 0 }

    private fun openTryOut() {
        compose.onNodeWithText(str(R.string.row_tryout_title)).performClick()
        waitFor(str(R.string.tryout_hint))
    }

    /** The try-out field: the one editable field that is not the settings search. */
    private val tryOutField = hasSetTextAction() and !hasText(str(R.string.search_placeholder))

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun aWideWindowShowsTheListAndAnEmptyDetailSideBySide() {
        show(wide = true)
        compose.onNodeWithText(str(R.string.section_keyboard)).assertExists()
        compose.onNodeWithText(str(R.string.detail_empty_title)).assertExists()
    }

    @Test
    fun pickingASectionFillsTheDetailPaneAndKeepsTheList() {
        show(wide = true)
        compose.onNodeWithText(str(R.string.row_appearance_title)).performClick()
        compose.waitUntil(5_000) { count(str(R.string.appearance_section_theme)) > 0 }
        // The list is still there, and the open section is the marked row.
        compose.onNodeWithText(str(R.string.section_keyboard)).assertExists()
        assertEquals(0, count(str(R.string.detail_empty_title)))
    }

    @Test
    fun aTopLevelPageBesideTheListHasNoBackArrow() {
        show(wide = true)
        compose.onNodeWithText(str(R.string.row_appearance_title)).performClick()
        compose.waitUntil(5_000) { count(str(R.string.appearance_section_theme)) > 0 }
        assertEquals(0, compose.onAllNodes(hasContentDescription(str(R.string.action_back))).fetchSemanticsNodes().size)
    }

    @Test
    fun aNarrowWindowShowsOnlyTheListAndOpensPagesOnTop() {
        show(wide = false)
        compose.onNodeWithText(str(R.string.section_keyboard)).assertExists()
        assertEquals(0, count(str(R.string.detail_empty_title)))
        compose.onNodeWithText(str(R.string.row_appearance_title)).performClick()
        compose.waitUntil(5_000) { count(str(R.string.appearance_section_theme)) > 0 }
        compose.waitUntil(5_000) { count(str(R.string.section_keyboard)) == 0 }
        compose.onNode(hasContentDescription(str(R.string.action_back))).assertExists()
    }

    /** A medium window has room for one pane: the list's rows must still open their pages. */
    @Test
    fun aMediumWindowOpensPagesFromTheList() {
        show(MEDIUM_DP)
        compose.onNodeWithText(str(R.string.row_appearance_title)).performClick()
        compose.waitUntil(5_000) { count(str(R.string.appearance_section_theme)) > 0 }
        compose.onNode(hasContentDescription(str(R.string.action_back))).assertExists()
    }

    /** Rotating a foldable's cover screen crosses the one/two-pane switch: the page and its back stack stay. */
    @Test
    fun theOpenPageAndItsBackStackSurviveTheSwitchBetweenOneAndTwoPanes() {
        val width = show(COMPACT_DP)
        openTryOut()

        width.value = EXPANDED_DP
        waitFor(str(R.string.section_keyboard))
        compose.onNodeWithText(str(R.string.tryout_hint)).assertExists()
        assertEquals(0, count(str(R.string.detail_empty_title)))

        width.value = COMPACT_DP
        compose.waitUntil(5_000) { count(str(R.string.section_keyboard)) == 0 }
        compose.onNodeWithText(str(R.string.tryout_hint)).assertExists()
        // Back still leads to the list the page was opened from.
        compose.onNode(hasContentDescription(str(R.string.action_back))).performClick()
        waitFor(str(R.string.section_keyboard))
        assertEquals(0, count(str(R.string.tryout_hint)))
    }

    /** The list is drawn in a different place in each layout; what the user typed into search must follow it. */
    @Test
    fun theSearchTextSurvivesTheSwitchBetweenOneAndTwoPanes() {
        val width = show(COMPACT_DP)
        compose.onNode(hasSetTextAction()).performTextInput("zzz")
        waitFor(str(R.string.search_empty_title))

        width.value = EXPANDED_DP
        waitFor(str(R.string.detail_empty_title))
        compose.onNode(hasSetTextAction() and hasText("zzz")).assertExists()
        waitFor(str(R.string.search_empty_title))

        width.value = COMPACT_DP
        compose.waitUntil(5_000) { count(str(R.string.detail_empty_title)) == 0 }
        compose.onNode(hasSetTextAction() and hasText("zzz")).assertExists()
    }

    /**
     * The activity is recreated on a rotation. The try-out field keeps its focus, so the keyboard
     * comes back up, and the search field beside it in the list does not take it.
     */
    @Test
    fun aRecreationAcrossTheSwitchKeepsTheTryOutFieldFocused() {
        val width = mutableStateOf(COMPACT_DP)
        val model = model()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            SongyanTheme(dynamicColor = false) {
                SettingsHost(viewModel = model, versionName = "1", adaptiveInfo = adaptiveInfo(width.value))
            }
        }
        openTryOut()
        compose.onNode(tryOutField).performClick()
        compose.onNode(tryOutField).assertIsFocused()

        width.value = EXPANDED_DP
        restoration.emulateSavedInstanceStateRestore()
        waitFor(str(R.string.section_keyboard))
        compose.waitUntil(5_000) {
            compose.onAllNodes(tryOutField and isFocused()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText(str(R.string.search_placeholder)).assertIsNotFocused()
    }

    @Test
    fun sidebarAndKeyboardTargetBothAskBeforeLeavingADirtyEditor() {
        val model = model()
        val target = mutableStateOf<SettingsTarget?>(null)
        lateinit var navigation: NavHostController
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                navigation = rememberNavController()
                SettingsHost(
                    viewModel = model,
                    versionName = "1",
                    adaptiveInfo = adaptiveInfo(EXPANDED_DP),
                    navController = navigation,
                    target = target.value,
                    onTargetHandled = { target.value = null },
                )
            }
        }
        waitFor(str(R.string.detail_empty_title))
        compose.runOnIdle { navigation.navigate(SettingsRoutes.layoutEditor("qwerty26")) }
        waitFor(str(R.string.layout_editor_name))
        compose.onNodeWithText(str(R.string.layout_editor_name)).performTextInput("edited")
        compose.onNode(hasScrollToIndexAction()).performScrollToNode(hasText(str(R.string.row_schemes_title)))
        compose.onNodeWithText(str(R.string.row_schemes_title)).performClick()
        waitFor(str(R.string.layout_editor_discard_title))
        compose.onNodeWithText(str(R.string.layout_editor_keep_editing)).performClick()
        compose.onNodeWithText(str(R.string.layout_editor_name)).assertExists()
        compose.runOnIdle { target.value = SettingsTarget.QUICK_CONTENT }
        waitFor(str(R.string.layout_editor_discard_title))
        compose.onNodeWithText(str(R.string.layout_editor_discard)).performClick()
        compose.waitUntil(5_000) { count(str(R.string.layout_editor_name)) == 0 }
        compose.runOnIdle {
            assertEquals(SettingsRoutes.QUICK_CONTENT, navigation.currentDestination?.route)
        }
    }

    private companion object {
        const val COMPACT_DP = 400
        const val MEDIUM_DP = 700
        const val EXPANDED_DP = 1000
    }
}
