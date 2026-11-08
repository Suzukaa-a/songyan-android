package app.songyan.ime.settings

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import app.songyan.ime.settings.state.InMemorySettingsPreferences
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.ui.screens.KeyboardMenuScreen
import app.songyan.ime.settings.ui.theme.SongyanTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** The keyboard menu page: the running package's input methods, then each other package's under its name. */
class KeyboardMenuScreenTest {
    @get:Rule val compose = createComposeRule()
    private val preferences = InMemorySettingsPreferences()

    private val other =
        StoredSourceInfo(
            id = "scheme-kana",
            name = "Kana Pack",
            kind = StoredSourceKind.SCHEME,
            inUse = false,
            schemes = listOf(InstalledScheme("kana", "Kana"), InstalledScheme("romaji", "Romaji")),
        )

    private fun show() {
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                KeyboardMenuScreen(
                    schemes = listOf(InstalledScheme("quanpin", "Quanpin")),
                    inUseSchemeId = "quanpin",
                    layoutId = "qwerty26",
                    onBack = {},
                    preferences = preferences,
                    otherSources = listOf(other),
                )
            }
        }
    }

    private fun hidden() = runBlocking { preferences.menuHiddenSchemas.first() }

    @Test fun anotherPackageIsListedUnderItsNameWithSwitchesOn() {
        show()
        compose.onNodeWithText("Kana Pack").performScrollTo()
        compose.onNodeWithText("Kana").performScrollTo().assertIsOn()
        compose.onNodeWithText("Romaji").performScrollTo().assertIsOn()
    }

    @Test fun hidingAnInputMethodOfAnotherPackageStoresItsPlainId() {
        show()
        compose.onNodeWithText("Romaji").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(setOf("romaji"), hidden())
    }
}
