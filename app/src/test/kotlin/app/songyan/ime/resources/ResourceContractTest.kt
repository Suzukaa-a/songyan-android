package app.songyan.ime.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the language setup: English is the default resource set, Chinese lives in `values-zh`,
 * both define the same strings, and the app opts into per-app language and predictive back.
 */
class ResourceContractTest {
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private val resourceModules = listOf("app", "settings", "ime")

    private fun stringNames(dir: File): Set<String> =
        dir
            .listFiles { f -> f.name.startsWith("strings") && f.extension == "xml" }
            .orEmpty()
            .flatMap { file ->
                val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
                val nodes = doc.getElementsByTagName("string")
                (0 until nodes.length)
                    .map { nodes.item(it).attributes }
                    .filter { it.getNamedItem("translatable")?.nodeValue != "false" }
                    .map { it.getNamedItem("name").nodeValue }
            }.toSet()

    private fun stringValue(
        module: String,
        qualifier: String,
        name: String,
    ): String? {
        val dir = File(repoRoot, "$module/src/main/res/$qualifier")
        dir.listFiles { f -> f.extension == "xml" }.orEmpty().forEach { file ->
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
            val nodes = doc.getElementsByTagName("string")
            for (i in 0 until nodes.length) {
                val node = nodes.item(i)
                if (node.attributes.getNamedItem("name").nodeValue == name) return node.textContent
            }
        }
        return null
    }

    @Test
    fun defaultAndChineseResourcesDefineTheSameStrings() {
        for (module in resourceModules) {
            val res = File(repoRoot, "$module/src/main/res")
            assertTrue("$module has no values-en; English is the default set", !File(res, "values-en").exists())
            val default = stringNames(File(res, "values"))
            val zh = stringNames(File(res, "values-zh"))
            assertEquals("$module: only in default", emptySet<String>(), default - zh)
            assertEquals("$module: only in zh", emptySet<String>(), zh - default)
        }
    }

    @Test
    fun theInputMethodIsNamedSongyanInEnglishAndSongyanInputMethodInChinese() {
        assertEquals("Songyan", stringValue("app", "values", "ime_name"))
        assertEquals("松烟输入法", stringValue("app", "values-zh", "ime_name"))
    }

    @Test
    fun manifestDeclaresLocaleConfigAndPredictiveBack() {
        val manifest = File(repoRoot, "app/src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:localeConfig=\"@xml/locales_config\""))
        assertTrue(manifest.contains("android:enableOnBackInvokedCallback=\"true\""))
        val localeNames =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(File(repoRoot, "app/src/main/res/xml/locales_config.xml"))
                .getElementsByTagName("locale")
                .let { nodes ->
                    (0 until nodes.length).map {
                        nodes
                            .item(it)
                            .attributes
                            .getNamedItem("android:name")
                            .nodeValue
                    }
                }
        assertEquals(listOf("en", "zh-Hans", "zh-Hant", "ja"), localeNames)
    }
}
