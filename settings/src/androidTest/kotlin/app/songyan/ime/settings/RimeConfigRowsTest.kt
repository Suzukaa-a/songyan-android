package app.songyan.ime.settings

import android.content.Intent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import app.songyan.ime.settings.state.RimeConfigInfo
import app.songyan.ime.settings.ui.DictionaryImportUiState
import app.songyan.ime.settings.ui.RimeConfigUiState
import app.songyan.ime.settings.ui.SchemeImportUiState
import app.songyan.ime.settings.ui.screens.RIME_CONFIG_STATUS_TAG
import app.songyan.ime.settings.ui.screens.SchemesScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The Rime configuration rows on the 输入方案 page: where the files are, and applying them. */
class RimeConfigRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private var applied = 0
    private var noFilesApp = 0

    private fun show(
        state: RimeConfigUiState,
        intents: List<Intent> = emptyList(),
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
                    schemeImport = SchemeImportUiState.Idle,
                    schemeImportAvailable = true,
                    onImportScheme = {},
                    onDismissSchemeImport = {},
                    onCancelImport = {},
                    onRetryImport = {},
                    onUseScheme = {},
                    onBack = null,
                    rimeConfig = state,
                    rimeConfigIntents = { intents },
                    onRimeConfigNoFilesApp = { noFilesApp++ },
                    onApplyRimeConfig = { applied++ },
                )
            }
        }
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(str(R.string.rime_config_apply)))
    }

    @Test
    fun theRowsSayWhereTheFilesAreAndApplyPendingChanges() {
        show(RimeConfigUiState(available = true, info = RimeConfigInfo(fileCount = 1, pending = true)))

        compose.onNodeWithText(str(R.string.rime_config_section)).assertExists()
        compose.onNodeWithText(str(R.string.rime_config_body)).assertExists()
        compose.onNodeWithTag(RIME_CONFIG_STATUS_TAG).assertExists()
        compose.onNodeWithText(str(R.string.rime_config_pending)).assertExists()
        compose.onNodeWithText(str(R.string.rime_config_open)).assertIsEnabled()
        compose.onNodeWithText(str(R.string.rime_config_apply)).assertIsEnabled().performClick()
        assertEquals(1, applied)
    }

    @Test
    fun nothingToApplyLeavesTheButtonOff() {
        show(RimeConfigUiState(available = true, info = RimeConfigInfo(fileCount = 2, pending = false)))

        compose.onNodeWithText(str(R.string.rime_config_apply)).assertIsNotEnabled()
        val inUse =
            InstrumentationRegistryResources.plural(R.plurals.rime_config_in_use, 2)
        compose.onNodeWithText(inUse).assertExists()
    }

    @Test
    fun aRefusedFileIsNamedAndCanBeAppliedAgainOnceFixed() {
        show(
            RimeConfigUiState(
                available = true,
                info = RimeConfigInfo(fileCount = 1, pending = true, failure = "「default.custom.yaml」格式有误"),
            ),
        )

        compose.onNodeWithText("「default.custom.yaml」格式有误").assertExists()
        compose.onNodeWithText(str(R.string.rime_config_apply)).assertIsEnabled()
    }

    @Test
    fun whileApplyingNothingElseCanBeApplied() {
        show(RimeConfigUiState(available = true, info = RimeConfigInfo(fileCount = 1, pending = true), applying = true))

        compose.onNodeWithText(str(R.string.rime_config_applying)).assertExists()
        compose.onNodeWithText(str(R.string.rime_config_apply)).assertIsNotEnabled()
    }

    @Test
    fun withoutAFilesAppTheUserIsTold() {
        show(
            RimeConfigUiState(available = true, info = RimeConfigInfo(fileCount = 0, pending = false)),
            intents = listOf(Intent("app.songyan.ime.test.NO_SUCH_ACTION")),
        )

        compose.onNodeWithText(str(R.string.rime_config_empty)).assertExists()
        compose.onNodeWithText(str(R.string.rime_config_open)).performClick()
        assertEquals(1, noFilesApp)
    }
}

/** Plural strings in the device's own language. */
private object InstrumentationRegistryResources {
    fun plural(
        id: Int,
        count: Int,
    ): String =
        androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation()
            .targetContext.resources
            .getQuantityString(id, count, count)
}
