package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.TestZips
import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutFold
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.UserKeyboards
import app.songyan.ime.prefs.KeyboardPreferences
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeEngineBackend
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.LayoutLibraryHolder
import app.songyan.ime.settings.state.SchemeImportHolder
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On a device: importing a scheme package folds no keyboard; once the user picks the 14-, 18- and
 * 9-key keyboards for its input method, the real :deploy process generates and compiles their
 * companions, and the keyboard's engine over the published build types the package's words through
 * them.
 */
@RunWith(AndroidJUnit4::class)
class SchemeCompanionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val storage = AppStorage(context.filesDir)
    private val publisher = GenerationPublisher(storage)
    private val preferences = KeyboardPreferences(context)
    private var savedKeyboards: UserKeyboards.Stored? = null
    private val settings = SettingsInForeground()
    private var scheme: String? = null

    @Before
    fun setUp() {
        settings.open()
        savedKeyboards = runBlocking { preferences.myKeyboards.first() }
    }

    @After
    fun tearDown() {
        runCatching { runBlocking { LayoutLibraryHolder.current.edit { it.remove(OWN_LAYOUT) } } }
        scheme?.let { id -> runCatching { runBlocking { StoredSourcesHolder.current.delete(id) } } }
        savedKeyboards?.let { runBlocking { preferences.setMyKeyboards(it) } }
        settings.close()
    }

    @Test
    fun anImportedSchemeTypesOnItsGeneratedCompactCompanions() =
        runBlocking {
            val imported = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage()))
            assertTrue("scheme import failed: $imported", imported is SchemeImportResult.Success)
            val record = requireNotNull(publisher.readActive())
            scheme = record.sourceId
            assertEquals(listOf(SCHEMA), record.schemaIds)
            assertEquals(
                "nothing is folded before the user picks",
                emptyMap<KeyboardLayoutKind, String>(),
                adaptersOf(record),
            )

            val picked = pickEveryKeyboard(SCHEMA)
            val adapters = adaptersOf(picked)
            assertEquals(
                listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9),
                adapters.keys.toList(),
            )
            assertTrue(candidates(picked, adapters.getValue(KeyboardLayoutKind.MERGED14), "tabagu").contains(WORD))
            assertTrue(candidates(picked, adapters.getValue(KeyboardLayoutKind.PHONE9), "926744").contains(WORD))
        }

    /**
     * A speller alphabet that declares tone digits and a backslash (as rime_wanxiang does) no longer
     * limits the scheme to 26 keys: all three compact layouts exist, and the 9-key one still types
     * the toneless spelling although the scheme also spells `yan9`.
     */
    @Test
    fun aSchemeDeclaringToneDigitsStillGetsEveryCompactLayout() =
        runBlocking {
            val imported = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage(toneKeys = true)))
            assertTrue("scheme import failed: $imported", imported is SchemeImportResult.Success)
            val record = requireNotNull(publisher.readActive())
            scheme = record.sourceId

            val picked = pickEveryKeyboard(SCHEMA)
            val adapters = adaptersOf(picked)
            assertEquals(
                listOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9),
                adapters.keys.toList(),
            )
            assertTrue(candidates(picked, adapters.getValue(KeyboardLayoutKind.MERGED14), "tabagu").contains(WORD))
            assertTrue(candidates(picked, adapters.getValue(KeyboardLayoutKind.PHONE9), "926744").contains(WORD))
        }

    /**
     * A package with two input methods is usable right after the import with no folded keyboards;
     * picking keyboards for both makes the deploy process build them, without another import.
     */
    @Test
    fun bothInputMethodsGetTheLayoutsPickedAfterTheImport() =
        runBlocking {
            val imported = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage(SCHEMA, SECOND)))
            assertTrue("scheme import failed: $imported", imported is SchemeImportResult.Success)
            val record = requireNotNull(publisher.readActive())
            scheme = record.sourceId
            assertEquals(listOf(SCHEMA, SECOND), record.schemaIds)
            assertFalse("a fresh install waits for the user to pick", record.companionsPending)
            assertEquals(emptyMap<String, Set<KeyboardLayoutKind>>(), layoutsBySource(record))

            val layouts = layoutsBySource(pickEveryKeyboard(SCHEMA, SECOND))
            val all = setOf(KeyboardLayoutKind.MERGED14, KeyboardLayoutKind.MERGED18, KeyboardLayoutKind.PHONE9)
            assertEquals(mapOf(SCHEMA to all, SECOND to all), layouts)
        }

    /**
     * A 14-key layout that moves y from the TY key to the UI key (codes t | yui, the UI key sending u) is a
     * grouping of its own: picking it builds a companion for that grouping, which types the word with
     * the keys this layout sends, while the bundled 14-key companion keeps typing it the bundled way.
     */
    @Test
    fun aFourteenKeyLayoutGroupedItsOwnWayTypesOnItsOwnCompanion() =
        runBlocking {
            val imported = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage()))
            assertTrue("scheme import failed: $imported", imported is SchemeImportResult.Success)
            scheme = requireNotNull(publisher.readActive()).sourceId
            val base =
                LayoutFold.writtenOut(
                    BuiltinLayouts.merged14.copyWithId(OWN_LAYOUT, "自分组 14 键"),
                    KeyboardLayoutKind.MERGED14,
                )
            val own =
                base.copy(
                    rows =
                        base.rows.map { row ->
                            LayoutRow(
                                row.keys.map { key ->
                                    when (key.action.value.takeIf { key.action.type == LayoutActionKind.CODE }) {
                                        "t" -> LayoutFold.keyCovering(key, "t")
                                        "u" -> LayoutFold.keyCovering(key, "yui")
                                        else -> key
                                    }
                                },
                            )
                        },
                )
            val fold = requireNotNull(LayoutFold.of(own, KeyboardLayoutKind.MERGED14))
            assertFalse(fold.isBuiltin)
            LayoutLibraryHolder.current.edit { it.install(own.encoded(), replaceExisting = true) }

            val picked =
                pick(
                    listOf(
                        PublishedKeyboard(SCHEMA, KeyboardLayoutKind.MERGED14, OWN_LAYOUT),
                        PublishedKeyboard(SCHEMA, KeyboardLayoutKind.MERGED14),
                    ),
                    listOf(SCHEMA),
                )
            val companions = companionsOf(picked)
            val ownCompanion = companions.single { it.layout == KeyboardLayoutKind.MERGED14 && it.fold == fold.id }
            val bundled = companions.single { it.layout == KeyboardLayoutKind.MERGED14 && it.fold == null }
            assertTrue(candidates(picked, ownCompanion.schemaID, "uabagu").contains(WORD))
            assertTrue(candidates(picked, bundled.schemaID, "tabagu").contains(WORD))
        }

    /** The same for 9 keys: y moved from 9 (WXYZ) to 8 (TUVY) types the word as 826744 on its own companion. */
    @Test
    fun aNineKeyLayoutGroupedItsOwnWayTypesOnItsOwnCompanion() =
        runBlocking {
            val imported = SchemeImportHolder.current.importFromUri(Uri.fromFile(schemePackage()))
            assertTrue("scheme import failed: $imported", imported is SchemeImportResult.Success)
            scheme = requireNotNull(publisher.readActive()).sourceId
            val base =
                LayoutFold.writtenOut(
                    BuiltinLayouts.phone9.copyWithId(OWN_LAYOUT, "自分组 9 键"),
                    KeyboardLayoutKind.PHONE9,
                )
            val own =
                base.copy(
                    rows =
                        base.rows.map { row ->
                            LayoutRow(
                                row.keys.map { key ->
                                    when (key.action.value.takeIf { key.action.type == LayoutActionKind.CODE }) {
                                        "8" -> LayoutFold.keyCovering(key, "tuvy")
                                        "9" -> LayoutFold.keyCovering(key, "wxz")
                                        else -> key
                                    }
                                },
                            )
                        },
                )
            val fold = requireNotNull(LayoutFold.of(own, KeyboardLayoutKind.PHONE9))
            assertEquals('8', fold.map['y'])
            LayoutLibraryHolder.current.edit { it.install(own.encoded(), replaceExisting = true) }

            val picked =
                pick(
                    listOf(
                        PublishedKeyboard(SCHEMA, KeyboardLayoutKind.PHONE9, OWN_LAYOUT),
                        PublishedKeyboard(SCHEMA, KeyboardLayoutKind.PHONE9),
                    ),
                    listOf(SCHEMA),
                )
            val companions = companionsOf(picked)
            val ownCompanion = companions.single { it.layout == KeyboardLayoutKind.PHONE9 && it.fold == fold.id }
            val bundled = companions.single { it.layout == KeyboardLayoutKind.PHONE9 && it.fold == null }
            assertTrue(candidates(picked, ownCompanion.schemaID, "826744").contains(WORD))
            assertTrue(candidates(picked, bundled.schemaID, "926744").contains(WORD))
        }

    /** Stores every shape as the keyboards of [schemas], as the install picker does, and waits for the build. */
    private suspend fun pickEveryKeyboard(vararg schemas: String): PublicationRecord =
        pick(schemas.flatMap { id -> KeyboardLayoutKind.entries.map { PublishedKeyboard(id, it) } }, schemas.toList())

    /** Stores [picked] as the keyboards of [schemas] and waits for the build. */
    private suspend fun pick(
        picked: List<PublishedKeyboard>,
        schemas: List<String>,
    ): PublicationRecord {
        val before = requireNotNull(publisher.readActive()).sequence
        val stored = preferences.myKeyboards.first()
        preferences.setMyKeyboards(
            UserKeyboards.Stored(
                stored.keyboards.filter { it.schemaID !in schemas } + picked,
                stored.schemes + schemas,
            ),
        )
        // Settled: built, and no later generation replaces it (an older one's files are pruned).
        val deadline = System.currentTimeMillis() + 120_000
        var settled: PublicationRecord? = null
        while (System.currentTimeMillis() < deadline) {
            val active = requireNotNull(publisher.readActive())
            if (active.sequence > before && !active.companionsPending) {
                if (active == settled) return active
                settled = active
                delay(3_000)
            } else {
                delay(250)
            }
        }
        throw AssertionError("the picked keyboards were not built")
    }

    private suspend fun layoutsBySource(record: PublicationRecord): Map<String, Set<KeyboardLayoutKind>> {
        val generation = storage.generationDir(record.generation)
        val user = File(context.cacheDir, "companion-test-user").also { it.deleteRecursively() }
        val backend =
            RimeEngineBackend.create(
                sharedDataDir = File(generation, "resources"),
                userDataDir = user,
                compiledDir = File(generation, "build"),
                appName = RimeEngine.DEFAULT_APP_NAME,
                appVersion = "test",
            )
        try {
            return backend.encodingAdapters
                .groupBy { it.sourceSchemaID }
                .mapValues { (_, adapters) -> adapters.map { it.layout }.toSet() }
        } finally {
            backend.shutdown()
            user.deleteRecursively()
        }
    }

    /** The companions the keyboard's backend reads from the published build, by layout. */
    private suspend fun adaptersOf(record: PublicationRecord): Map<KeyboardLayoutKind, String> =
        companionsOf(record).associate { it.layout to it.schemaID }

    /** Every companion the keyboard's backend reads from the published build. */
    private suspend fun companionsOf(record: PublicationRecord): List<KeyboardResourceSet.EncodingAdapter> {
        val generation = storage.generationDir(record.generation)
        val user = File(context.cacheDir, "companion-test-user").also { it.deleteRecursively() }
        val backend =
            RimeEngineBackend.create(
                sharedDataDir = File(generation, "resources"),
                userDataDir = user,
                compiledDir = File(generation, "build"),
                appName = RimeEngine.DEFAULT_APP_NAME,
                appVersion = "test",
            )
        try {
            assertEquals(listOf(SCHEMA), backend.availableSchemas.map { it.id })
            for (adapter in backend.encodingAdapters) {
                assertEquals(SCHEMA, adapter.sourceSchemaID)
                backend.validateSchema(adapter.schemaID)
            }
            return backend.encodingAdapters
        } finally {
            backend.shutdown()
            user.deleteRecursively()
        }
    }

    private suspend fun candidates(
        record: PublicationRecord,
        schema: String,
        keys: String,
    ): List<String> {
        val generation = storage.generationDir(record.generation)
        val user = File(context.cacheDir, "companion-test-user").also { it.deleteRecursively() }
        val engine =
            RimeEngine.createPrebuilt(
                sharedDataDir = File(generation, "resources"),
                userDataDir = user,
                appVersion = "test",
                compiledDir = File(generation, "build"),
            )
        try {
            assertTrue("cannot select $schema", engine.selectSchema(schema))
            for (char in keys) engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
            return engine.readSnapshot().candidates.map { it.text }
        } finally {
            engine.close()
            user.deleteRecursively()
        }
    }

    private fun schemePackage(
        vararg schemas: String = arrayOf(SCHEMA),
        toneKeys: Boolean = false,
    ): File {
        val files =
            linkedMapOf(
                "demo-main/default.yaml" to "schema_list:\n" + schemas.joinToString("") { "  - schema: $it\n" },
            )
        for (schema in schemas) {
            files["demo-main/$schema.schema.yaml"] =
                "schema:\n  schema_id: $schema\n  name: 配套演示\n" +
                "engine:\n  processors:\n    - speller\n    - selector\n    - express_editor\n" +
                "  segmentors:\n    - abc_segmentor\n  translators:\n    - script_translator\n" +
                "speller:\n  alphabet: zyxwvutsrqponmlkjihgfedcba" +
                (if (toneKeys) "1234567890\\\\\n  algebra:\n    - derive/^(yan)$/yan9/\n" else "\n") +
                "translator:\n  dictionary: $SCHEMA\n"
        }
        files["demo-main/$SCHEMA.dict.yaml"] =
            "---\nname: $SCHEMA\nversion: \"1\"\nsort: by_weight\n...\n$WORD\tyan shi\t1\n言\tyan\t1\n"
        val target = File(context.cacheDir, "demo-companion.zip")
        TestZips.write(target, files)
        return target
    }

    private companion object {
        const val SCHEMA = "demo_companion"
        const val SECOND = "demo_companion_two"
        const val WORD = "演示"
        const val OWN_LAYOUT = "companion-test-own14"
    }
}
