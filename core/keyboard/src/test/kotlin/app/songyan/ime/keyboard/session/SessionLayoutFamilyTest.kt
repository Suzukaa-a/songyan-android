package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.engine.EngineBackendKind
import app.songyan.ime.keyboard.engine.EngineOptionState
import app.songyan.ime.keyboard.engine.EngineSnapshot
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A saved layout the running scheme's family cannot use falls back to one it can, instead of
 * refusing the switch; the same pair the family allows passes through unchanged.
 */
class SessionLayoutFamilyTest {
    private class QuietEngine(
        var schema: String,
        private val alphabets: Map<String, String> = mapOf(schema to LATIN_ALPHABET),
    ) : EngineBackend {
        override val kind = EngineBackendKind.DEMO_SIMULATOR
        override val inputUnavailableReason: String? = null
        override var spellingDemand: (() -> Boolean)? = null

        override val availableSchemas: List<KeyboardSchemaDescriptor>
            get() = alphabets.map { (id, alphabet) -> KeyboardSchemaDescriptor(id, id, alphabet) }

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

        override val statusSummary: String get() = "quiet"

        override suspend fun spellerAlphabet(schemaID: String): String? = alphabets[schemaID]

        override suspend fun optionStates(): List<EngineOptionState> = emptyList()
    }

    private companion object {
        const val LATIN_ALPHABET = "abcdefghijklmnopqrstuvwxyz"

        // The Dachen key map of an alias id the language table cannot place by its name.
        const val BOPOMOFO_ALPHABET = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-"
    }

    private fun set(vararg schemas: KeyboardSchemaDescriptor) =
        KeyboardResourceSet(
            id = "test",
            name = "测试来源",
            sharedDirectory = "/shared",
            userDirectory = "/user",
            layoutEntries = schemas.map { KeyboardResourceSet.LayoutEntry(KeyboardLayoutKind.QWERTY26, it.id) },
            schemas = schemas.toList(),
        )

    @Test
    fun theCachedProfileCarriesAnAliasFamilyForLayoutSelection() =
        runTest {
            val engine = QuietEngine("dachen", mapOf("dachen" to BOPOMOFO_ALPHABET))
            val session =
                InputSession(
                    engine,
                    KeyboardLayoutKind.QWERTY26,
                    set(KeyboardSchemaDescriptor("dachen", "大字注音", BOPOMOFO_ALPHABET)),
                )
            session.reloadOptions()
            // The engine alphabet, not the id, places this scheme in the zhuyin family.
            assertEquals(InputSchemeFamily.ZHUYIN, session.inputLanguage.family)
            assertEquals(InputSchemeFamily.ZHUYIN, session.layoutFamilyContext().familyOf("dachen"))
        }

    @Test
    fun theLayoutFamilyContextFollowsTheSchemeSwitch() =
        runTest {
            val engine =
                QuietEngine(
                    "dachen",
                    mapOf("dachen" to BOPOMOFO_ALPHABET, "songyan_quanpin" to LATIN_ALPHABET),
                )
            val session =
                InputSession(
                    engine,
                    KeyboardLayoutKind.QWERTY26,
                    set(
                        KeyboardSchemaDescriptor("dachen", "大字注音", BOPOMOFO_ALPHABET),
                        KeyboardSchemaDescriptor("songyan_quanpin", "内置全拼", LATIN_ALPHABET),
                    ),
                )
            session.reloadOptions()
            assertEquals(InputSchemeFamily.ZHUYIN, session.layoutFamilyContext().familyOf("dachen"))
            session.selectSchema("songyan_quanpin")
            session.reloadOptions()
            // The cached factor follows the running scheme: another scheme's family is not carried over.
            assertEquals(InputSchemeFamily.GENERIC, session.layoutFamilyContext().familyOf("dachen"))
            assertEquals(InputSchemeFamily.GENERIC, session.layoutFamilyContext().familyOf("songyan_quanpin"))
        }

    @Test
    fun aFoldedShapeSavedForACangjieSchemeFallsBackToTheIdentityOne() =
        runTest {
            val engine = QuietEngine("cangjie5")
            val session =
                InputSession(
                    engine,
                    KeyboardLayoutKind.PHONE9,
                    set(KeyboardSchemaDescriptor("cangjie5", "倉頡", "abcdefghijklmnopqrstuvwxyz")),
                )
            session.selectLayout(KeyboardLayoutKind.PHONE9, "cangjie5")
            assertEquals(KeyboardLayoutKind.QWERTY26, session.layout)
            assertEquals("cangjie5", session.selectedSchemaID)
            assertEquals("cangjie5", engine.schema)
        }

    @Test
    fun aRunningLayoutTheFamilyForbidsIsRepairedWithoutASwitch() =
        runTest {
            val engine = QuietEngine("cangjie5")
            val session =
                InputSession(
                    engine,
                    KeyboardLayoutKind.PHONE9,
                    set(KeyboardSchemaDescriptor("cangjie5", "倉頡", "abcdefghijklmnopqrstuvwxyz")),
                )
            // The editor start path sees the stored pair unchanged and only repairs the shape.
            session.repairFamilyLayout()
            assertEquals(KeyboardLayoutKind.QWERTY26, session.layout)
            assertEquals("cangjie5", session.selectedSchemaID)
        }

    @Test
    fun aSchemeWithoutAFamilyKeepsItsRequestedShape() =
        runTest {
            val engine = QuietEngine("songyan_quanpin")
            val session =
                InputSession(
                    engine,
                    KeyboardLayoutKind.QWERTY26,
                    set(KeyboardSchemaDescriptor("songyan_quanpin", "内置全拼", "abcdefghijklmnopqrstuvwxyz")),
                )
            // The bundled scheme's compact companions are not in this set, so the switch is refused
            // exactly as before and the running layout stays.
            session.selectLayout(KeyboardLayoutKind.PHONE9, "songyan_quanpin")
            assertEquals(KeyboardLayoutKind.QWERTY26, session.layout)
        }
}
