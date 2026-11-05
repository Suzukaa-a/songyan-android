package app.songyan.ime.ime

import java.io.File

/**
 * Adds two real schemas ahead of the built-in pair: one the input-language table reads as
 * Japanese and one it reads as traditional Chinese (Zhuyin). They reuse the built-in dictionary,
 * so they prove the keyboard rules, not the full Japanese or Zhuyin input experience.
 */
internal fun addInputLanguageFixtureSchemes(source: File) {
    for ((id, name) in listOf("kagiroi_test" to "カギロイ", "bopomofo_test" to "注音測試")) {
        File(source, id + ".schema.yaml").writeText(fixtureSchema(id, name))
    }
    val default = File(source, "default.yaml")
    default.writeText(
        default.readText().replace(
            "schema_list:",
            "schema_list:\n  - schema: kagiroi_test\n  - schema: bopomofo_test",
        ),
    )
}

private fun fixtureSchema(
    id: String,
    name: String,
): String =
    """
    schema:
      schema_id: $id
      name: $name
      version: "0.0.1"
    engine:
      processors:
        - speller
        - selector
        - express_editor
      segmentors:
        - abc_segmentor
      translators:
        - script_translator
      filters:
        - uniquifier
    speller:
      alphabet: zyxwvutsrqponmlkjihgfedcba
      algebra: []
    translator:
      dictionary: songyan_basic
      prism: $id
      user_dict: shared_$id
      enable_user_dict: false
    """.trimIndent() + "\n"
