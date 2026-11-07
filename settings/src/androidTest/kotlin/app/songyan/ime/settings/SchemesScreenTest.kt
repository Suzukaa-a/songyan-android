package app.songyan.ime.settings

import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.settings.state.ImportedSchemeInfo
import app.songyan.ime.settings.state.RecommendedSchemes
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.settings.ui.DictionaryImportUiState
import app.songyan.ime.settings.ui.EngineFeaturesUiState
import app.songyan.ime.settings.ui.SchemeImportUiState
import app.songyan.ime.settings.ui.StoredSourceUiState
import app.songyan.ime.settings.ui.components.GrammarModelActions
import app.songyan.ime.settings.ui.screens.GrammarModelCardInput
import app.songyan.ime.settings.ui.screens.SchemesScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** P4-1 / P4-2 on the 输入方案 page: statuses, update, undo, link import, recommended packages, shared files. */
class SchemesScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val builtin = StoredSourceInfo("songyan-builtin", "松烟内置", StoredSourceKind.BUILTIN, inUse = true)
    private val current = StoredSourceInfo("scheme-1", "雾凇拼音", StoredSourceKind.SCHEME, inUse = true, updatable = true)
    private val outdated =
        StoredSourceInfo(
            "scheme-2",
            "万象拼音",
            StoredSourceKind.SCHEME,
            inUse = true,
            status = StoredSourceStatus.UPDATE_AVAILABLE,
        )
    private val damaged =
        StoredSourceInfo(
            "scheme-3",
            "已损坏的输入方案",
            StoredSourceKind.SCHEME,
            inUse = false,
            status = StoredSourceStatus.DAMAGED,
        )

    private val opened = mutableListOf<String>()
    private val restored = mutableListOf<String>()
    private val links = mutableListOf<String>()
    private val recommendedDownloads = mutableListOf<String>()
    private var incomingConfirmed = false
    private var incomingDismissed = false
    private val learningAnswers = mutableListOf<Boolean>()
    private val downloads = mutableListOf<String>()
    private var grammarCancels = 0
    private var grammarRetries = 0
    private var featuresOpened = 0
    private var grammarDismissed = 0

    private fun show(
        sources: List<StoredSourceInfo> = emptyList(),
        sourceAction: StoredSourceUiState = StoredSourceUiState.Idle,
        incoming: Uri? = null,
        schemeImport: SchemeImportUiState = SchemeImportUiState.Idle,
        grammarState: EngineFeaturesUiState = EngineFeaturesUiState(),
    ) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                SchemesScreen(
                    schemes = emptyList(),
                    inUseSchemeId = null,
                    importedDictionary = null,
                    dictionaryImport = DictionaryImportUiState.Idle,
                    dictionaryImportAvailable = true,
                    onImportDictionaryFile = { _, _ -> },
                    onImportDictionaryFromUrl = { _, _ -> },
                    onRemoveImportedDictionary = {},
                    onDismissDictionaryImport = {},
                    schemeImport = schemeImport,
                    schemeImportAvailable = true,
                    onImportScheme = {},
                    onDismissSchemeImport = {},
                    onCancelImport = {},
                    onRetryImport = {},
                    onUseScheme = {},
                    onBack = null,
                    sources = sources,
                    sourceAction = sourceAction,
                    onOpenSource = { opened += it },
                    onDismissSourceAction = {},
                    onImportSchemeFromLink = { links += it },
                    onImportRecommendedScheme = { recommendedDownloads += it.id },
                    onRestoreSource = { restored += it },
                    incomingPackage = incoming,
                    onConfirmIncomingPackage = { incomingConfirmed = true },
                    onDismissIncomingPackage = { incomingDismissed = true },
                    onAnswerSchemeLearning = { learningAnswers += it },
                    grammarModel =
                        GrammarModelCardInput(
                            state = grammarState,
                            actions =
                                GrammarModelActions(
                                    onDownload = { downloads += it },
                                    onPickFile = {},
                                    onCancel = { grammarCancels++ },
                                    onRetry = { grammarRetries++ },
                                    onDismissFailure = {},
                                    onOpenFeatures = { featuresOpened++ },
                                    onDismiss = { grammarDismissed++ },
                                ),
                            onImportFile = {},
                        ),
                )
            }
        }
    }

    /** The list is lazy: rows below the fold are only composed once scrolled to. */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    /** Nodes whose whole text is [text], so a button "使用" is not confused with the status "正在使用". */
    private fun count(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().size

    @Test
    fun aPackageRowShowsItsStatusAndOpensItsPage() {
        show(listOf(builtin, outdated))
        // The name is also a recommended package, so scroll to the status that only the row has.
        scrollTo(str(R.string.schemes_status_update_available))
        compose.onNodeWithText(str(R.string.schemes_status_update_available), substring = true).assertExists()
        compose.onNodeWithText(outdated.name).performClick()
        assertEquals(listOf("scheme-2"), opened)
    }

    @Test
    fun theListOffersNoActionsOnAPackage() {
        show(listOf(builtin, current, damaged))
        scrollTo(damaged.name)
        assertEquals(0, count(str(R.string.schemes_source_use)))
        assertEquals(0, count(str(R.string.schemes_source_update)))
        assertEquals(0, count(str(R.string.schemes_source_delete)))
    }

    @Test
    fun aDamagedPackageIsMarkedInTheList() {
        show(listOf(builtin, damaged))
        scrollTo(damaged.name)
        compose.onNodeWithText(str(R.string.schemes_status_damaged), substring = true).assertExists()
    }

    @Test
    fun aDeletionOffersUndoThatRestoresThePackage() {
        show(listOf(builtin), StoredSourceUiState.Deleted("scheme-1", "雾凇拼音"))
        compose.onNodeWithText(str(R.string.schemes_source_deleted, "雾凇拼音")).assertExists()
        compose.onNodeWithText(str(R.string.schemes_source_undo)).performClick()
        compose.waitForIdle()
        assertEquals(listOf("scheme-1"), restored)
    }

    @Test
    fun aLinkMustBeHttpsBeforeItCanBeDownloaded() {
        show()
        scrollTo(str(R.string.schemes_scheme_import_link))
        compose.onNodeWithText(str(R.string.schemes_scheme_import_link)).performClick()
        compose.onNodeWithText(str(R.string.schemes_scheme_link_confirm)).assertIsNotEnabled()

        compose.onNodeWithText(str(R.string.schemes_scheme_link_label)).performTextInput("http://example.com/p.zip")
        compose.onNodeWithText(str(R.string.schemes_scheme_link_invalid)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_scheme_link_confirm)).assertIsNotEnabled()
    }

    @Test
    fun anHttpsLinkIsHandedOverOnConfirm() {
        show()
        scrollTo(str(R.string.schemes_scheme_import_link))
        compose.onNodeWithText(str(R.string.schemes_scheme_import_link)).performClick()
        compose
            .onNodeWithText(
                str(R.string.schemes_scheme_link_label),
            ).performTextInput("https://github.com/iDvel/rime-ice")
        compose.onNodeWithText(str(R.string.schemes_scheme_link_note)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_scheme_link_confirm)).assertIsEnabled().performClick()
        assertEquals(listOf("https://github.com/iDvel/rime-ice"), links)
    }

    @Test
    fun aRecommendedPackageIsDownloadedFromItsPinnedArchivesAfterConfirming() {
        show()
        val bopomofo = RecommendedSchemes.catalog.first { it.id == "rime-bopomofo" }
        scrollTo(str(bopomofo.nameRes))
        compose.onNodeWithText(str(bopomofo.nameRes)).performClick()
        compose.onNodeWithText(str(R.string.schemes_recommended_confirm_title, str(bopomofo.nameRes))).assertExists()
        compose.onNodeWithText(str(R.string.schemes_recommended_homepage)).assertExists()
        assertTrue("nothing is downloaded before the user confirms", recommendedDownloads.isEmpty())
        compose.onNodeWithText(str(R.string.schemes_recommended_confirm)).performClick()
        assertEquals(listOf("rime-bopomofo"), recommendedDownloads)
    }

    @Test
    fun everyRecommendedPackageIsListedWithItsLicenceAndUpstreamPage() {
        show()
        RecommendedSchemes.catalog.forEach {
            scrollTo(str(it.nameRes))
            compose.onNodeWithText(str(it.nameRes)).assertExists()
            compose.onNodeWithText(str(it.detailRes)).assertExists()
            val licence = str(R.string.schemes_recommended_license, it.licenses.joinToString(" · "))
            compose.onNode(hasText(licence) and hasText(str(it.nameRes))).assertExists()
        }
    }

    @Test
    fun aPackageSharedFromAnotherAppIsOnlyImportedAfterAsking() {
        show(incoming = Uri.parse("content://example/package.zip"))
        compose.onNodeWithText(str(R.string.schemes_incoming_title)).assertExists()
        assertTrue(!incomingConfirmed)
        compose.onNodeWithText(str(R.string.schemes_incoming_confirm)).performClick()
        assertTrue(incomingConfirmed)
    }

    @Test
    fun decliningASharedPackageImportsNothing() {
        show(incoming = Uri.parse("content://example/package.zip"))
        compose.onNodeWithText(str(R.string.schemes_dictionary_cancel)).performClick()
        assertTrue(incomingDismissed)
        assertTrue(!incomingConfirmed)
    }

    @Test
    fun folderAndFileImportsAreBothOffered() {
        show()
        scrollTo(str(R.string.schemes_scheme_import_file))
        compose.onNodeWithText(str(R.string.schemes_scheme_import_file)).assertIsEnabled()
        scrollTo(str(R.string.schemes_scheme_import_folder))
        compose.onNodeWithText(str(R.string.schemes_scheme_import_folder)).assertIsEnabled()
    }

    private fun imported(inUse: Boolean) =
        SchemeImportUiState.Succeeded(
            ImportedSchemeInfo("雾凇拼音", listOf("rime_ice"), inUse, sourceId = "scheme-1", learningDictionaries = 2),
        )

    @Test
    fun aPackageWithLearnedWordsAsksBeforeMergingThem() {
        show(schemeImport = imported(inUse = true))
        val question =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext
                .resources
                .getQuantityString(R.plurals.schemes_scheme_learning_question, 2, 2)
        compose.onNodeWithText(question).assertExists()
        compose.onNodeWithText(str(R.string.schemes_scheme_learning_merge)).performClick()
        assertEquals(listOf(true), learningAnswers)
    }

    @Test
    fun aPackageNotInUseOffersToUseAndMerge() {
        show(schemeImport = imported(inUse = false))
        assertEquals(0, count(str(R.string.schemes_scheme_learning_merge)))
        compose.onNodeWithText(str(R.string.schemes_scheme_learning_use_and_merge)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_scheme_learning_decline)).performClick()
        assertEquals(listOf(false), learningAnswers)
    }

    @Test
    fun anImportWithoutLearnedWordsAsksNothing() {
        show(schemeImport = SchemeImportUiState.Succeeded(ImportedSchemeInfo("雾凇拼音", listOf("rime_ice"), true)))
        assertEquals(0, count(str(R.string.schemes_scheme_learning_decline)))
        compose.onNodeWithText(str(R.string.action_dismiss)).assertExists()
    }

    @Test
    fun theMergedCountIsShownOnceDone() {
        show(schemeImport = SchemeImportUiState.LearningMerged("Merged 2 dictionaries with 5 entries."))
        compose.onNodeWithText("Merged 2 dictionaries with 5 entries.").assertExists()
        compose.onNodeWithText(str(R.string.action_dismiss)).assertExists()
    }

    private fun imported(vararg models: String) =
        SchemeImportUiState.Succeeded(
            ImportedSchemeInfo("万象拼音", listOf("wanxiang"), true, requiredModels = models.toList()),
        )

    @Test
    fun aPackageThatNeedsAKnownModelOffersASizedDownloadFromItsSuggestedLink() {
        show(schemeImport = imported("wanxiang-lts-zh-hans.gram"))
        compose.onNodeWithText(str(R.string.schemes_grammar_needed)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_grammar_download, 420)).performClick()
        assertEquals(
            listOf("https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/wanxiang-lts-zh-hans.gram"),
            downloads,
        )
        compose.onNodeWithText(str(R.string.schemes_grammar_from_file)).assertExists()
    }

    @Test
    fun aModelWithNoKnownLinkOnlyOffersTheFileImport() {
        show(schemeImport = imported("private-model.gram"))
        compose.onNodeWithText(str(R.string.schemes_grammar_needed)).assertExists()
        compose.onAllNodes(hasText(str(R.string.schemes_grammar_download, 420))).assertCountEquals(0)
        compose.onNodeWithText(str(R.string.schemes_grammar_from_file)).assertExists()
    }

    @Test
    fun aPackageThatNeedsNoModelShowsNoCard() {
        show(schemeImport = imported())
        compose.onAllNodes(hasText(str(R.string.schemes_grammar_needed))).assertCountEquals(0)
    }

    @Test
    fun aDownloadInFlightShowsProgressAndCanBeStopped() {
        show(
            schemeImport = imported("wanxiang-lts-zh-hans.gram"),
            grammarState = EngineFeaturesUiState(saving = true, cancellable = true),
        )
        compose.onNodeWithText(str(R.string.features_downloading)).assertExists()
        compose.onNodeWithText(str(R.string.action_cancel)).performClick()
        assertEquals(1, grammarCancels)
    }

    @Test
    fun aFailedDownloadSaysWhyAndOffersARetry() {
        show(
            schemeImport = imported("wanxiang-lts-zh-hans.gram"),
            grammarState = EngineFeaturesUiState(failed = true, failure = "没有网络。", retryable = true),
        )
        compose.onNodeWithText("没有网络。").assertExists()
        compose.onNodeWithText(str(R.string.action_retry)).performClick()
        assertEquals(1, grammarRetries)
    }

    @Test
    fun aStoredModelPointsToThePageWhereItIsChosen() {
        show(
            schemeImport = imported("wanxiang-lts-zh-hans.gram"),
            grammarState = EngineFeaturesUiState(grammarModelImported = true),
        )
        compose.onNodeWithText(str(R.string.schemes_grammar_imported)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_grammar_choose)).performClick()
        assertEquals(1, featuresOpened)
    }

    @Test
    fun closingTheCardRemovesIt() {
        show(schemeImport = imported("wanxiang-lts-zh-hans.gram"))
        compose.onNodeWithText(str(R.string.schemes_grammar_later)).performClick()
        compose.onAllNodes(hasText(str(R.string.schemes_grammar_needed))).assertCountEquals(0)
        assertEquals(1, grammarDismissed)
    }
}
