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
 * Settings word the bundled romaji board as itself instead of as the 26-key shape under it, and the
 * four language resources carry that name. Author-owned layouts keep the name they were saved with.
 */
class RomajiLayoutDisplayNamesTest {
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
    fun theRomajiBoardIsWordedByItsOwnResource() {
        val resource = requireNotNull(bundledLayoutNameRes("romaji26")) { "romaji26 has no worded name" }
        assertEquals("layout_name_romaji26", stringResourceName(resource))
        assertEquals(LayoutDisplayName.Worded(resource), layoutDisplayName(BuiltinLayouts.romaji26, qwerty))
        // Shapes, an author's layout id and an empty id are never renamed.
        for (id in listOf("qwerty26", "merged14", "my-romaji", "")) {
            assertNull(id, bundledLayoutNameRes(id))
        }
        assertNull(bundledLayoutNameRes(null))
    }

    @Test
    fun theJapaneseKeyboardIsNamedByTheRomajiBoard() {
        val fromFamily =
            requireNotNull(
                currentMainLayout("kagiroi", "qwerty26", InputSchemeFamily.JAPANESE, LayoutRegistry.create()),
            )
        assertEquals("romaji26", fromFamily.id)
        val familyName =
            fromFamily.displayName as? LayoutDisplayName.Worded
                ?: throw AssertionError("the bundled romaji board must be worded by a resource")
        assertEquals("layout_name_romaji26", stringResourceName(familyName.resource))
        // A saved pairing on the board reads back as the board; one saved on the 26-key shape before
        // the board shipped keeps the name it has.
        assertEquals(
            "romaji26",
            currentMainLayout(
                "kagiroi",
                "qwerty26",
                InputSchemeFamily.JAPANESE,
                registry(kagiroi to "romaji26"),
            )?.id,
        )
        val saved26 =
            requireNotNull(
                currentMainLayout(
                    "kagiroi",
                    "qwerty26",
                    InputSchemeFamily.JAPANESE,
                    registry(kagiroi to "qwerty26"),
                ),
            )
        assertEquals("qwerty26", saved26.id)
        val savedName =
            saved26.displayName as? LayoutDisplayName.Worded
                ?: throw AssertionError("the shipped 26-key shape must be worded by a resource")
        assertEquals("layout_name_26", stringResourceName(savedName.resource))
    }

    @Test
    fun anAuthorsRomajiLayoutKeepsItsName() {
        val mine = BuiltinLayouts.romaji26.copyWithId("my-romaji", "我的罗马字")
        assertEquals(LayoutDisplayName.Authored("我的罗马字"), layoutDisplayName(mine, qwerty))
        // Editing the bundled board also makes it the author's own.
        assertEquals(
            LayoutDisplayName.Authored("我的罗马字"),
            layoutDisplayName(BuiltinLayouts.romaji26.renamed("我的罗马字"), qwerty),
        )
        val owned =
            LayoutRegistry
                .create(layouts = BuiltinLayouts.all + mine)
                .withComposition(KeyboardComposition(mainLayoutID = "my-romaji"), kagiroi.rawValue)
        assertEquals(
            CurrentMainLayout("my-romaji", LayoutDisplayName.Authored("我的罗马字")),
            currentMainLayout("kagiroi", "qwerty26", InputSchemeFamily.JAPANESE, owned),
        )
    }

    @Test
    fun theLayoutNameIsTranslatedIntoAllFourLanguages() {
        val expected =
            mapOf(
                "values" to "Romaji 26 keys",
                "values-zh" to "罗马字 26 键",
                "values-b+zh+Hant" to "羅馬字 26 鍵",
                "values-ja" to "ローマ字 26キー",
            )
        for ((qualifier, name) in expected) {
            assertEquals(qualifier, name, layoutNames(qualifier)["layout_name_romaji26"])
        }
    }
}
