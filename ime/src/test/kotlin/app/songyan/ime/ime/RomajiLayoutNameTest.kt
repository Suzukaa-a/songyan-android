package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import java.lang.reflect.Modifier
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The keyboard menu words the bundled romaji board as itself instead of as the 26-key shape under
 * it, and the four language resources carry that name. The context-dependent label path is the
 * device test's; here the resource the helper picks is checked as data.
 */
class RomajiLayoutNameTest {
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

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
        val dir = File(repoRoot, "ime/src/main/res/" + qualifier)
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
    fun theBundledRomajiBoardIsWordedByItsOwnResource() {
        val resource = requireNotNull(bundledLayoutNameRes("romaji26")) { "romaji26 has no worded name" }
        assertEquals("ime_layout_romaji26", stringResourceName(resource))
        // Shapes, an author's copy and an empty id keep the names they already have.
        assertNull(bundledLayoutNameRes("qwerty26"))
        assertNull(bundledLayoutNameRes("my-romaji"))
        assertNull(bundledLayoutNameRes(null))
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
            assertEquals(qualifier, name, layoutNames(qualifier)["ime_layout_romaji26"])
        }
    }
}
