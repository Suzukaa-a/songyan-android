package app.songyan.ime.settings

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.settings.ui.SettingsUiState
import app.songyan.ime.settings.ui.screens.HomeScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home at a larger system font (1.3, the size the visual audit found clipping): every row keeps its
 * title and subtitle on screen, the voice row leaves room for its icon and both lines, and the list
 * keeps padding below the last row so the navigation bar cannot cut it at the end.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenLargeFontTest {
    @get:Rule
    val compose = createComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun string(id: Int): String = context.getString(id)

    private fun showHome(fontScale: Float) {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                CompositionLocalProvider(LocalDensity provides Density(1f, fontScale)) {
                    HomeScreen(
                        state = SettingsUiState(),
                        onOpen = {},
                        onRetryImeStatus = {},
                        onCancelImport = {},
                        onRetryImport = {},
                        onDismissDictionaryImport = {},
                        onDismissSchemeImport = {},
                    )
                }
            }
        }
    }

    @Test
    fun theVoiceInputRowStaysWholeAtThirteenTenthsFont() {
        showHome(fontScale = 1.3f)

        compose.onNodeWithText(string(R.string.row_voice_title)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.row_voice_subtitle)).assertIsDisplayed()
        // The list item merges its two lines, so the unmerged tree is what says each line has its
        // own laid-out bounds: both inside the row and stacked, none cut at this font size.
        val row = compose.onNodeWithText(string(R.string.row_voice_title)).fetchSemanticsNode()
        val title =
            compose.onNodeWithText(string(R.string.row_voice_title), useUnmergedTree = true).fetchSemanticsNode()
        val subtitle =
            compose.onNodeWithText(string(R.string.row_voice_subtitle), useUnmergedTree = true).fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode()
        assertTrue(
            "both lines must stay inside the row, title=" + title.boundsInRoot + " subtitle=" + subtitle.boundsInRoot,
            title.boundsInRoot.top >= row.boundsInRoot.top &&
                title.boundsInRoot.bottom <= row.boundsInRoot.bottom &&
                subtitle.boundsInRoot.top >= title.boundsInRoot.bottom &&
                subtitle.boundsInRoot.bottom <= row.boundsInRoot.bottom,
        )
        assertTrue(
            "the voice row must stay inside the window",
            row.boundsInRoot.bottom <= root.boundsInRoot.bottom,
        )
        // The lines start past the row's leading edge, which is the icon's own column: a larger
        // font wraps the label instead of pushing it over the 24 dp icon.
        assertTrue(
            "the text must keep clear of the leading icon, row=" + row.boundsInRoot + " title=" + title.boundsInRoot,
            title.boundsInRoot.left > row.boundsInRoot.left && subtitle.boundsInRoot.left > row.boundsInRoot.left,
        )
    }

    @Test
    fun theListLeavesRoomBelowTheLastRowAtThirteenTenthsFont() {
        showHome(fontScale = 1.3f)

        compose.onNodeWithText(string(R.string.row_about_title)).performScrollTo().assertIsDisplayed()
        val last = compose.onNodeWithText(string(R.string.row_about_title)).fetchSemanticsNode()
        val root = compose.onRoot().fetchSemanticsNode()
        val gap = root.boundsInRoot.bottom - last.boundsInRoot.bottom
        assertTrue(
            "the last row must not sit against the list bottom, gap was " + gap,
            gap >= with(compose.density) { 12.dp.toPx() },
        )
    }

    @Test
    fun everyRowStillShowsItsSubtitleAtThirteenTenthsFont() {
        showHome(fontScale = 1.3f)

        for (subtitle in listOf(
            R.string.row_voice_subtitle,
            R.string.row_scripts_subtitle,
            R.string.row_appearance_subtitle,
        )) {
            compose.onNodeWithText(string(subtitle)).performScrollTo().assertIsDisplayed()
        }
    }
}
