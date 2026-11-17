package app.songyan.ime.settings

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.layout.ApplicableLayouts
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.installPicker
import app.songyan.ime.settings.ui.components.ImportCard
import app.songyan.ime.settings.ui.components.ImportKind
import app.songyan.ime.settings.ui.components.ImportPhase
import app.songyan.ime.settings.ui.components.ImportStatusCard
import app.songyan.ime.settings.ui.components.InstallPickerUi
import app.songyan.ime.settings.ui.components.LocalInstallPicker
import app.songyan.ime.settings.ui.theme.SongyanTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** After a new package installed the card asks which keyboards to start with; the default is ticked. */
class InstallPickerCardTest {
    @get:Rule
    val compose = createComposeRule()

    private val letters = ('a'..'z').map { it.toString() }
    private val wubi =
        InstalledScheme(
            "wubi86",
            "五笔",
            setOf("qwerty26"),
            profile = SchemaKeyProfile(SchemaKeyProfile.Kind.SHAPE_CODE, letters + listOf(";", "'")),
        )
    private val toggled = mutableListOf<String>()
    private var names: ((ApplicableLayouts.Generated) -> String)? = null

    private fun show(checked: Set<String>?) {
        val picker = installPicker(listOf(wubi), listOf("wubi86"), LayoutRegistry.create(), checked)
        compose.setContent {
            SongyanTheme(dynamicColor = false) {
                CompositionLocalProvider(
                    LocalInstallPicker provides InstallPickerUi(picker, { toggled += it }, { names = it }),
                ) {
                    ImportStatusCard(
                        card = ImportCard(ImportKind.SCHEME, ImportPhase.SUCCEEDED, "安装完成", pickable = true),
                        onCancel = {},
                        onRetry = {},
                        onDismiss = {},
                    )
                }
            }
        }
    }

    @Test
    fun theDefaultKeyboardIsTickedAndConfirmingHandsOverTheGeneratedNames() {
        show(null)
        compose.onNodeWithText(str(R.string.install_picker_title)).assertIsDisplayed()
        compose.onNodeWithText("五笔").assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layout_name_keys26)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.layout_name_26)).assertIsDisplayed()
        compose.onNodeWithText(str(R.string.keyboards_missing_codes, "; '"), substring = true).assertIsDisplayed()

        compose.onNodeWithText(str(R.string.layout_name_26)).performClick()
        assertEquals(listOf("wubi86:qwerty26#qwerty26"), toggled)

        compose.onNodeWithText(str(R.string.install_picker_start)).assertIsEnabled().performClick()
        assertEquals("keys", names?.invoke(ApplicableLayouts.Generated.KEYS26)?.let { "keys" })
    }

    @Test
    fun withNothingTickedTheButtonWaits() {
        show(emptySet())
        compose.onNodeWithText(str(R.string.install_picker_start)).assertIsDisplayed()
    }
}
