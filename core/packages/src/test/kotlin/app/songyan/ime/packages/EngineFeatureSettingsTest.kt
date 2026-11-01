package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineFeatureSettingsTest {
    @Test
    fun defaultsMatchTheIosApp() {
        val settings = EngineFeatureSettings()
        assertTrue("typo correction is on by default", settings.typoCorrection)
        assertTrue("prediction is on once a database exists", settings.prediction)
        assertNull(settings.predictionDatabase)
        assertTrue(settings.fuzzyPinyin.isEmpty())
        assertTrue(settings.disabledLua.isEmpty())
        assertTrue(settings.grammar.isEmpty())
    }

    @Test
    fun aDamagedFileReadsAsDefaults() {
        assertEquals(EngineFeatureSettings(), EngineFeatureSettings.decode(null))
        assertEquals(EngineFeatureSettings(), EngineFeatureSettings.decode("{not json"))
        assertEquals(EngineFeatureSettings(), EngineFeatureSettings.decode("[]"))
    }

    @Test
    fun choicesSurviveARoundTrip() {
        val settings =
            EngineFeatureSettings(
                typoCorrection = false,
                predictionDatabase = EngineFeatureSettings.StoredFile("predict-1.db", 10, "ab", "my.db"),
                fuzzyPinyin = mapOf("quanpin" to listOf("z-zh")),
                disabledLua = mapOf("x" to listOf("lua_filter@*a")),
                grammarModels = listOf(EngineFeatureSettings.StoredFile("m.gram", 5, "cd")),
                grammar =
                    mapOf(
                        "quanpin" to EngineFeatureSettings.GrammarChoice("m.gram"),
                        "y" to EngineFeatureSettings.GrammarChoice(),
                    ),
            )
        val decoded = EngineFeatureSettings.decode(settings.encode())
        assertEquals(settings, decoded)
        assertTrue(decoded.grammar.getValue("y").isOff)
    }

    @Test
    fun unknownKeysAndPairsAreIgnored() {
        val decoded = EngineFeatureSettings.decode("""{"future":1,"fuzzyPinyin":{"a":["z-zh","nope"]}}""")
        assertEquals(setOf("z-zh"), decoded.fuzzyPairs("a"))
        assertTrue(decoded.fuzzyPairs("b").isEmpty())
    }
}
