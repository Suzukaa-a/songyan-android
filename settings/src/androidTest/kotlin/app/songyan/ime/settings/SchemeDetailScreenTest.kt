package app.songyan.ime.settings

import android.app.Application
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.ImeStatus
import app.songyan.ime.settings.state.ImportedSchemeInfo
import app.songyan.ime.settings.state.InMemorySettingsAppStateSource
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.SettingsAppState
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.settings.state.UnavailableDictionaryImportGateway
import app.songyan.ime.settings.state.UnavailableSchemeImportGateway
import app.songyan.ime.settings.ui.DictionaryImportUiState
import app.songyan.ime.settings.ui.SchemeImportUiState
import app.songyan.ime.settings.ui.SettingsNavHost
import app.songyan.ime.settings.ui.SettingsViewModel
import app.songyan.ime.settings.ui.StoredSourceUiState
import app.songyan.ime.settings.ui.components.ImportKind
import app.songyan.ime.settings.ui.screens.SchemeDetailScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The page of one stored input scheme: status, input methods, update, stop using and delete. */
class SchemeDetailScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val builtin =
        StoredSourceInfo(
            "songyan-builtin",
            "松烟内置",
            StoredSourceKind.BUILTIN,
            inUse = false,
            schemes = listOf(InstalledScheme("songyan_quanpin", "全拼")),
        )
    private val ice =
        StoredSourceInfo(
            "scheme-1",
            "雾凇拼音",
            StoredSourceKind.SCHEME,
            inUse = true,
            updatable = true,
            originHost = "github.com",
            schemes =
                listOf(
                    InstalledScheme("rime_ice", "雾凇拼音"),
                    InstalledScheme("double_pinyin", "自然码双拼"),
                ),
            inUseSchemeId = "double_pinyin",
        )
    private val other =
        ice.copy(
            id = "scheme-2",
            name = "万象拼音",
            inUse = false,
            updatable = false,
            originHost = null,
            inUseSchemeId = null,
        )

    private val used = mutableListOf<String>()
    private val updated = mutableListOf<String>()
    private val deleted = mutableListOf<String>()
    private val cancelled = mutableListOf<ImportKind>()
    private val learningAnswers = mutableListOf<Boolean>()

    private fun show(
        id: String,
        sources: List<StoredSourceInfo>,
        schemeImport: SchemeImportUiState = SchemeImportUiState.Idle,
        sourceAction: StoredSourceUiState = StoredSourceUiState.Idle,
    ) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                SchemeDetailScreen(
                    sourceId = id,
                    sources = sources,
                    sourceAction = sourceAction,
                    schemeImport = schemeImport,
                    dictionaryImport = DictionaryImportUiState.Idle,
                    onUse = { used += it },
                    onUpdate = { updated += it },
                    onDelete = { deleted += it },
                    onDismissSourceAction = {},
                    onCancelImport = { cancelled += it },
                    onRetryImport = {},
                    onDismissSchemeImport = {},
                    onAnswerSchemeLearning = { learningAnswers += it },
                    onBack = null,
                )
            }
        }
    }

    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    }

    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun tappingAPackageInTheListOpensItsPage() {
        val state = SettingsAppState(engine = EngineStatus.Ready, sources = listOf(builtin, ice))
        val model =
            SettingsViewModel(
                application = ApplicationProvider.getApplicationContext<Application>(),
                appStateSource = InMemorySettingsAppStateSource(state),
                imeStatusSource = { ImeStatus(enabled = true, selected = true) },
                preferences = InMemorySettingsPreferences(),
                dictionaryGateway = UnavailableDictionaryImportGateway(),
                schemeGateway = UnavailableSchemeImportGateway(),
            )
        compose.setContent {
            SongyanTheme(dynamicColor = false) { SettingsNavHost(viewModel = model, versionName = "1") }
        }
        compose.onNodeWithText(str(R.string.row_schemes_title)).performClick()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("雾凇拼音"))
        compose.onNodeWithText("雾凇拼音").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(str(R.string.schemes_detail_status_section)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_source_update)).assertExists()
    }

    @Test
    fun aPackageInUseSaysSoAndOffersNoUseButton() {
        show("scheme-1", listOf(builtin, ice))
        compose.onNodeWithText(str(R.string.schemes_detail_status_section)).assertExists()
        assertEquals(0, count(str(R.string.schemes_source_use)))
        assertEquals(0, count(str(R.string.schemes_source_use_new)))
    }

    @Test
    fun aPackageNotInUseCanBeUsed() {
        show("scheme-2", listOf(builtin, ice.copy(inUse = false), other))
        compose.onNodeWithText(str(R.string.schemes_status_not_in_use)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_source_use)).performClick()
        assertEquals(listOf("scheme-2"), used)
    }

    @Test
    fun aPackageWithNewerFilesOffersTheNewVersion() {
        show("scheme-1", listOf(builtin, ice.copy(status = StoredSourceStatus.UPDATE_AVAILABLE)))
        compose
            .onNodeWithText(
                str(R.string.schemes_status_in_use) + " · " + str(R.string.schemes_status_update_available),
            ).assertExists()
        compose.onNodeWithText(str(R.string.schemes_source_use_new)).performClick()
        assertEquals(listOf("scheme-1"), used)
    }

    @Test
    fun theInputMethodsAreListedAndTheOneInUseIsMarked() {
        show("scheme-1", listOf(builtin, ice))
        scrollTo("自然码双拼")
        compose.onNodeWithText("自然码双拼").assertExists()
        // The status line and the marked input method.
        assertEquals(2, count(str(R.string.schemes_status_in_use)))
    }

    @Test
    fun aPackageFromALinkNamesItAndUpdatesInOneTap() {
        show("scheme-1", listOf(builtin, ice))
        scrollTo(str(R.string.schemes_source_update))
        compose.onNodeWithText(str(R.string.schemes_detail_from_link, "github.com")).assertExists()
        compose.onNodeWithText(str(R.string.schemes_source_update)).performClick()
        assertEquals(listOf("scheme-1"), updated)
    }

    @Test
    fun aPackageImportedFromAFileSaysToImportItAgain() {
        show("scheme-2", listOf(builtin, other))
        scrollTo(str(R.string.schemes_detail_reimport_note))
        compose.onNodeWithText(str(R.string.schemes_detail_from_file)).assertExists()
        assertEquals(0, count(str(R.string.schemes_source_update)))
    }

    @Test
    fun anUpdateInFlightShowsProgressAndCanBeCancelled() {
        show("scheme-1", listOf(builtin, ice), schemeImport = SchemeImportUiState.Running())
        compose.onNodeWithText(str(R.string.schemes_scheme_importing)).assertExists()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        assertEquals(listOf(ImportKind.SCHEME), cancelled)
    }

    @Test
    fun theLearnedWordsOfThisPackageAreOfferedForMerging() {
        show(
            "scheme-1",
            listOf(builtin, ice),
            schemeImport =
                SchemeImportUiState.Succeeded(
                    ImportedSchemeInfo(
                        "雾凇拼音",
                        listOf("rime_ice"),
                        true,
                        sourceId = "scheme-1",
                        learningDictionaries = 2,
                    ),
                ),
        )
        compose.onNodeWithText(str(R.string.schemes_scheme_learning_merge)).performClick()
        assertEquals(listOf(true), learningAnswers)
    }

    @Test
    fun theResultOfAnotherPackagesImportIsNotShownHere() {
        show(
            "scheme-1",
            listOf(builtin, ice, other),
            schemeImport =
                SchemeImportUiState.Succeeded(
                    ImportedSchemeInfo(
                        "万象拼音",
                        listOf("wanxiang"),
                        false,
                        sourceId = "scheme-2",
                        learningDictionaries = 1,
                    ),
                ),
        )
        assertEquals(0, count(str(R.string.schemes_scheme_learning_merge)))
        assertEquals(0, count(str(R.string.schemes_scheme_learning_use_and_merge)))
    }

    @Test
    fun aPackageInUseCanBeStoppedWhichSwitchesToTheBuiltInOne() {
        show("scheme-1", listOf(builtin, ice))
        scrollTo(str(R.string.schemes_detail_stop_using))
        compose.onNodeWithText(str(R.string.schemes_detail_stop_using_note, "松烟内置")).assertExists()
        compose.onNodeWithText(str(R.string.schemes_detail_stop_using)).performClick()
        assertEquals(listOf("songyan-builtin"), used)
    }

    @Test
    fun deletingAsksFirstAndOnlyThenDeletes() {
        show("scheme-2", listOf(builtin, other))
        scrollTo(str(R.string.schemes_source_delete))
        compose.onNodeWithText(str(R.string.schemes_source_delete)).performClick()
        compose.onNodeWithText(str(R.string.schemes_source_delete_body)).assertExists()
        assertTrue("nothing is deleted before the user confirms", deleted.isEmpty())
        // The dialog's confirm button carries the same label as the button on the page.
        compose.onNode(hasText(str(R.string.schemes_source_delete)) and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("scheme-2"), deleted)
    }

    @Test
    fun cancellingTheConfirmationDeletesNothing() {
        show("scheme-2", listOf(builtin, other))
        scrollTo(str(R.string.schemes_source_delete))
        compose.onNodeWithText(str(R.string.schemes_source_delete)).performClick()
        compose.onNodeWithText(str(R.string.schemes_dictionary_cancel)).performClick()
        assertTrue(deleted.isEmpty())
    }

    @Test
    fun theBuiltInPackageSaysWhyItCannotBeDeleted() {
        show("songyan-builtin", listOf(builtin, ice))
        scrollTo(str(R.string.schemes_detail_builtin_note))
        assertEquals(0, count(str(R.string.schemes_source_delete)))
    }

    @Test
    fun aDamagedPackageCanOnlyBeDeleted() {
        val damaged =
            StoredSourceInfo(
                "scheme-3",
                "已损坏的输入方案",
                StoredSourceKind.SCHEME,
                inUse = false,
                status = StoredSourceStatus.DAMAGED,
            )
        show("scheme-3", listOf(builtin, damaged))
        compose.onNodeWithText(str(R.string.schemes_status_damaged)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_source_damaged_note)).assertExists()
        assertEquals(0, count(str(R.string.schemes_source_use)))
        assertEquals(0, count(str(R.string.schemes_source_update)))
        scrollTo(str(R.string.schemes_source_delete))
        compose.onNodeWithText(str(R.string.schemes_source_delete)).assertExists()
    }

    @Test
    fun actionsAreDisabledWhileAnotherRequestRuns() {
        show("scheme-2", listOf(builtin, other), sourceAction = StoredSourceUiState.Running("scheme-1"))
        compose.onNodeWithText(str(R.string.schemes_source_use)).performClick()
        assertTrue(used.isEmpty())
    }

    @Test
    fun aMissingPackageSaysSo() {
        show("gone", listOf(builtin))
        compose.onNodeWithText(str(R.string.schemes_detail_not_found)).assertExists()
    }
}
