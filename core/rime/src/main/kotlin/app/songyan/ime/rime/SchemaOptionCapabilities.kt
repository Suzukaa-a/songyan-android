package app.songyan.ime.rime

import app.songyan.ime.keyboard.engine.BuiltinScriptState
import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.IOException

/**
 * Reads facts of expanded engine schemas: the verified simplifier switches and native script choice
 * groups, and the declared speller alphabet.
 */
object SchemaOptionCapabilities {
    /** The switches of [schemaID] as built in [compiledDir]; empty when the schema cannot be read. */
    fun read(
        resourceID: String,
        schemaID: String,
        compiledDir: File,
        sharedDir: File,
    ): List<EngineOptionDescriptor> {
        if (resourceID.isEmpty()) return emptyList()
        val yaml = deployedSchema(compiledDir, schemaID) ?: return emptyList()
        return parse(resourceID, schemaID, yaml, { true }) { config -> verifiedOpencc(config, sharedDir) }
    }

    /**
     * The speller alphabet [schemaID] declares as built in [compiledDir]; empty when it declares
     * none or cannot be read. The language table reads it to place an input method its id cannot
     * name by itself.
     */
    fun spellerAlphabet(
        compiledDir: File,
        schemaID: String,
    ): String = alphabetOf(deployedSchema(compiledDir, schemaID).orEmpty())

    /** The declared `speller/alphabet` of one deployed schema text; empty when absent. */
    internal fun alphabetOf(yaml: String): String =
        scalarField(sections(yaml)["speller"].orEmpty(), "alphabet").orEmpty()

    private fun deployedSchema(
        compiledDir: File,
        schemaID: String,
    ): String? =
        try {
            File(compiledDir, "$schemaID.schema.yaml").takeIf { it.isFile }?.readText(Charsets.UTF_8)
        } catch (_: IOException) {
            null
        }

    fun parse(
        resourceID: String,
        schemaID: String,
        yaml: String,
        openccInstalled: (String) -> Boolean,
        openccKind: (String) -> EngineOptionKind? = ::kindOf,
    ): List<EngineOptionDescriptor> {
        val sections = sections(yaml)
        val items = switchItems(sections["switches"].orEmpty())
        val conversions = linkedMapOf<String, EngineOptionKind?>()
        val declaredConfigs = linkedMapOf<String, String>()
        for (filter in filters(sections["engine"].orEmpty())) {
            val namespace =
                when {
                    filter == "simplifier" -> "simplifier"
                    filter.startsWith("simplifier@") -> filter.removePrefix("simplifier@")
                    else -> continue
                }
            val fields = sections[namespace].orEmpty()
            val name = scalarField(fields, "option_name") ?: "simplification"
            val config = scalarField(fields, "opencc_config") ?: "t2s.json"
            if (name !in conversions) {
                conversions[name] = if (openccInstalled(config)) openccKind(config) else null
                // Keep the config the schema declares: the keyboard's script rules read it instead
                // of inferring a state from the verified kind alone.
                declaredConfigs[name] = config.removeSuffix(".json")
            }
        }
        val result = mutableListOf<EngineOptionDescriptor>()
        for (item in items) {
            val name = scalarField(item, "name")
            if (name != null) {
                val kind = if (name == FULL_SHAPE) EngineOptionKind.FULL_SHAPE else conversions[name] ?: continue
                result +=
                    EngineOptionDescriptor(
                        resourceID,
                        schemaID,
                        name,
                        kind,
                        scalarField(item, "reset") in TRUE_VALUES,
                        scriptConfig =
                            declaredConfigs[name]?.takeIf {
                                kind == EngineOptionKind.TRADITIONAL || kind == EngineOptionKind.SIMPLIFIED
                            },
                    )
                continue
            }
            val options = listField(item, "options")
            val labels = listField(item, "states")
            if (options.size < 2 ||
                labels.size != options.size ||
                labels.any { it.isBlank() } ||
                options.distinct().size != options.size
            ) {
                continue
            }
            val kinds = options.mapNotNull { conversions[it] }
            if (kinds.isEmpty() ||
                kinds.any { it != EngineOptionKind.TRADITIONAL && it != EngineOptionKind.SIMPLIFIED }
            ) {
                continue
            }
            if (options.any { it in conversions && conversions[it] == null }) continue
            val reset = scalarField(item, "reset")?.toIntOrNull() ?: 0
            val knownStates = knownScriptStates(schemaID, options, labels)
            for ((index, option) in options.withIndex()) {
                result +=
                    EngineOptionDescriptor(
                        resourceID,
                        schemaID,
                        option,
                        EngineOptionKind.TRADITIONAL,
                        reset == index,
                        options,
                        labels[index],
                        scriptConfig = declaredConfigs[option],
                        knownScriptState = knownStates.getOrNull(index),
                    )
            }
        }
        return result.distinctBy { it.name }
    }

    private fun switchItems(lines: List<String>): List<List<String>> {
        val depth =
            lines.filter { it.trimStart().startsWith("- ") }.minOfOrNull { it.length - it.trimStart().length }
                ?: return emptyList()
        val items = mutableListOf<MutableList<String>>()
        for (line in lines) {
            if (line.length - line.trimStart().length == depth && line.trimStart().startsWith("- ")) {
                items += mutableListOf(" ".repeat(depth + 2) + line.trimStart().removePrefix("- "))
            } else if (items.isNotEmpty()) {
                items.last().add(line)
            }
        }
        return items
    }

    private fun listField(
        lines: List<String>,
        key: String,
    ): List<String> {
        val inline = scalarField(lines, key)
        if (inline != null && inline.startsWith("[") && inline.endsWith("]")) {
            return inline
                .drop(1)
                .dropLast(1)
                .split(',')
                .map(::scalar)
                .filter { it.isNotEmpty() }
        }
        val start = lines.indexOfFirst { it.trim() == "$key:" }
        if (start < 0) return emptyList()
        val depth = lines[start].length - lines[start].trimStart().length
        return lines
            .drop(start + 1)
            .takeWhile {
                it.length - it.trimStart().length > depth ||
                    (it.length - it.trimStart().length == depth && it.trim().startsWith("- "))
            }.filter { it.trim().startsWith("- ") }
            .map { scalar(it.trim().removePrefix("- ")) }
    }

    internal fun verifiedOpencc(
        config: String,
        sharedDir: File,
    ): EngineOptionKind? {
        val root = File(sharedDir, "opencc").canonicalFile

        fun resolve(path: String): File? =
            File(root, path).canonicalFile.takeIf {
                it.toPath().startsWith(root.toPath()) &&
                    it.isFile
            }
        return try {
            val file = resolve(config) ?: return null
            val json = Json.parseToJsonElement(file.readText()) as? JsonObject ?: return null

            fun validDict(dict: JsonObject): Boolean =
                when ((dict["type"] as? JsonPrimitive)?.contentOrNull) {
                    "group" ->
                        (dict["dicts"] as? JsonArray)?.let { ds ->
                            ds.isNotEmpty() &&
                                ds.all { it is JsonObject && validDict(it) }
                        } ==
                            true
                    "text", "ocd2", "ocd" ->
                        (dict["file"] as? JsonPrimitive)?.contentOrNull?.let {
                            resolve(it)?.length()?.let { size ->
                                size >
                                    0
                            }
                        } ==
                            true
                    else -> false
                }
            val chain = json["conversion_chain"] as? JsonArray ?: return null
            if (chain.isEmpty() ||
                !chain.all { step ->
                    (step as? JsonObject)?.get("dict")?.let { it is JsonObject && validDict(it) } ==
                        true
                }
            ) {
                return null
            }
            val segmentation = json["segmentation"] as? JsonObject ?: return null
            if ((segmentation["dict"] as? JsonObject)?.let(::validDict) != true) return null
            val name = (json["name"] as? JsonPrimitive)?.contentOrNull.orEmpty()
            when {
                name == "Emoji" || name.endsWith(" to Emoji") -> EngineOptionKind.EMOJI
                name.contains("Simplified Chinese to Traditional Chinese") ||
                    name.contains("Traditional Chinese to Traditional Chinese") -> EngineOptionKind.TRADITIONAL
                name.contains("Traditional Chinese to Simplified Chinese") -> EngineOptionKind.SIMPLIFIED
                else -> kindOf(config)
            }
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * The bundled schemes' one exclusive four-state group, recognized by the scheme it belongs to
     * plus its complete declaration (members, order and canonical labels). A group an author defines
     * with the same option names keeps null, so its own labels stay as written; the UI words only
     * the group it can prove is the bundled one.
     */
    private fun knownScriptStates(
        schemaID: String,
        options: List<String>,
        labels: List<String>,
    ): List<BuiltinScriptState> {
        if (schemaID !in BuiltinTemplateSchemas.SOURCE_SCHEMA_IDS) return emptyList()
        if (options != BuiltinScriptState.entries.map { it.optionName }) return emptyList()
        if (labels != CANONICAL_SCRIPT_LABELS) return emptyList()
        return options.map { name -> BuiltinScriptState.entries.first { it.optionName == name } }
    }

    private fun kindOf(config: String): EngineOptionKind? =
        when (config) {
            "s2t.json", "s2tw.json", "s2hk.json", "t2tw.json", "t2hk.json", "t2hkf.json" -> EngineOptionKind.TRADITIONAL
            "t2s.json" -> EngineOptionKind.SIMPLIFIED
            "emoji.json" -> EngineOptionKind.EMOJI
            else -> null
        }

    /** Top-level keys mapped to their indented body lines. */
    private fun sections(yaml: String): Map<String, List<String>> {
        val sections = LinkedHashMap<String, MutableList<String>>()
        var section = ""
        for (line in yaml.lineSequence()) {
            val text = line.trimEnd()
            if (text.isEmpty()) continue
            if (!text.startsWith(" ") && !text.startsWith("#") && text.endsWith(":")) {
                section = text.dropLast(1)
                sections.getOrPut(section) { mutableListOf() }
            } else if (!text.startsWith("#")) {
                sections.getOrPut(section) { mutableListOf() }.add(text)
            }
        }
        return sections
    }

    private fun filters(lines: List<String>): List<String> {
        val result = mutableListOf<String>()
        var indent = -1
        for (line in lines) {
            val item = line.trim()
            val depth = line.length - line.trimStart().length
            if (indent < 0) {
                if (item == "filters:") indent = depth
                continue
            }
            when {
                item.startsWith("- ") && depth >= indent -> result += scalar(item.removePrefix("- "))
                depth <= indent -> return result
            }
        }
        return result
    }

    private fun scalarField(
        lines: List<String>,
        key: String,
    ): String? {
        val top = lines.minOfOrNull { it.length - it.trimStart().length } ?: return null
        val prefix = "$key:"
        return lines
            .firstOrNull { it.length - it.trimStart().length == top && it.trim().startsWith(prefix) }
            ?.let { scalar(it.trim().removePrefix(prefix)) }
            ?.takeIf { it.isNotEmpty() }
    }

    private fun scalar(text: String): String = text.trim().trim('"', '\'')

    /** The bundled schemes' declared labels; a custom label means the group is no longer the canonical one. */
    private val CANONICAL_SCRIPT_LABELS = listOf("简体", "繁體", "臺灣", "香港")

    private val TRUE_VALUES = setOf("1", "true")

    private const val FULL_SHAPE = "full_shape"
}
