package app.songyan.ime.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Layout files written by the iOS app's own encoders (`scripts/ios-interop.sh <ios-repo> export <dir>`,
 * committed under test resources/ios-interop) must load here and read back as the same data.
 *
 * When SONGYAN_INTEROP_OUT is set, the files are re-written by the Android encoders into that
 * directory so `scripts/ios-interop.sh <ios-repo> verify <dir>` can decode them with the iOS code.
 */
class IosInteropTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val directory = File(IosInteropTest::class.java.getResource("/ios-interop")!!.toURI())

    private fun layoutFiles(): List<File> =
        directory.listFiles { file -> file.name.startsWith("layout-") }!!.sortedBy { it.name }

    /** Numbers as doubles: iOS writes `0.2` and `5`, Android the same values with a different spelling. */
    private fun tree(text: String): JsonElement = canonical(Json.parseToJsonElement(text))

    private fun canonical(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(element.map(::canonical))
            is JsonPrimitive ->
                if (!element.isString && element.content.toDoubleOrNull() != null) {
                    JsonPrimitive(element.content.toDouble())
                } else {
                    element
                }
        }

    /** Where two trees first differ, so a failure names the field instead of dumping both files. */
    private fun firstDifference(
        expected: JsonElement,
        actual: JsonElement,
        path: String = "$",
    ): String? {
        if (expected is JsonObject && actual is JsonObject) {
            for (key in expected.keys + actual.keys) {
                val e = expected[key]
                val a = actual[key]
                if (e == null || a == null) return "$path.$key expected=$e actual=$a"
                firstDifference(e, a, "$path.$key")?.let { return it }
            }
            return null
        }
        if (expected is JsonArray && actual is JsonArray) {
            if (expected.size != actual.size) return "$path size expected=${expected.size} actual=${actual.size}"
            for (i in expected.indices) firstDifference(expected[i], actual[i], "$path[$i]")?.let { return it }
            return null
        }
        return if (expected == actual) null else "$path expected=$expected actual=$actual"
    }

    private fun assertSameTree(
        name: String,
        expected: String,
        actual: String,
    ) = assertEquals(name, null, firstDifference(tree(expected), tree(actual)))

    @Test
    fun everyIosLayoutLoadsAndReadsBackAsTheSameData() {
        assertTrue(layoutFiles().size >= 5)
        val problems =
            layoutFiles().mapNotNull { file ->
                val text = file.readText()
                try {
                    firstDifference(tree(text), tree(LayoutJson.decode(text).encoded()))?.let { file.name + " " + it }
                } catch (error: LayoutException) {
                    file.name + " " + error.message
                }
            }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun theBuiltinLayoutsMatchTheIosBuiltins() {
        val problems =
            BuiltinLayouts.all.mapNotNull { layout ->
                val file = File(directory, "layout-${layout.id}.json")
                if (!file.exists()) return@mapNotNull "${layout.id} has no iOS file"
                firstDifference(tree(file.readText()), tree(layout.encoded()))?.let { layout.id + " " + it }
            }
        assertEquals(emptyList<String>(), problems)
    }

    @Test
    fun theCustomLayoutKeepsItsSplit() {
        val layout = LayoutJson.decode(File(directory, "layout-custom-qwerty.json").readText())
        val split = layout.split!!
        assertEquals(LayoutSplitConfiguration.Activation.ALWAYS, split.activation)
        assertEquals(listOf(5, 5, 3), split.rowBreaks)
        assertEquals(0.2, split.gapFraction, 0.0001)
        assertEquals(false, split.automaticGap)
    }

    @Test
    fun anIosAuthoredSplitArrangementKeepsItsDuplicateKeys() {
        val layout = LayoutJson.decode(File(directory, "layout-custom-split-keys.json").readText())
        assertEquals(BuiltinLayouts.qwerty26.rows, layout.rows)
        val halves = layout.split!!.halves!!

        fun text(keys: List<LayoutKey>) = keys.joinToString("") { it.label }
        assertEquals(listOf("qwerty", "yuiop"), listOf(text(halves[0].left), text(halves[0].right)))
        assertEquals(listOf("asdfg", "ghjkl"), listOf(text(halves[1].left), text(halves[1].right)))
        assertEquals(listOf("zxcv", "vbnm"), listOf(text(halves[2].left), text(halves[2].right)))
        assertTrue(layout.split!!.automaticGap)
    }

    @Test
    fun anIosAuthoredNineKeyLayoutKeepsItsFacesRailsAndControlRow() {
        val layout = LayoutJson.decode(File(directory, "layout-custom-phone9.json").readText())
        // It is a telephone keypad the shell can draw, and it is not the built-in one.
        assertTrue(PhoneKeypadStructure.matches(layout))
        assertTrue(BuiltinLayouts.matchesPhone9PrimarySemantics(layout))
        assertEquals("我的", layout.rows[1].keys[1].label)
        assertEquals(
            "5",
            layout.rows[1]
                .keys[1]
                .action.value,
        )
        // Its rails are declared, so they are what the shell draws instead of the bundled ones.
        assertFalse(PhoneKeypadRailPolicy.usesBundledRails(layout))
        val leading = PhoneKeypadRailPolicy.declaredKeys(layout, LayoutAuxiliaryArea.Position.LEADING)
        val trailing = PhoneKeypadRailPolicy.declaredKeys(layout, LayoutAuxiliaryArea.Position.TRAILING)
        assertEquals(listOf("。", "？"), leading.map { it.label })
        assertEquals(listOf(LayoutActionKind.BACKSPACE, LayoutActionKind.CLEAR_INPUT), trailing.map { it.action.type })
        assertEquals(listOf(1.0, 2.0), PhoneKeypadRailPolicy.declaredSpans(trailing))
        // The control row is not drawn on Android, but it is data and comes back out unchanged.
        val control = PhoneKeypadRailPolicy.controlRowKeys(layout)!!
        assertEquals("空 格", control[1].label)
        assertEquals("，", control[1].longPress!!.single().label)
        val text = File(directory, "layout-custom-phone9.json").readText()
        assertSameTree("custom-phone9", text, layout.encoded())
    }

    @Test
    fun anIosCatalogLoadsWithItsCustomPageAndPairing() {
        val folder = temporary.newFolder("catalog")
        File(directory, "layouts.json").copyTo(File(folder, LayoutStore.CATALOG_FILE_NAME))
        val registry = LayoutStore(folder.absolutePath).load()
        assertNotNull(registry.layout("custom-qwerty"))
        assertNotNull(registry.page("custom-numeric"))
        assertEquals("custom-qwerty", registry.compositions["songyan_quanpin:qwerty26"]!!.mainLayoutID)
        assertEquals("custom-numeric", registry.compositions["songyan_quanpin:qwerty26"]!!.numericPageID)
        // Saved again by Android, the catalog says the same thing.
        val resaved = temporary.newFolder("resaved")
        LayoutStore(resaved.absolutePath).save(registry)
        assertSameTree(
            "catalog",
            File(directory, "layouts.json").readText(),
            File(resaved, LayoutStore.CATALOG_FILE_NAME).readText(),
        )
    }

    /** The acceptance layout as the Android editor leaves it: y, g and v repeated on both halves. */
    private fun androidSplitKeysLayout(): LayoutDefinition {
        fun letter(c: Char) = LayoutKey(c.toString(), action = LayoutAction.code(c.toString()))
        val split = LayoutSplitConfiguration.of(LayoutSplitConfiguration.Activation.ALWAYS)
        val halves = split.derivedArrangement(BuiltinLayouts.qwerty26.rows).toMutableList()
        halves[0] = halves[0].copy(left = halves[0].left + letter('y'))
        halves[1] = halves[1].copy(right = listOf(letter('g')) + halves[1].right)
        halves[2] = halves[2].copy(right = listOf(letter('v')) + halves[2].right)
        return BuiltinLayouts.qwerty26.copyWithId("android-split-keys", "拆分键").withSplit(split.copy(halves = halves))
    }

    /** A catalog the way the Android editors leave it: a user layout with a manual split, authored pages and a pairing. */
    private fun androidAuthoredCatalog(): LayoutRegistry {
        val mine =
            BuiltinLayouts.qwerty26
                .copyWithId("android-mine", "我的 26 键")
                .withSplit(
                    LayoutSplitConfiguration.of(
                        LayoutSplitConfiguration.Activation.ALWAYS,
                        rowBreaks = listOf(5, 5, 4, 3),
                        gapFraction = 0.2,
                    ),
                )
        val numeric = KeyboardPagePresets.numericAutomatic.duplicated("android-digits", "我的数字页")
        val symbols = KeyboardPagePresets.symbolRowsPage(true).duplicated("android-symbols", "我的符号页")
        val english =
            KeyboardPagePresets.englishQwerty
                .duplicated("android-abc", "我的 ABC")
                .withSplit(LayoutSplitConfiguration(activation = LayoutSplitConfiguration.Activation.OFF))
        // A nine-key copy the way the editor leaves it: its rails declared as data, the control row as it was.
        val nine = PhoneKeypadRailPolicy.withDeclaredRails(BuiltinLayouts.phone9.copyWithId("android-nine", "我的九键"))
        return LayoutRegistry
            .create()
            .install(mine.encoded())
            .install(nine.encoded())
            .withPage(numeric)
            .withPage(symbols)
            .withPage(english)
            .withPage(KeyboardPagePresets.symbolsCategorized.duplicated("android-categories", "我的分类符号"))
            .withComposition(
                KeyboardComposition(
                    mainLayoutID = "android-mine",
                    symbolPageID = "android-symbols",
                    numericPageID = "android-digits",
                    englishPageID = "android-abc",
                ),
                "songyan_quanpin:qwerty26",
            )
    }

    @Test
    fun anAndroidAuthoredCatalogSurvivesAStoreRoundTrip() {
        val registry = androidAuthoredCatalog()
        val folder = temporary.newFolder("authored")
        LayoutStore(folder.absolutePath).save(registry)
        assertEquals(registry, LayoutStore(folder.absolutePath).load())
    }

    @Test
    fun writesTheAndroidEncodingsForTheIosDecoders() {
        val out = System.getenv("SONGYAN_INTEROP_OUT")?.let(::File) ?: return
        out.mkdirs()
        for (file in layoutFiles()) File(out, file.name).writeText(LayoutJson.decode(file.readText()).encoded())
        File(out, "layout-android-split-keys.json").writeText(androidSplitKeysLayout().encoded())
        val folder = temporary.newFolder("catalog-out")
        File(directory, "layouts.json").copyTo(File(folder, LayoutStore.CATALOG_FILE_NAME))
        val registry = LayoutStore(folder.absolutePath).load()
        LayoutStore(out.absolutePath).save(registry)
        val authored = temporary.newFolder("authored-out")
        LayoutStore(authored.absolutePath).save(androidAuthoredCatalog())
        File(
            authored,
            LayoutStore.CATALOG_FILE_NAME,
        ).copyTo(File(out, "catalog-android-authored.json"), overwrite = true)
    }
}
