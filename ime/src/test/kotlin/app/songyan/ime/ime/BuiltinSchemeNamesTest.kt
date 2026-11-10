package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The bundled schemes are named as bundled in every interface language: a bare 小鹤双拼 would read
 * the same as the double pinyin a catalog scheme such as rime-ice ships, and the switcher would
 * show two identical rows. The Simplified Chinese words are the schema files' own names.
 */
class BuiltinSchemeNamesTest {
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }

    private fun strings(
        module: String,
        qualifier: String,
    ): Map<String, String> {
        val file = File(repoRoot, "$module/src/main/res/$qualifier/strings.xml")
        assertTrue("$file is missing", file.isFile)
        val children =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(file)
                .documentElement.childNodes
        val names = linkedMapOf<String, String>()
        for (index in 0 until children.length) {
            val element = children.item(index) as? Element ?: continue
            if (element.tagName == "string") names[element.getAttribute("name")] = element.textContent
        }
        return names
    }

    @Test
    fun theBundledSchemesNeverShareANameWithACatalogScheme() {
        val catalogNames =
            listOf("double_pinyin_flypy", "rime_ice", "luna_pinyin")
                .map { KeyboardSchemaDescriptor(id = it, name = "").displayName }
                .toSet()
        for ((module, prefix) in listOf("ime" to "ime_schema", "app" to "scheme_builtin")) {
            for (qualifier in listOf("values", "values-zh", "values-b+zh+Hant", "values-ja")) {
                val strings = strings(module, qualifier)
                val quanpin = requireNotNull(strings[prefix + "_quanpin"]) { "$module $qualifier" }
                val flypy = requireNotNull(strings[prefix + "_flypy"]) { "$module $qualifier" }
                assertNotEquals("$module $qualifier", quanpin, flypy)
                assertTrue("$module $qualifier: $flypy", flypy !in catalogNames)
                assertTrue("$module $qualifier: $quanpin", quanpin !in catalogNames)
            }
            val simplified = strings(module, "values-zh")
            assertEquals(BuiltinTemplateSchemas.NAMES[BuiltinTemplateSchemas.QUANPIN], simplified[prefix + "_quanpin"])
            assertEquals(BuiltinTemplateSchemas.NAMES[BuiltinTemplateSchemas.FLYPY], simplified[prefix + "_flypy"])
        }
    }
}
