package app.songyan.ime.settings.state

import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.settings.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.Modifier
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Settings word the bundled kana board as itself instead of as the 26-key shape under it, and the
 * four language resources carry that name and the new key-message. Author-owned layouts keep the
 * name they were saved with, and the Japanese pairings keep the romaji board until one is chosen.
 */
class Kana12LayoutDisplayNamesTest {
    private val qwerty = KeyboardLayoutOption.QWERTY26
    private val kagiroi = KeyboardCompositionKey("kagiroi", LayoutInput.Builtin.QWERTY26)
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private fun registry(vararg compositions: Pair<KeyboardCompositionKey, String>): LayoutRegistry {
        var result = LayoutRegistry.create()
        for ((key, mainLayoutId) in compositions) {
            result = result.withComposition(KeyboardComposition(mainLayoutID = mainLayoutId), key.rawValue)
        }
        return result
    }

    /** The name of the generated string resource behind an id: a JVM test has no context to ask. */
    private fun stringResourceName(id: Int): String =
        R.string::class.java.declaredFields
            .firstOrNull { field ->
                Modifier.isStatic(field.modifiers) &&
                    field.type == Int::class.javaPrimitiveType &&
                    field.getInt(null) == id
            }?.name
            ?: throw AssertionError("no string resource has id " + id)

    private fun layoutNames(qualifier: String): Map<String, String> {
        val dir = File(repoRoot, "settings/src/main/res/" + qualifier)
        assertTrue(qualifier + " is missing", dir.isDirectory)
        val names = linkedMapOf<String, String>()
        dir
            .listFiles { file -> file.name.startsWith("strings") && file.extension == "xml" }
            .orEmpty()
            .sortedBy { it.name }
            .forEach { file ->
                val document =
                    DocumentBuilderFactory
                        .newInstance()
                        .newDocumentBuilder()
                        .parse(file)
                val children = document.documentElement.childNodes
                for (index in 0 until children.length) {
                    val element = children.item(index) as? Element ?: continue
                    if (element.tagName == "string") names[element.getAttribute("name")] = element.textContent
                }
            }
        return names
    }

    @Test
    fun theKanaBoardIsWordedByItsOwnResource() {
        val resource = requireNotNull(bundledLayoutNameRes("kana12")) { "kana12 has no worded name" }
        assertEquals("layout_name_kana12", stringResourceName(resource))
        assertEquals(LayoutDisplayName.Worded(resource), layoutDisplayName(BuiltinLayouts.kana12, qwerty))
        // Shapes, an author's layout id and an empty id are never renamed.
        for (id in listOf("qwerty26", "merged14", "merged18", "phone9", "my-kana", "")) {
            assertNull(id, bundledLayoutNameRes(id))
        }
        assertNull(bundledLayoutNameRes(null))
    }

    @Test
    fun theJapaneseKeyboardKeepsTheRomajiBoardAndOffersTheKanaOne() {
        val fromFamily =
            requireNotNull(
                currentMainLayout("kagiroi", "qwerty26", InputSchemeFamily.JAPANESE, LayoutRegistry.create()),
            )
        assertEquals("romaji26", fromFamily.id)
        // A pairing saved on the kana board reads back as that board and is worded as itself.
        val chosen =
            requireNotNull(
                currentMainLayout(
                    "kagiroi",
                    "qwerty26",
                    InputSchemeFamily.JAPANESE,
                    registry(kagiroi to "kana12"),
                ),
            )
        assertEquals("kana12", chosen.id)
        assertEquals(LayoutDisplayName.Worded(R.string.layout_name_kana12), chosen.displayName)
    }

    @Test
    fun anAuthorsKanaLayoutKeepsItsName() {
        val mine = BuiltinLayouts.kana12.copyWithId("my-kana", "我的假名")
        assertEquals(LayoutDisplayName.Authored("我的假名"), layoutDisplayName(mine, qwerty))
        // Editing the bundled board also makes it the author's own.
        assertEquals(
            LayoutDisplayName.Authored("我的假名"),
            layoutDisplayName(BuiltinLayouts.kana12.renamed("我的假名"), qwerty),
        )
    }

    @Test
    fun theLayoutNameAndTheNewKeyMessageAreTranslatedIntoAllFourLanguages() {
        val expected =
            mapOf(
                "values" to "Kana 12 keys",
                "values-zh" to "假名 12 键",
                "values-b+zh+Hant" to "假名 12 鍵",
                "values-ja" to "かな 12キー",
            )
        for ((qualifier, name) in expected) {
            assertEquals(qualifier, name, layoutNames(qualifier)["layout_name_kana12"])
        }
        // The kana key message is a new user-visible sentence, so every language carries it too.
        for (qualifier in expected.keys) {
            val message = layoutNames(qualifier)["layout_error_kana_row"]
            assertTrue(qualifier + " is missing layout_error_kana_row", message.isNullOrEmpty().not())
        }
    }
}
