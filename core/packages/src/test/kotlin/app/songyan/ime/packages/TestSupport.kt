package app.songyan.ime.packages

import java.nio.file.Files
import java.nio.file.Path

/**
 * Compiled schema shaped like the sources the features are meant for, matching the iOS test
 * fixtures so translated cases assert the same behaviour.
 */
internal fun compiledSchema(
    id: String,
    translators: List<String> = listOf("script_translator"),
    alphabet: String? = "zyxwvutsrqponmlkjihgfedcba",
    namespace: String = "translator",
    algebra: List<String>? = emptyList(),
    extra: String = "",
): ByteArray {
    val lines = mutableListOf("schema:", "  schema_id: " + id, "  name: Test", "engine:", "  translators:")
    for (translator in translators) lines.add("    - " + translator)
    lines.add("speller:")
    if (alphabet != null) lines.add("  alphabet: " + alphabet)
    if (algebra != null) {
        if (algebra.isEmpty()) {
            lines.add("  algebra: []")
        } else {
            // Block style: a rule such as `derive/^([zcs])h/$1/` carries a character class,
            // which YAML cannot read inside a flow sequence.
            lines.add("  algebra:")
            for (rule in algebra) lines.add("    - \"" + rule + "\"")
        }
    }
    lines.add(namespace + ":")
    lines.add("  dictionary: test_dict")
    if (extra.isNotEmpty()) lines.add(extra)
    return (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
}

/** Compiled `default.yaml` listing the enabled schemas in order. */
internal fun compiledDefault(vararg enabled: String): ByteArray =
    ("schema_list:\n" + enabled.joinToString("") { "  - schema: " + it + "\n" }).toByteArray(Charsets.UTF_8)

/** A build directory holding `default.yaml` and the given compiled schemas. */
internal fun buildDirectory(
    enabled: List<String>,
    schemas: Map<String, ByteArray>,
): Path {
    val build = Files.createTempDirectory("fuzzy-plan-")
    Files.write(build.resolve("default.yaml"), compiledDefault(*enabled.toTypedArray()))
    for ((id, data) in schemas) Files.write(build.resolve(id + ".schema.yaml"), data)
    build.toFile().deleteOnExit()
    return build
}

/**
 * The derivations the managed block inserts, in list order, read back from the merged custom file.
 */
internal fun appended(customText: String): List<String> {
    val root = BoundedYaml.load(customText).asStringMap() ?: error("merged custom file is not a mapping")
    val patches = root["__patch"] as? List<*> ?: error("merged custom file has no __patch list")
    return patches.flatMap { patch ->
        val map = patch.asStringMap()?.get("patch/+").asStringMap() ?: emptyMap()
        map.keys
            .filter { it.startsWith(FuzzyPinyin.INSERT_KEY_PREFIX) }
            .sorted()
            .map { map[it] as String }
    }
}

/** Applies [recipe] to [existing] under the test package reference, as the recipe plan would. */
internal fun applyRecipe(
    recipe: DeclarativeRecipe,
    existing: Map<String, ByteArray>,
): RecipePatchApplication =
    recipe.applyPatches(
        existingFiles = existing,
        packageReference = "user/pkg",
        selectedRecipe = recipe.declaredName ?: error("recipe has no declared name"),
    )

/** One native `fuzz/&lt;pattern&gt;/&lt;replacement&gt;/` rule applied the way librime does it. */
internal fun applyFuzzRule(
    rule: String,
    input: String,
): String? {
    val body = rule.removePrefix("fuzz/")
    val separator = body.indexOf('/')
    if (separator < 0 || !body.endsWith("/")) return null
    val pattern = body.substring(0, separator)
    val replacement = body.substring(separator + 1, body.length - 1)
    val regex =
        try {
            Regex(pattern)
        } catch (_: Exception) {
            return null
        }
    val match = regex.find(input) ?: return null
    if (match.range.isEmpty()) return null
    val replaced = regex.replace(input, Regex.escapeReplacement(replacement))
    return if (replaced == input) null else replaced
}
