package app.songyan.ime.layout

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.PublishedKeyboard
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Data written by the build before keyboards named their layout (`d57-snapshot`: the layout catalog
 * as its store saved it and the `keyboard` preferences file) reads as the same keyboards, main
 * layouts, pages and selection. The catalog holds a copied layout paired with rime_ice's 26 keys, the
 * kana board paired with kagiroi and a page choice for a 9-key keyboard; the preferences hide one
 * input method, one shape and one board.
 */
class KeyboardsSnapshotTest {
    @get:Rule val folder = TemporaryFolder()

    private val families = LayoutFamilyContext.UNKNOWN
    private val all = KeyboardLayoutKind.entries.toSet()
    private val identity = setOf(KeyboardLayoutKind.QWERTY26)
    private val schemes =
        listOf(
            KeyboardScheme("rime_ice", all),
            KeyboardScheme("double_pinyin_abc", all),
            KeyboardScheme("double_pinyin_flypy", all),
            KeyboardScheme("kagiroi", identity),
        )

    private fun resource(name: String): File =
        File(folder.root, name).also { file ->
            val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("d57-snapshot/$name")) { name }
            stream.use { input -> file.outputStream().use { input.copyTo(it) } }
        }

    private fun registry(): LayoutRegistry {
        resource(LayoutStore.CATALOG_FILE_NAME)
        return LayoutStore(folder.root.path).load()
    }

    private fun preferences(): Preferences =
        runBlocking {
            val file = resource("keyboard.preferences_pb")
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }).data.first()
            } finally {
                scope.cancel()
            }
        }

    @Test
    fun theSnapshotHoldsNoKeyboardList() {
        val preferences = preferences()
        assertNull(preferences[stringPreferencesKey("my_keyboards")])
        assertNull(preferences[stringPreferencesKey("layout_definition_id")])
        assertEquals("rime_ice", preferences[stringPreferencesKey("active_schema_id")])
        assertEquals("qwerty26", preferences[stringPreferencesKey("layout_id")])
    }

    @Test
    fun theKeyboardsAreTheOnesTheMenuOffered() {
        val preferences = preferences()
        val current =
            PublishedKeyboard(
                requireNotNull(preferences[stringPreferencesKey("active_schema_id")]),
                requireNotNull(KeyboardLayoutKind.fromWire(preferences[stringPreferencesKey("layout_id")].orEmpty())),
                preferences[stringPreferencesKey("layout_definition_id")],
            )
        val legacy =
            LegacyKeyboards.of(
                schemes,
                preferences[stringSetPreferencesKey("menu_hidden_schemas")].orEmpty(),
                preferences[stringSetPreferencesKey("menu_hidden_layouts")].orEmpty(),
                registry(),
                families,
                current,
            )
        val keyboards = UserKeyboards.effective(UserKeyboards.Stored(), schemes.map { it.id }, legacy)
        assertEquals(
            listOf(
                // The copied layout was the main layout of rime_ice's 26 keys; 14 keys were hidden.
                "rime_ice:qwerty26#my-26",
                "rime_ice:merged18#merged18",
                "rime_ice:phone9#phone9",
                // double_pinyin_abc was hidden.
                "double_pinyin_flypy:qwerty26#qwerty26",
                "double_pinyin_flypy:merged18#merged18",
                "double_pinyin_flypy:phone9#phone9",
                // The kana board was hidden and kagiroi is not the input method in use.
                "kagiroi:qwerty26#romaji26",
            ),
            keyboards.map { it.id },
        )
        // The keyboard in use names no layout and still draws the copied one.
        assertEquals("rime_ice:qwerty26#my-26", UserKeyboards.named(current, registry(), families).id)
    }

    @Test
    fun everyKeyboardKeepsTheMainLayoutAndPagesOfItsShape() {
        val registry = registry()
        for (scheme in schemes) {
            for (kind in scheme.shapes) {
                val shape = PublishedKeyboard(scheme.id, kind)
                val before = registry.composition(shape.compositionKey(), families)
                val named = UserKeyboards.named(shape, registry, families)
                assertEquals(shape.id, before, registry.composition(named.compositionKey(), families))
            }
        }
        val mine = registry.composition(KeyboardCompositionKey.parse("rime_ice:qwerty26#my-26")!!, families)
        assertEquals("my-26", mine.mainLayoutID)
        assertEquals(KeyboardPagePresets.NUMERIC_KEYPAD_ID, mine.numericPageID)
        val nine = registry.composition(KeyboardCompositionKey.parse("double_pinyin_flypy:phone9#phone9")!!, families)
        assertEquals(KeyboardPagePresets.SYMBOLS_CATEGORIZED_ID, nine.symbolPageID)
        // Both Japanese boards keep the pages their shared shape had.
        val shared = registry.composition(KeyboardCompositionKey.parse("kagiroi:qwerty26")!!, families)
        for (board in listOf("romaji26", "kana12")) {
            val composition = registry.composition(KeyboardCompositionKey.parse("kagiroi:qwerty26#$board")!!, families)
            assertEquals(shared.copy(mainLayoutID = board), composition)
        }
    }

    @Test
    fun readingChangesNothingStored() {
        val registry = registry()
        assertEquals(
            setOf("rime_ice:qwerty26", "kagiroi:qwerty26", "double_pinyin_flypy:phone9"),
            registry.compositions.keys,
        )
        assertEquals("kana12", registry.compositions.getValue("kagiroi:qwerty26").mainLayoutID)
    }
}
