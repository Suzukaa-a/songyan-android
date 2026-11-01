package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** The imported dictionary is appended to a package dictionary's `import_tables`. */
class DictionaryStackingTest {
    private fun stacked(text: String): String {
        val output = ByteArrayOutputStream()
        DictionaryStacking.writeStacked(ByteArrayInputStream(text.toByteArray()), output)
        return output.toString(Charsets.UTF_8)
    }

    @Test
    fun aBlockListGetsTheDictionaryAfterItsLastItemAndTheRowsStayByteForByte() {
        val dict =
            "# Rime dictionary\n---\nname: rime_ice\nversion: \"2024\"\nsort: by_weight\n" +
                "import_tables:\n  - cn_dicts/8105 # chars\n  - cn_dicts/base\n...\n\n你好\tni hao\t100\n"
        assertEquals(
            "# Rime dictionary\n---\nname: rime_ice\nversion: \"2024\"\nsort: by_weight\n" +
                "import_tables:\n  - cn_dicts/8105 # chars\n  - cn_dicts/base\n  - songyan_imported\n...\n\n你好\tni hao\t100\n",
            stacked(dict),
        )
    }

    @Test
    fun flowListsAndMissingListsArePatchedToo() {
        assertEquals(
            "---\nname: a\nimport_tables: [b, c, songyan_imported]\n...\nrow\n",
            stacked("---\nname: a\nimport_tables: [b, c]\n...\nrow\n"),
        )
        assertEquals(
            "---\nname: a\nsort: by_weight\nimport_tables:\n  - songyan_imported\n...\n",
            stacked("---\nname: a\nsort: by_weight\n...\n"),
        )
        assertEquals(
            "name: a\nimport_tables:\n- b\n- songyan_imported\n...\n",
            stacked("name: a\nimport_tables:\n- b\n...\n"),
        )
    }

    @Test
    fun columnsOtherThanWordCodeWeightAndNameClashesAreRefused() {
        val columns =
            assertThrows(DictionaryStacking.StackingError::class.java) {
                stacked("---\nname: a\ncolumns:\n  - text\n  - weight\n...\n")
            }
        assertEquals(DictionaryStacking.Refusal.UNSUPPORTED_COLUMNS, columns.refusal)
        stacked("---\nname: a\ncolumns: [text, code, weight, stem]\n...\n")

        val taken =
            assertThrows(DictionaryStacking.StackingError::class.java) {
                stacked("---\nname: a\nimport_tables: [songyan_imported]\n...\n")
            }
        assertEquals(DictionaryStacking.Refusal.NAME_TAKEN, taken.refusal)
    }

    @Test
    fun aFileWithoutAHeaderEndOrWithAnOddListIsRefused() {
        for (text in listOf("name: a\n你好\tni hao\n", "---\nname: a\nimport_tables: b\n...\n")) {
            val error = assertThrows(DictionaryStacking.StackingError::class.java) { stacked(text) }
            assertEquals(DictionaryStacking.Refusal.UNSUPPORTED_HEADER, error.refusal)
        }
    }

    @Test
    fun theMainDictionaryIsThePrimaryTranslatorsOwn() {
        val schemas =
            mapOf(
                "rime_ice" to
                    (
                        "schema:\n  schema_id: rime_ice\nengine:\n  translators:\n    - punct_translator\n" +
                            "    - script_translator\n    - table_translator@custom_phrase\n" +
                            "translator:\n  dictionary: rime_ice\ncustom_phrase:\n  dictionary: \"\"\n"
                    ).toByteArray(),
                "named" to
                    "engine:\n  translators:\n    - script_translator@main\nmain:\n  dictionary: other\n".toByteArray(),
                "included" to
                    "engine:\n  translators:\n    - script_translator\ntranslator:\n  __include: x\n".toByteArray(),
                "escaping" to
                    "engine:\n  translators: [script_translator]\ntranslator: {dictionary: ../up}\n".toByteArray(),
            )
        assertEquals(mapOf("rime_ice" to "rime_ice", "named" to "other"), DictionaryStacking.mainDictionaries(schemas))
    }
}
