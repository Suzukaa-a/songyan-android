package app.songyan.ime.settings

import android.content.Context
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.settings.ui.VoiceInputUiState
import app.songyan.ime.settings.ui.VoiceScriptOption
import app.songyan.ime.settings.ui.VoiceSourceOrigin
import app.songyan.ime.settings.ui.screens.VoicePermission
import app.songyan.ime.settings.ui.screens.showsSection
import app.songyan.ime.settings.ui.screens.voiceInputSection
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The voice input section of the Scripts page, rendered on its own: the microphone permission row
 * with its system-settings action, the selection semantics of the source list, a failed catalog
 * with its retry, when the section shows at all, and a large-font dark rendering where the actions
 * stay on screen and clickable.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInputSectionTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int): String = context.getString(id)

    private fun format(
        id: Int,
        vararg args: Any,
    ): String = context.getString(id, *args)

    private fun show(
        state: VoiceInputUiState,
        granted: Boolean = true,
        dialogBlocked: Boolean = false,
        dark: Boolean = false,
        fontScale: Float = 1f,
    ): MutableList<String> {
        val calls = mutableListOf<String>()
        compose.setContent {
            SongyanTheme(darkTheme = dark, dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    LazyColumn {
                        if (state.showsSection()) {
                            voiceInputSection(
                                state = state,
                                permission =
                                    VoicePermission(
                                        granted = granted,
                                        dialogBlocked = dialogBlocked,
                                        request = { calls += "request" },
                                        openSystemSettings = { calls += "settings" },
                                    ),
                                onEnabledChange = { calls += if (it) "enabled" else "disabled" },
                                onSelect = { calls += "select:$it" },
                                onRetry = { calls += "retry" },
                            )
                        }
                    }
                }
            }
        }
        return calls
    }

    private fun option(
        id: String,
        name: String,
        origin: VoiceSourceOrigin = VoiceSourceOrigin.Pasted,
        isAddon: Boolean = false,
    ) = VoiceScriptOption(id = id, name = name, isAddon = isAddon, origin = origin, enabled = true)

    @Test
    fun noSourceAndVoiceOffShowsNoSection() {
        show(VoiceInputUiState(loading = false))

        compose.onNodeWithText(string(R.string.voice_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.voice_switch_title)).assertDoesNotExist()
    }

    @Test
    fun aVoiceSourceShowsTheSection() {
        show(VoiceInputUiState(loading = false, scripts = listOf(option("a", "听写"))))

        compose.onNodeWithText(string(R.string.voice_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.voice_switch_title)).assertIsDisplayed()
    }

    @Test
    fun voiceSwitchedOnKeepsTheSectionSoItCanBeTurnedOff() {
        val calls = show(VoiceInputUiState(loading = false, enabled = true))

        compose.onNodeWithText(string(R.string.voice_switch_title)).assertIsEnabled().performClick()
        assertEquals(listOf("disabled"), calls)
    }

    @Test
    fun whileLoadingThereIsNoSection() {
        show(VoiceInputUiState(loading = true, enabled = true))

        compose.onNodeWithText(string(R.string.voice_title)).assertDoesNotExist()
    }

    @Test
    fun aMissingPermissionIsAskedForWithTheSystemDialogFirst() {
        val calls = show(VoiceInputUiState(loading = false, scripts = listOf(option("a", "听写"))), granted = false)

        compose.onNodeWithText(string(R.string.voice_permission_denied)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.voice_permission_open_settings)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.voice_permission_request)).assertIsDisplayed().performClick()
        assertEquals(listOf("request"), calls)
    }

    @Test
    fun onceAndroidStopsShowingTheDialogTheButtonOpensTheSystemPage() {
        val calls =
            show(
                VoiceInputUiState(loading = false, scripts = listOf(option("a", "听写"))),
                granted = false,
                dialogBlocked = true,
            )

        compose.onNodeWithText(string(R.string.voice_permission_request)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.voice_permission_open_settings)).assertIsDisplayed().performClick()
        assertEquals(listOf("settings"), calls)
    }

    @Test
    fun aGrantedPermissionStatesItWithoutTheAction() {
        show(VoiceInputUiState(loading = false, scripts = listOf(option("a", "听写"))), granted = true)

        compose.onNodeWithText(string(R.string.voice_permission_granted)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.voice_permission_open_settings)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.voice_permission_request)).assertDoesNotExist()
    }

    @Test
    fun theChosenSourceIsTheSelectedRadioWithItsOriginAndState() {
        val state =
            VoiceInputUiState(
                loading = false,
                enabled = true,
                selectedScriptId = "chosen",
                scripts =
                    listOf(
                        option("other", "听写甲"),
                        option("chosen", "听写乙", origin = VoiceSourceOrigin.Link),
                    ),
            )
        val calls = show(state)

        compose.onNodeWithText("听写乙").assertIsSelected()
        compose
            .onNodeWithText("听写乙")
            .assert(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.StateDescription,
                    string(R.string.voice_script_selected),
                ),
            )
        compose.onNodeWithText("听写甲").assertIsNotSelected()
        compose
            .onNodeWithText(
                format(
                    R.string.voice_script_summary,
                    string(R.string.scripts_origin_link),
                    string(R.string.voice_script_enabled),
                ),
            ).assertIsDisplayed()

        compose.onNodeWithText("听写甲").performClick()
        assertEquals(listOf("select:other"), calls)
    }

    @Test
    fun aCatalogThatCannotBeReadOffersARetry() {
        val calls = show(VoiceInputUiState(loading = false, failed = true, enabled = true))

        compose.onNodeWithText(string(R.string.voice_load_failed_title)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.voice_retry)).assertIsDisplayed().performClick()
        assertTrue(calls.contains("retry"))
    }

    @Test
    fun theSwitchSaysItNeedsTheMicrophonePermission() {
        val calls = show(VoiceInputUiState(loading = false, scripts = listOf(option("a", "听写"))), granted = false)

        compose.onNodeWithText(string(R.string.voice_switch_title)).assertIsNotEnabled()
        compose.onNodeWithText(string(R.string.voice_switch_needs_permission)).assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun theSwitchSaysItNeedsAVoiceScript() {
        show(VoiceInputUiState(loading = false, enabled = true, scripts = emptyList()), granted = true)

        compose.onNodeWithText(string(R.string.voice_switch_title)).assertIsEnabled()
        compose.onNodeWithText(string(R.string.voice_switch_needs_script)).assertIsDisplayed()
    }

    @Test
    fun theSwitchIsLiveWhenTheMicrophoneAndAScriptAreThere() {
        val calls = show(VoiceInputUiState(loading = false, scripts = listOf(option("a", "听写"))), granted = true)

        compose
            .onNodeWithText(string(R.string.voice_switch_title))
            .assertIsDisplayed()
            .assertIsEnabled()
            .performClick()
        assertEquals(listOf("enabled"), calls)
    }

    @Test
    fun thePermissionActionStaysClickableAtTheLargestFontInTheDarkTheme() {
        val calls =
            show(
                VoiceInputUiState(loading = false, scripts = listOf(option("a", "听写"))),
                granted = false,
                dark = true,
                fontScale = 2f,
            )

        compose.onNodeWithText(string(R.string.voice_permission_request)).assertIsDisplayed().performClick()
        assertEquals(listOf("request"), calls)
    }

    @Test
    fun theSourceListKeepsItsSelectionAtTheLargestFont() {
        val state =
            VoiceInputUiState(
                loading = false,
                enabled = true,
                selectedScriptId = "chosen",
                scripts = listOf(option("chosen", "听写乙"), option("other", "听写甲")),
            )
        show(state, dark = true, fontScale = 2f)

        compose.onNodeWithText("听写乙").assertIsDisplayed().assertIsSelected()
        compose.onNodeWithText("听写甲").assertIsDisplayed()
    }

    @Test
    fun theSwitchAndTheAddonRowAreLabelled() {
        val state =
            VoiceInputUiState(
                loading = false,
                enabled = true,
                selectedScriptId = "addon:com.example.voice",
                scripts =
                    listOf(
                        option("addon:com.example.voice", "听写扩展", isAddon = true, origin = VoiceSourceOrigin.Addon),
                    ),
            )
        val calls = show(state)

        compose.onNodeWithText(format(R.string.voice_addon_option, "听写扩展")).assertIsSelected()
        compose
            .onNodeWithText(
                format(
                    R.string.voice_script_summary,
                    string(R.string.voice_addon_origin),
                    string(R.string.voice_script_enabled),
                ),
            ).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.voice_switch_title)).assertIsDisplayed()
        assertEquals(emptyList<String>(), calls)
    }
}
