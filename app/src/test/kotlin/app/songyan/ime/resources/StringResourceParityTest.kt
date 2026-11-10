package app.songyan.ime.resources

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Guards the four language resource sets as data: the same string and plural names, the same printf
 * arguments in every translation, and Japanese copy that is actually Japanese. Resource XML only;
 * the app's UI code is not inspected.
 */
class StringResourceParityTest {
    private val repoRoot: File =
        generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    private val modules = listOf("app", "settings", "ime")

    private val languages = listOf("values", "values-zh", "values-b+zh+Hant", "values-ja")

    private class LanguageResources(
        val strings: Map<String, String>,
        val plurals: Map<String, Map<String, String>>,
    )

    private fun read(
        module: String,
        qualifier: String,
    ): LanguageResources {
        val dir = File(repoRoot, "$module/src/main/res/$qualifier")
        assertTrue("$module: $qualifier is missing", dir.isDirectory)
        val strings = linkedMapOf<String, String>()
        val plurals = linkedMapOf<String, Map<String, String>>()
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
                    if (element.getAttribute("translatable").equals("false", ignoreCase = true)) continue
                    when (element.tagName) {
                        "string" -> strings[element.getAttribute("name")] = element.textContent
                        "plurals" -> {
                            val items = linkedMapOf<String, String>()
                            val itemNodes = element.getElementsByTagName("item")
                            for (item in 0 until itemNodes.length) {
                                val node = itemNodes.item(item) as Element
                                items[node.getAttribute("quantity")] = node.textContent
                            }
                            plurals[element.getAttribute("name")] = items
                        }
                    }
                }
            }
        return LanguageResources(strings, plurals)
    }

    @Test
    fun everyLanguageDefinesTheSameStringAndPluralNames() {
        for (module in modules) {
            val english = read(module, "values")
            for (qualifier in languages.drop(1)) {
                val translated = read(module, qualifier)
                assertEquals("$module $qualifier: string names", english.strings.keys, translated.strings.keys)
                assertEquals("$module $qualifier: plural names", english.plurals.keys, translated.plurals.keys)
            }
        }
    }

    @Test
    fun everyTranslationKeepsTheEnglishPrintfArguments() {
        for (module in modules) {
            val english = read(module, "values")
            for (qualifier in languages.drop(1)) {
                val translated = read(module, qualifier)
                for ((name, value) in translated.strings) {
                    // The name sets are checked elsewhere; a renamed key is that test's failure.
                    val source = english.strings[name] ?: continue
                    assertEquals("$module $qualifier: $name", arguments(source), arguments(value))
                }
                for ((name, items) in translated.plurals) {
                    val sources = english.plurals[name] ?: continue
                    for ((quantity, value) in items) {
                        // Quantity sets may differ by language (an other-only set is valid), so a
                        // target item compares with the same English quantity, else English "other".
                        val source =
                            sources[quantity]
                                ?: sources["other"]
                                ?: throw AssertionError("$module $qualifier: $name/$quantity has no English source")
                        assertEquals("$module $qualifier: $name/$quantity", arguments(source), arguments(value))
                    }
                }
            }
        }
    }

    @Test
    fun japaneseCopyIsTranslatedExceptForTheListedLiterals() {
        for (module in modules) {
            val chinese = read(module, "values-zh")
            val japanese = read(module, "values-ja")
            for ((name, value) in chinese.strings) {
                // The name sets are checked elsewhere; a missing key is that test's failure.
                val translation = japanese.strings[name] ?: continue
                if ("$module/$name" in JAPANESE_LEFT_UNTRANSLATED) continue
                assertTrue("$module: $name is still Simplified Chinese: $translation", translation != value)
            }
            for ((name, items) in chinese.plurals) {
                val translations = japanese.plurals[name] ?: continue
                for ((quantity, value) in items) {
                    // A language may leave a quantity out; only shared quantities are compared.
                    val translation = translations[quantity] ?: continue
                    assertTrue(
                        "$module: $name/$quantity is still Simplified Chinese: $translation",
                        translation != value,
                    )
                }
            }
        }
    }

    /**
     * printf conversions as index+conversion tokens, e.g. `%2$d`. Text may reorder the arguments, so
     * the tokens are compared as a sorted multiset, but the index stays paired with its conversion:
     * an index swapped onto another conversion is caught instead of silently passing. `%%` is a
     * literal percent, and a bare percent followed by prose ("0% flush") is not a conversion either.
     */
    private fun arguments(value: String): List<String> {
        val literalFree = value.replace("%%", " ")
        val positional = POSITIONAL_PRINTF.findAll(literalFree).toList()
        val withoutPositional = POSITIONAL_PRINTF.replace(literalFree, " ")
        val plain =
            PLAIN_PRINTF.findAll(withoutPositional).filter { match ->
                val previous = withoutPositional.getOrNull(match.range.first - 1)
                val conversion = match.groupValues[1].last()
                previous?.isDigit() != true && conversion in PRINTF_CONVERSIONS
            }
        val indexed = positional.map { "%" + it.groupValues[1] + it.groupValues[2] }
        val unindexed = plain.map { "%" + it.groupValues[1] }
        return (indexed + unindexed).sorted()
    }

    private companion object {
        val POSITIONAL_PRINTF = Regex("%(\\d+\\$)([-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z])")

        val PLAIN_PRINTF = Regex("%([-#+ 0,(]*\\d*(?:\\.\\d+)?[a-zA-Z])")

        const val PRINTF_CONVERSIONS = "bBhHsScCdoxXeEfgGaAn"

        /**
         * Keys whose Japanese equals the Simplified Chinese on purpose: typographic and number
         * formats, physical key faces, the glossary's same-form terms, and theme or product proper
         * names. Exact module/key matches, confirmed by the Japanese resources owner; new same-form
         * copy has to be added here deliberately instead of loosening the assertion.
         */
        val JAPANESE_LEFT_UNTRANSLATED =
            setOf(
                // Pure symbols, numbers and format-only strings.
                "app/import_path_separator",
                "app/rime_config_error_detail",
                "settings/candidate_bar_value",
                "settings/features_list_separator",
                "settings/keyboard_title",
                "settings/layout_jump_target",
                "settings/learning_backup_detail",
                "settings/options_bottom_gap_value",
                "settings/options_percent",
                "settings/options_wide_position_value",
                "settings/quick_content_phrases_counter",
                "ime/ime_numbers",
                "settings/layout_page_numeric",
                // Technical literals and physical key faces.
                "ime/ime_back",
                "settings/layout_action_shift",
                "settings/layout_action_language",
                // The glossary's same-form terms.
                "ime/ime_qs_engine_full_shape",
                "settings/options_engine_full_shape",
                "settings/schemes_status_not_in_use",
                // Brand and theme proper names.
                "settings/appearance_name_bamboo",
                "settings/appearance_name_dusk",
                "settings/appearance_name_galaxy",
                "settings/appearance_name_ink",
                "settings/schemes_recommended_rime_ice_name",
                "settings/schemes_recommended_wanxiang_name",
                "settings/schemes_recommended_bopomofo_name",
                "settings/schemes_recommended_quick_name",
                "settings/schemes_recommended_cantonese_name",
                "settings/schemes_recommended_kagiroi_name",
            )
    }
}
