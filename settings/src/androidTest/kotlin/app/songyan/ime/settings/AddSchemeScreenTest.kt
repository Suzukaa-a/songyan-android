package app.songyan.ime.settings

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import app.songyan.ime.settings.state.RecommendedSchemes
import app.songyan.ime.settings.ui.screens.AddSchemeScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The page that adds an input scheme: the recommended catalogue, file, folder and link, and the way back. */
class AddSchemeScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val links = mutableListOf<String>()
    private val recommendedDownloads = mutableListOf<String>()
    private var started = 0

    private fun show(busy: Boolean = false) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                AddSchemeScreen(
                    importAvailable = true,
                    busy = busy,
                    onImportScheme = {},
                    onImportSchemeFolder = {},
                    onImportSchemeFromLink = { links += it },
                    onImportRecommendedScheme = { recommendedDownloads += it.id },
                    onStarted = { started++ },
                    onBack = {},
                )
            }
        }
    }

    /** The list is lazy: rows below the fold are only composed once scrolled to. */
    private fun scrollTo(text: String) {
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(text, substring = true))
    }

    @Test
    fun aRecommendedPackageIsDownloadedAfterConfirmingAndThePageGoesBack() {
        show()
        val bopomofo = RecommendedSchemes.catalog.first { it.id == "rime-bopomofo" }
        scrollTo(str(bopomofo.nameRes))
        compose.onNodeWithText(str(bopomofo.nameRes)).performClick()
        compose.onNodeWithText(str(R.string.schemes_recommended_confirm_title, str(bopomofo.nameRes))).assertExists()
        compose.onNodeWithText(str(R.string.schemes_recommended_homepage)).assertExists()
        assertTrue("nothing is downloaded before the user confirms", recommendedDownloads.isEmpty())
        assertEquals(0, started)
        compose.onNodeWithText(str(R.string.schemes_recommended_confirm)).performClick()
        assertEquals(listOf("rime-bopomofo"), recommendedDownloads)
        assertEquals("the list with the progress card comes back", 1, started)
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
    fun aLinkMustBeHttpsBeforeItCanBeDownloaded() {
        show()
        scrollTo(str(R.string.schemes_scheme_import_link))
        compose.onNodeWithText(str(R.string.schemes_scheme_import_link)).performClick()
        compose.onNodeWithText(str(R.string.schemes_scheme_link_confirm)).assertIsNotEnabled()

        compose.onNodeWithText(str(R.string.schemes_scheme_link_label)).performTextInput("http://example.com/p.zip")
        compose.onNodeWithText(str(R.string.schemes_scheme_link_invalid)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_scheme_link_confirm)).assertIsNotEnabled()
        assertEquals(0, started)
    }

    @Test
    fun anHttpsLinkIsHandedOverOnConfirmAndThePageGoesBack() {
        show()
        scrollTo(str(R.string.schemes_scheme_import_link))
        compose.onNodeWithText(str(R.string.schemes_scheme_import_link)).performClick()
        compose
            .onNodeWithText(str(R.string.schemes_scheme_link_label))
            .performTextInput("https://github.com/iDvel/rime-ice")
        compose.onNodeWithText(str(R.string.schemes_scheme_link_note)).assertExists()
        compose.onNodeWithText(str(R.string.schemes_scheme_link_confirm)).assertIsEnabled().performClick()
        assertEquals(listOf("https://github.com/iDvel/rime-ice"), links)
        assertEquals(1, started)
    }

    @Test
    fun folderAndFileImportsAreBothOffered() {
        show()
        scrollTo(str(R.string.schemes_scheme_import_file))
        compose.onNodeWithText(str(R.string.schemes_scheme_import_file)).assertIsEnabled()
        scrollTo(str(R.string.schemes_scheme_import_folder))
        compose.onNodeWithText(str(R.string.schemes_scheme_import_folder)).assertIsEnabled()
    }

    @Test
    fun whileAnotherImportRunsNothingNewCanStart() {
        show(busy = true)
        compose.onNodeWithText(str(R.string.schemes_add_busy)).assertExists()
        scrollTo(str(R.string.schemes_scheme_import_link))
        compose.onNodeWithText(str(R.string.schemes_scheme_import_file)).assertIsNotEnabled()
        compose.onNodeWithText(str(R.string.schemes_scheme_import_link)).assertIsNotEnabled()
    }
}
