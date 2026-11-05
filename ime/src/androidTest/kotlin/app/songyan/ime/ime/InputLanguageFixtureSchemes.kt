package app.songyan.ime.ime

import java.io.File

/**
 * Adds two real schemas ahead of the built-in pair: one the input-language table reads as
 * Japanese and one it reads as traditional Chinese (Zhuyin). They reuse the built-in dictionary,
 * so they prove the keyboard rules, not the full Japanese or Zhuyin input experience.
 *
 * The Zhuyin fixture also declares the letter-to-symbol preedit format a real bopomofo schema
 * declares, so the engine spells its composition in bopomofo itself: the non-ASCII marked text a
 * test sees comes from the engine, never from a string the test made up.
 */
internal fun addInputLanguageFixtureSchemes(source: File) {
    for ((id, name) in listOf("kagiroi_test" to "カギロイ", "bopomofo_test" to "注音測試")) {
        File(source, id + ".schema.yaml").writeText(fixtureSchema(id, name, bopomofo = id == BOPOMOFO_SCHEMA))
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
    bopomofo: Boolean,
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
    """.trimIndent() + (if (bopomofo) BOPOMOFO_PREEDIT_FORMAT else "") + "\n"

internal const val BOPOMOFO_SCHEMA: String = "bopomofo_test"

/** The composition [BOPOMOFO_SCHEMA] spells when `ni` is typed: the bopomofo syllable ㄋㄧ. */
internal const val BOPOMOFO_NI: String = "ㄋㄧ"

/**
 * The projection a real bopomofo schema declares: the engine spells its own composition as
 * bopomofo symbols instead of the typed key codes. The pairs are the ones the bundled dictionary
 * has a word for (`ni` is 你), so the composition has a highlighted candidate to format and
 * `ni` renders as [BOPOMOFO_NI] without any text supplied by the test.
 */
private const val BOPOMOFO_PREEDIT_FORMAT =
    "\n  preedit_format:\n" +
        "    - xlit/ni/ㄋㄧ/\n"
