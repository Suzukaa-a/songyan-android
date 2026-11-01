package app.songyan.ime.rime

import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.keyboard.engine.EngineOptionKind
import java.io.File
import java.io.IOException

/**
 * Reads which schema switches the keyboard offers, from librime's emitted (expanded)
 * `build/<schema>.schema.yaml`.
 *
 * Deliberately conservative: a switch is offered only when a `simplifier` filter drives it and its
 * OpenCC config is one the keyboard can name and that is installed, or when it is `full_shape`, which
 * librime's always-loaded shape processor and formatter honor. Source YAML includes and patches,
 * Lua filters, other OpenCC transforms and radio groups are not interpreted, so the panel never shows a
 * switch whose effect it cannot describe. `ascii_mode` is the 中/英 key and is never listed.
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
        val yaml =
            try {
                File(compiledDir, "$schemaID.schema.yaml").takeIf { it.isFile }?.readText(Charsets.UTF_8)
            } catch (_: IOException) {
                null
            } ?: return emptyList()
        return parse(resourceID, schemaID, yaml) { config -> File(sharedDir, "opencc/$config").isFile }
    }

    fun parse(
        resourceID: String,
        schemaID: String,
        yaml: String,
        openccInstalled: (String) -> Boolean,
    ): List<EngineOptionDescriptor> {
        val sections = sections(yaml)
        val switches = switches(sections["switches"].orEmpty())
        val result = mutableListOf<EngineOptionDescriptor>()
        for (filter in filters(sections["engine"].orEmpty())) {
            val namespace =
                when {
                    filter == "simplifier" -> "simplifier"
                    filter.startsWith("simplifier@") -> filter.removePrefix("simplifier@")
                    else -> continue
                }
            val fields = sections[namespace].orEmpty()
            val name = scalarField(fields, "option_name") ?: "simplification"
            val reset = switches[name] ?: continue
            val config = scalarField(fields, "opencc_config") ?: continue
            if (!openccInstalled(config)) continue
            val kind = kindOf(config) ?: continue
            if (result.any { it.name == name }) continue
            result += EngineOptionDescriptor(resourceID, schemaID, name, kind, defaultValue = reset)
        }
        switches[FULL_SHAPE]?.let { reset ->
            result += EngineOptionDescriptor(resourceID, schemaID, FULL_SHAPE, EngineOptionKind.FULL_SHAPE, reset)
        }
        return result
    }

    private fun kindOf(config: String): EngineOptionKind? =
        when (config) {
            "s2t.json", "s2tw.json", "s2hk.json" -> EngineOptionKind.TRADITIONAL
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

    /** Switch names with their reset values; a radio group (`options:`) has no `name` and is skipped. */
    private fun switches(lines: List<String>): Map<String, Boolean> {
        val found = LinkedHashMap<String, Boolean>()
        var current: String? = null
        for (line in lines) {
            val item = line.trim()
            if (item.startsWith("- ")) {
                current = null
                val body = item.removePrefix("- ").trim()
                if (body.startsWith("name:")) {
                    current = scalar(body.removePrefix("name:"))
                    found[current] = false
                }
            } else if (current != null && item.startsWith("reset:")) {
                found[current] = scalar(item.removePrefix("reset:")) in TRUE_VALUES
            }
        }
        return found
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

    private val TRUE_VALUES = setOf("1", "true")

    private const val FULL_SHAPE = "full_shape"
}
