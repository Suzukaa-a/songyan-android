package app.songyan.ime.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import app.songyan.ime.settings.ui.components.RadioRow
import app.songyan.ime.settings.ui.components.SectionHeader
import app.songyan.ime.settings.ui.components.SettingsRow
import app.songyan.ime.settings.ui.components.SwitchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Material list rows, headings for groups, and touch targets of at least 48 dp. */
class SettingsComponentsTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun aSectionHeaderIsAnnouncedAsAHeading() {
        compose.setContent { MaterialTheme { SectionHeader("键盘") } }
        compose.onNodeWithText("键盘").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
    }

    @Test
    fun everyKindOfClickableRowIsAtLeast48DpTall() {
        compose.setContent {
            MaterialTheme {
                Column {
                    SettingsRow(title = "navigate", subtitle = null, onClick = {})
                    RadioRow(title = "radio", selected = false, onClick = {})
                    SwitchRow(title = "switch", subtitle = null, checked = false, onCheckedChange = {})
                }
            }
        }
        for (title in listOf("navigate", "radio", "switch")) {
            compose.onNode(hasClickAction() and hasText(title)).assertHeightIsAtLeast(48.dp)
        }
    }

    @Test
    fun aSwitchRowFlipsFromAnywhereOnTheRow() {
        var checked = false
        compose.setContent {
            MaterialTheme {
                SwitchRow(title = "开关", subtitle = "说明", checked = checked, onCheckedChange = { checked = it })
            }
        }
        compose.onNodeWithText("说明").performClick()
        assertTrue(checked)
    }

    @Test
    fun aRadioRowReportsItsSelectionOnce() {
        compose.setContent {
            MaterialTheme {
                Column {
                    RadioRow(title = "一", selected = true, onClick = {})
                    RadioRow(title = "二", selected = false, onClick = {})
                }
            }
        }
        compose.onNode(hasClickAction() and hasText("一")).assertIsSelected()
        val selected =
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true)).fetchSemanticsNodes()
        assertEquals(1, selected.size)
    }
}
