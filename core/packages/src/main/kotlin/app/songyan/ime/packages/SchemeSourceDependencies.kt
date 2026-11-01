package app.songyan.ime.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The files a Rime source tree needs, found the way librime resolves them. Ported from the iOS
 * `SchemeSourceCheck.missingDependencies`: a scheme that deploys with a missing dependency only
 * fails at runtime (no candidates, a crashed Lua component), so imports check the closure first.
 *
 * [read] returns a file's bytes, or null when the tree has no such file; only YAML, Lua and
 * OpenCC JSON files are ever read, and each is already bounded by the import budget.
 */
internal class SchemeSourceDependencies(
    private val paths: Set<String>,
    private val read: (String) -> ByteArray?,
) {
    /** Thrown when a dependency path itself is unsafe or a config cannot be read as UTF-8 / YAML. */
    class Invalid(
        val path: String,
        val encoding: Boolean,
        message: String,
    ) : Exception(message)

    /**
     * Schema ids the source enables through `default.yaml` (with a literal `default.custom.yaml`
     * replacement applied), or null when the list cannot be read literally.
     */
    fun listedSchemaIds(): List<String>? {
        val defaults = paths.takeIf { DEFAULT_CONFIG in it }?.let { read(DEFAULT_CONFIG) } ?: return null
        val root = parseConfig(DEFAULT_CONFIG, defaults) ?: return null
        var list: List<*>? = root["schema_list"] as? List<*>
        val customBytes = if (DEFAULT_CUSTOM in paths) read(DEFAULT_CUSTOM) else null
        if (customBytes != null) {
            val custom = runCatching { parseConfig(DEFAULT_CUSTOM, customBytes) }.getOrNull()
            val patch = custom?.get("patch").asStringMap()
            val patches = custom?.get("__patch") as? List<*>
            list =
                when {
                    patch != null && custom?.get("__patch") == null && patch["schema_list"] is List<*> ->
                        patch["schema_list"] as List<*>

                    custom != null &&
                        custom["patch"] == null &&
                        patches != null &&
                        patches.all {
                            (it as? Map<*, *>)?.let { item -> item.size == 1 && item["patch/+"] is Map<*, *> } ==
                                true
                        } -> {
                        var replaced = list
                        for (item in patches) {
                            val replacement = ((item as Map<*, *>)["patch/+"] as Map<*, *>)["schema_list"]
                            if (replacement is List<*>) replaced = replacement
                        }
                        replaced
                    }

                    else -> null
                }
        }
        if (list == null) return null
        val ids = list.map { (it as? Map<*, *>)?.get("schema") as? String ?: return null }
        return ids.distinct()
    }

    /** Every referenced path the tree does not contain, sorted. */
    fun missing(): List<String> {
        val missing = sortedSetOf<String>()
        var selected: Set<String>? = null
        val listed = listedSchemaIds()
        if (listed != null) {
            val present = listed.filter { (it + SCHEMA_SUFFIX) in paths }.toSet()
            listed.filter { it !in present }.forEach { missing += it + SCHEMA_SUFFIX }
            if (present.isNotEmpty()) selected = present
        }
        val pending =
            ArrayList(
                paths.filter { path ->
                    val chosen = selected
                    if (chosen != null) {
                        if (path.endsWith(SCHEMA_SUFFIX)) return@filter path.removeSuffix(SCHEMA_SUFFIX) in chosen
                        if (path.endsWith(DICTIONARY_SUFFIX)) return@filter false
                        if (path.startsWith("lua/")) return@filter false
                    }
                    (path.endsWith(".yaml") && !path.contains('/')) ||
                        path == RIME_LUA ||
                        (path.startsWith("lua/") && path.endsWith(".lua")) ||
                        (path.startsWith("opencc/") && path.endsWith(".json"))
                },
            )
        val visited = HashSet<String>()

        fun require(name: String) {
            if (!SchemeSourceCheck.safe(name)) throw Invalid(name, false, "unsafe dependency path: $name")
            if (name !in paths) {
                missing += name
            } else if (name !in visited) {
                pending += name
            }
        }

        fun requireModule(module: String) {
            val base = "lua/" + module.replace('.', '/')
            val path =
                when {
                    "$base.lua" in paths -> "$base.lua"
                    "$base/init.lua" in paths -> "$base/init.lua"
                    else -> "$base.lua"
                }
            require(path)
        }

        while (pending.isNotEmpty()) {
            val path = pending.removeAt(pending.size - 1)
            if (!visited.add(path)) continue
            val data = read(path) ?: continue
            val yaml = path.endsWith(".yaml")
            val lua = path == RIME_LUA || (path.startsWith("lua/") && path.endsWith(".lua"))
            val opencc = path.startsWith("opencc/") && path.endsWith(".json")
            if (!yaml && !lua && !opencc) continue
            val text = decodeUtf8(data) ?: throw Invalid(path, true, "$path is not UTF-8")
            when {
                yaml -> {
                    val source = if (path.endsWith(DICTIONARY_SUFFIX)) dictionaryHeader(text) else text
                    val root = parseYaml(path, source) ?: continue
                    visitConfig(root, ::require)
                    luaReferences(root, ::require, ::requireModule)
                }

                lua -> {
                    for (match in LUA_REQUIRE.findAll(text)) {
                        val module = match.groupValues[1]
                        if (module !in LUA_STANDARD_MODULES) requireModule(module)
                    }
                }

                else -> {
                    val element =
                        try {
                            Json.parseToJsonElement(text)
                        } catch (error: Exception) {
                            throw Invalid(path, false, "$path is not JSON: ${error.message}")
                        }
                    openccFiles(element) { require("opencc/$it") }
                }
            }
        }
        return missing.toList()
    }

    private fun parseConfig(
        path: String,
        data: ByteArray,
    ): Map<String, Any?>? {
        val text = decodeUtf8(data) ?: throw Invalid(path, true, "$path is not UTF-8")
        return parseYaml(path, text).asStringMap()
    }

    private fun parseYaml(
        path: String,
        text: String,
    ): Any? =
        try {
            BoundedYaml.load(text)
        } catch (error: Exception) {
            throw Invalid(path, false, "$path: ${error.message ?: "YAML error"}")
        }

    private fun visitConfig(
        root: Any?,
        require: (String) -> Unit,
    ) {
        var budget = MAX_NODES
        val stack = ArrayDeque<Any?>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (--budget < 0) throw Invalid("", false, "too many YAML nodes")
            when (val node = stack.removeLast()) {
                is Map<*, *> -> {
                    for ((key, value) in node) {
                        when (key) {
                            "__include" -> reference(value, require)
                            "__patch" ->
                                if (value is List<*>) {
                                    value.forEach {
                                        reference(
                                            it,
                                            require,
                                        )
                                    }
                                } else {
                                    reference(value, require)
                                }
                            "dictionary" -> scalar(value)?.let { if (it.isNotEmpty()) require(it + DICTIONARY_SUFFIX) }
                            "use_preset_vocabulary" -> if (value == true) require(PRESET_VOCABULARY)
                            "import_preset" -> scalar(value)?.let { if (it.isNotEmpty()) require("$it.yaml") }
                            "opencc_config" -> scalar(value)?.let { if (it.isNotEmpty()) require("opencc/$it") }
                            "import_tables", "dependencies" ->
                                (value as? List<*>)?.forEach { item ->
                                    scalar(item)?.takeIf { it.isNotEmpty() }?.let {
                                        require(it + if (key == "dependencies") SCHEMA_SUFFIX else DICTIONARY_SUFFIX)
                                    }
                                }
                        }
                        if (value is Map<*, *> || value is List<*>) stack.addLast(value)
                    }
                }

                is List<*> -> node.forEach { if (it is Map<*, *> || it is List<*>) stack.addLast(it) }
                else -> Unit
            }
        }
    }

    /** librime ConfigCompiler::CreateReference: `file:/node`, optional with a trailing `?`. */
    private fun reference(
        value: Any?,
        require: (String) -> Unit,
    ) {
        val text = scalar(value) ?: return
        val colon = text.indexOf(':')
        if (colon <= 0) return
        val name = text.substring(0, colon)
        val file = if (name.endsWith(".yaml")) name else "$name.yaml"
        if (!SchemeSourceCheck.safe(file)) throw Invalid(file, false, "unsafe dependency path: $file")
        if (!text.contains('?') || file in paths) require(file)
    }

    /** Lua components named in parsed scalars; comments often mention the legacy syntax. */
    private fun luaReferences(
        root: Any?,
        require: (String) -> Unit,
        requireModule: (String) -> Unit,
    ) {
        var budget = MAX_NODES
        val stack = ArrayDeque<Any?>()
        stack.addLast(root)
        while (stack.isNotEmpty()) {
            if (--budget < 0) throw Invalid("", false, "too many YAML nodes")
            when (val node = stack.removeLast()) {
                is Map<*, *> -> node.values.forEach { stack.addLast(it) }
                is List<*> -> node.forEach { stack.addLast(it) }
                is String -> {
                    LUA_MODULE_COMPONENT.find(node)?.let { requireModule(it.groupValues[1]) }
                    if (LUA_GLOBAL_COMPONENT.containsMatchIn(node)) require(RIME_LUA)
                }
                else -> Unit
            }
        }
    }

    private fun openccFiles(
        element: JsonElement,
        require: (String) -> Unit,
    ) {
        when (element) {
            is JsonObject -> {
                (element["file"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.let(require)
                element.values.forEach { openccFiles(it, require) }
            }
            is JsonArray -> element.forEach { openccFiles(it, require) }
            else -> Unit
        }
    }

    private fun scalar(value: Any?): String? =
        when (value) {
            null, is Map<*, *>, is List<*> -> null
            else -> value.toString()
        }

    companion object {
        const val SCHEMA_SUFFIX = ".schema.yaml"
        const val DICTIONARY_SUFFIX = ".dict.yaml"
        const val DEFAULT_CONFIG = "default.yaml"
        const val DEFAULT_CUSTOM = "default.custom.yaml"
        private const val RIME_LUA = "rime.lua"
        private const val PRESET_VOCABULARY = "essay.txt"
        private const val MAX_NODES = 4_000_000
        private val LUA_STANDARD_MODULES = setOf("math", "string", "table", "utf8", "rime")
        private val LUA_REQUIRE = Regex("""require\s*\(?\s*["']([A-Za-z0-9_./-]+)["']""")
        private val LUA_MODULE_COMPONENT =
            Regex("""^lua_(?:processor|translator|filter|segmentor)@\*([A-Za-z0-9_.]+)""")
        private val LUA_GLOBAL_COMPONENT =
            Regex("""^lua_(?:processor|translator|filter|segmentor)@(?!\*)([A-Za-z0-9_]+)""")
        private val HEADER_END = Regex("""(?m)^\.\.\.[ \t]*(?:#[^\r\n]*)?\r?$""")

        /** Dictionary rows after the YAML document terminator are tabular text, not YAML. */
        fun dictionaryHeader(text: String): String =
            HEADER_END.find(text)?.let { text.substring(0, it.range.first) } ?: text

        /** macOS Finder / Archive Utility artifacts: `__MACOSX/` trees, `._*` AppleDouble, `.DS_Store`. */
        fun isFinderMetadata(path: String): Boolean {
            val components = path.split('/').filter { it.isNotEmpty() }
            val first = components.firstOrNull() ?: return false
            val last = components.last()
            return first == "__MACOSX" || last.startsWith("._") || last == ".DS_Store"
        }
    }
}
