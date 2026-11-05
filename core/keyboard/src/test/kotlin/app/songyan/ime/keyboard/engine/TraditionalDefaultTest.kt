package app.songyan.ime.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraditionalDefaultTest {
    @Test
    fun taiwanStartsWithTaiwanPhrases() {
        for (tag in listOf("zh-TW", "zh-Hant-TW")) {
            assertEquals(BuiltinScriptState.TAIWAN, TraditionalDefault.initialState(listOf(tag), false))
        }
    }

    @Test
    fun hongKongAndMacaoStartWithHongKongForms() {
        for (tag in listOf("zh-HK", "zh-MO", "zh-Hant-HK", "zh-Hant-MO")) {
            assertEquals(BuiltinScriptState.HONG_KONG, TraditionalDefault.initialState(listOf(tag), false))
        }
    }

    @Test
    fun otherTraditionalLocalesStartWithTraditionalForms() {
        for (tag in listOf("zh-Hant", "zh-Hant-US")) {
            assertEquals(BuiltinScriptState.TRADITIONAL, TraditionalDefault.initialState(listOf(tag), false))
        }
    }

    @Test
    fun simplifiedAndOtherLocalesKeepTheSimplifiedDefault() {
        for (tags in listOf(emptyList(), listOf("zh"), listOf("zh-CN"), listOf("ja-JP"), listOf("en-US"))) {
            assertEquals(BuiltinScriptState.SIMPLIFIED, TraditionalDefault.initialState(tags, false))
        }
    }

    @Test
    fun anExplicitSimplifiedScriptWinsOverTheRegion() {
        assertEquals(BuiltinScriptState.SIMPLIFIED, TraditionalDefault.initialState(listOf("zh-Hans-TW"), false))
    }

    @Test
    fun existingUserSettingsAreNeverReplacedByLocaleDefaults() {
        assertNull(TraditionalDefault.initialState(listOf("zh-TW"), true))
        assertNull(TraditionalDefault.initialState(listOf("ja-JP"), true))
    }
}
