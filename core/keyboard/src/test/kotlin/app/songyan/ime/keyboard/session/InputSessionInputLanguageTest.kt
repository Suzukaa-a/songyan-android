package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.engine.EngineOptionState
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.engine.InputLanguageProfile
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cached input language: derived when the schema or a declared script switch changes, never per
 * key, and only from switches that really declare a script conversion.
 */
class InputSessionInputLanguageTest {
    private class ScriptEngine(
        var schema: String = BUILTIN,
        var alphabet: String = LATIN,
        var states: List<EngineOptionState> = emptyList(),
    ) : EngineBackend {
        override val kind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override var spellingDemand: (() -> Boolean)? = null
        var alphabetQueries = 0
        var failOptions = false

        override val availableSchemas: List<KeyboardSchemaDescriptor>
            get() = listOf(KeyboardSchemaDescriptor(schema, schema, alphabet))

        override suspend fun setup(
            sharedDirectory: String,
            userDirectory: String,
        ) = Unit

        override suspend fun selectSchema(schemaID: String) {
            schema = schemaID
        }

        override suspend fun processKey(keyValue: String): EngineSnapshot = currentSnapshot()

        override suspend fun selectCandidate(index: Int): EngineSnapshot = currentSnapshot()

        override suspend fun nextPage(): EngineSnapshot = currentSnapshot()

        override suspend fun previousPage(): EngineSnapshot = currentSnapshot()

        override fun currentSnapshot(): EngineSnapshot = EngineSnapshot(schemaID = schema)

        override suspend fun shutdown() = Unit

        override val statusSummary: String get() = "script"

        override suspend fun spellerAlphabet(schemaID: String): String? {
            alphabetQueries++
            return alphabet.takeIf { schemaID == schema }
        }

        override suspend fun optionStates(): List<EngineOptionState> {
            check(!failOptions) { "options unavailable" }
            return states
        }
    }

    private fun switch(
        name: String,
        config: String?,
        active: Boolean,
        kind: EngineOptionKind = EngineOptionKind.TRADITIONAL,
    ) = EngineOptionState(
        EngineOptionDescriptor(BUILTIN, BUILTIN, name, kind, active, scriptConfig = config),
        active,
    )

    @Test
    fun theBuiltInSchemeKeepsTheLegacyProfileAndReadsTheAlphabetOnce() =
        runTest {
            val engine = ScriptEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            assertEquals(LEGACY, session.inputLanguage)
            session.reloadOptions()
            assertEquals(LEGACY, session.inputLanguage)
            session.reloadOptions()
            session.reloadOptions()
            assertEquals(1, engine.alphabetQueries)
        }

    @Test
    fun aDeclaredTraditionalConversionMakesItTraditionalWithoutRereadingTheAlphabet() =
        runTest {
            val engine = ScriptEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            session.reloadOptions()
            engine.states = listOf(switch("s2t", "s2t", true))
            session.reloadOptions()
            assertEquals(HANT, session.inputLanguage)
            assertEquals(1, engine.alphabetQueries)
        }

    @Test
    fun aFailedOptionsReadDropsTheConversionTheOldOptionsDeclared() =
        runTest {
            val engine = ScriptEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            engine.states = listOf(switch("s2t", "s2t", true))
            session.reloadOptions()
            assertEquals(HANT, session.inputLanguage)
            engine.failOptions = true
            session.reloadOptions()
            assertEquals(LEGACY, session.inputLanguage)
        }

    @Test
    fun anActiveEmojiConversionIsIgnoredNextToADeclaredTraditionalOne() =
        runTest {
            // Emoji candidates are a simplifier too, and schemes such as 雾凇 keep emoji and s2t on together.
            val engine = ScriptEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            engine.states = listOf(switch("emoji", "emoji", true, EngineOptionKind.EMOJI), switch("s2t", "s2t", true))
            session.reloadOptions()
            assertEquals(HANT, session.inputLanguage)
            engine.states = listOf(switch("emoji", "emoji", true, EngineOptionKind.EMOJI))
            session.reloadOptions()
            assertEquals(LEGACY, session.inputLanguage)
        }

    @Test
    fun declaredTraditionsThatAgreeKeepTheTraditionalProfile() =
        runTest {
            val engine = ScriptEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            engine.states = listOf(switch("s2t", "s2t", true), switch("s2twp", "s2twp", true))
            session.reloadOptions()
            assertEquals(HANT, session.inputLanguage)
            engine.states = listOf(switch("s2t", "s2t", true), switch("s2t_dup", "s2t", true))
            session.reloadOptions()
            assertEquals(HANT, session.inputLanguage)
        }

    @Test
    fun contradictoryOrUnprovenScriptSwitchesFallBackToTheLegacyProfile() =
        runTest {
            val engine = ScriptEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            // A known traditional and a known simplified declaration have no proven winner.
            engine.states =
                listOf(
                    switch("s2t", "s2t", true),
                    switch("t2s", "t2s", true, EngineOptionKind.SIMPLIFIED),
                )
            session.reloadOptions()
            assertEquals(LEGACY, session.inputLanguage)
            engine.states =
                listOf(
                    switch("s2t", "s2t", true),
                    switch("hk2s", "hk2s", true, EngineOptionKind.SIMPLIFIED),
                )
            session.reloadOptions()
            assertEquals(LEGACY, session.inputLanguage)
            // A conversion the script rules do not know is not a script state and cannot vote.
            engine.states = listOf(switch("s2t", "s2t", true), switch("t2hkf", "t2hkf", true))
            session.reloadOptions()
            assertEquals(HANT, session.inputLanguage)
            // An active switch whose declared config is unknown proves nothing.
            engine.states = listOf(switch("traditionalize", null, true))
            session.reloadOptions()
            assertEquals(LEGACY, session.inputLanguage)
            engine.states = listOf(switch("s2t_unknown", "s2x", true))
            session.reloadOptions()
            assertEquals(LEGACY, session.inputLanguage)
        }

    @Test
    fun theEnglishModeKeepsTheSchemasInputLanguage() =
        runTest {
            val engine = ScriptEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            session.selectSchema("kagiroi")
            assertEquals(
                InputLanguageProfile(InputLanguage.JA, InputSchemeFamily.JAPANESE),
                session.inputLanguage,
            )
            session.setMode(InputMode.ENGLISH)
            assertEquals(
                InputLanguageProfile(InputLanguage.JA, InputSchemeFamily.JAPANESE),
                session.inputLanguage,
            )
        }

    @Test
    fun switchingSchemasRecomputesOncePerSourceAndCachesEachAlphabet() =
        runTest {
            val engine = ScriptEngine()
            val session = InputSession(engine, KeyboardLayoutKind.QWERTY26)
            session.reloadOptions()
            session.selectSchema("kagiroi")
            assertEquals(
                InputLanguageProfile(InputLanguage.JA, InputSchemeFamily.JAPANESE),
                session.inputLanguage,
            )
            assertEquals(2, engine.alphabetQueries)
            session.selectSchema(BUILTIN)
            assertEquals(LEGACY, session.inputLanguage)
            assertEquals(2, engine.alphabetQueries)
        }

    private companion object {
        const val BUILTIN = "songyan_quanpin"
        const val LATIN = "zyxwvutsrqponmlkjihgfedcba"
        val LEGACY = InputLanguageProfile(InputLanguage.ZH_HANS, InputSchemeFamily.GENERIC)
        val HANT = InputLanguageProfile(InputLanguage.ZH_HANT, InputSchemeFamily.GENERIC)
    }
}
