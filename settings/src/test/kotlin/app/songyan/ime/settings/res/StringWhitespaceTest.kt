package app.songyan.ime.settings.res

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StringWhitespaceTest {
    private val resDir = File("src/main/res")
    private val entry = Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)

    private fun entries(): List<Triple<String, String, String>> =
        resDir.listFiles { f -> f.name.startsWith("values") }!!.flatMap { dir ->
            dir.listFiles { f -> f.name.endsWith(".xml") }!!.flatMap { file ->
                entry.findAll(file.readText()).map { Triple(dir.name, it.groupValues[1], it.groupValues[2]) }.toList()
            }
        }

    // aapt trims unquoted leading and trailing whitespace, which silently drops the space in
    // "<name> copy" style suffixes.
    @Test
    fun noStringRelyOnUnquotedEdgeWhitespace() {
        val bad = entries().filter { (_, _, v) -> v != v.trim() && !v.trim().startsWith("\"") }
        assertEquals(bad.toString(), 0, bad.size)
    }

    @Test
    fun copySuffixKeepsItsSeparatorWhereTheLanguageUsesOne() {
        for (dir in listOf("values", "values-zh", "values-b+zh+Hant")) {
            val v = entries().single { it.first == dir && it.second == "layout_copy_suffix" }.third
            assertTrue("$dir: $v", v.startsWith("\" ") && v.endsWith("\""))
        }
    }
}
