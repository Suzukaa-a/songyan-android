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
 * title and subtitle on screen, there is no voice row (it lives on the Scripts page), and the list
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
    fun voiceInputIsNotARowOfItsOwn() {
        showHome(fontScale = 1.0f)

        compose.onNodeWithText(string(R.string.voice_title)).assertDoesNotExist()
        compose.onNodeWithText(string(R.string.row_scripts_title)).performScrollTo().assertIsDisplayed()
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
            R.string.row_scripts_subtitle,
            R.string.row_appearance_subtitle,
        )) {
            compose.onNodeWithText(string(subtitle)).performScrollTo().assertIsDisplayed()
        }
    }
}
