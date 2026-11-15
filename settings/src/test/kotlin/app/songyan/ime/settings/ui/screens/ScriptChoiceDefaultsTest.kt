package app.songyan.ime.settings.ui.screens

import app.songyan.ime.script.ScriptSettingField
import app.songyan.ime.script.ScriptSettingKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptChoiceDefaultsTest {
    private val fields =
        listOf(
            ScriptSettingField("endpoint", ScriptSettingKind.URL, "Endpoint", secret = false),
            ScriptSettingField("mode", ScriptSettingKind.CHOICE, "Mode", secret = false, options = listOf("正式", "随意")),
        )

    @Test
    fun anUntouchedChoiceIsSentAsTheFirstOptionTheFormShowed() {
        assertEquals(mapOf("mode" to "正式"), withChoiceDefaults(fields, emptyMap()))
    }

    @Test
    fun aSavedChoiceIsKept() {
        // The saved value used to be overwritten by the default when it arrived after the form drew.
        assertEquals(mapOf("mode" to "随意"), withChoiceDefaults(fields, mapOf("mode" to "随意")))
    }

    @Test
    fun aValueTheScriptNoLongerListsFallsBackToTheFirstOption() {
        assertEquals(mapOf("mode" to "正式"), withChoiceDefaults(fields, mapOf("mode" to "旧选项")))
    }
}
