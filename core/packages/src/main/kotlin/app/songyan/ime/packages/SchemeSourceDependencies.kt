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

    /**
     * What the tree lacks. [missing] stops the import; [optional] does not: files reached only
     * through a schema's `dependencies` (a reverse-lookup scheme librime builds when present) or a
     * dictionary's `use_preset_vocabulary`. librime deploys the input method without them and only
     * that lookup, or the preset vocabulary, is absent; packages such as 薄荷 and 白霜 rely on the
     * shared `luna_pinyin` and `essay` that a desktop Rime installs.
     */
    class Closure(
        val missing: List<String>,
        val optional: List<String>,
    )

    /** Every referenced path the tree does not contain and the import cannot go without, sorted. */
    fun missing(): List<String> = closure().missing

    fun closure(): Closure {
        val missing = sortedSetOf<String>()
        val optional = sortedSetOf<String>()
        val softPending = ArrayList<String>()
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
        var soft = false

        fun require(name: String) {
            if (!SchemeSourceCheck.safe(name)) throw Invalid(name, false, "unsafe dependency path: $name")
            if (name !in paths) {
                // A compiled-only package has no dictionary header or import_tables to traverse.
                if (name.endsWith(DICTIONARY_SUFFIX) &&
                    "build/${name.removeSuffix(DICTIONARY_SUFFIX)}.table.bin" in paths
                ) {
                    return
                }
                if (soft) optional += name else missing += name
            } else if (name !in visited) {
                pending += name
            }
        }

        fun requireSoft(name: String) {
            if (soft) {
                require(name)
            } else {
                if (!SchemeSourceCheck.safe(name)) throw Invalid(name, false, "unsafe dependency path: $name")
                softPending += name
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

        while (true) {
            if (pending.isEmpty()) {
                // The required closure is complete; what only the optional edges reach comes after it.
                if (soft || softPending.isEmpty()) break
                soft = true
                softPending.forEach(::require)
                continue
            }
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
                    visitConfig(root, relaxedDictionaryNamespaces(path, root), ::require, ::requireSoft)
                    luaReferences(root, ::require, ::requireModule)
                }

                lua -> {
                    for (module in LuaRequireScanner.modules(text)) {
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
        return Closure(missing.toList(), (optional - missing).toList())
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
        relaxed: Set<String>,
        require: (String) -> Unit,
        requireOptional: (String) -> Unit,
    ) {
        var budget = MAX_NODES
        val stack = ArrayDeque<Pair<Any?, String?>>()
        stack.addLast(root to null)
        while (stack.isNotEmpty()) {
            if (--budget < 0) throw Invalid("", false, "too many YAML nodes")
            val (node, top) = stack.removeLast()
            when (node) {
                is Map<*, *> -> {
                    for ((key, value) in node) {
                        val childTop = top ?: (key as? String) ?: ""
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
                            "dictionary" ->
                                scalar(value)?.let { name ->
                                    if (name.isNotEmpty()) {
                                        // A namespace no engine entry claims is never loaded natively,
                                        // so its dictionary stays optional; a claimed one stays required.
                                        if (top != null && top in relaxed) {
                                            requireOptional(name + DICTIONARY_SUFFIX)
                                        } else {
                                            require(name + DICTIONARY_SUFFIX)
                                        }
                                    }
                                }
                            "use_preset_vocabulary" -> if (value == true) requireOptional(PRESET_VOCABULARY)
                            "import_preset" -> scalar(value)?.let { if (it.isNotEmpty()) require("$it.yaml") }
                            "opencc_config" -> scalar(value)?.let { if (it.isNotEmpty()) require("opencc/$it") }
                            "import_tables" ->
                                (value as? List<*>)?.forEach { item ->
                                    scalar(item)?.takeIf { it.isNotEmpty() }?.let { require(it + DICTIONARY_SUFFIX) }
                                }
                            "dependencies" ->
                                (value as? List<*>)?.forEach { item ->
                                    scalar(
                                        item,
                                    )?.takeIf { it.isNotEmpty() }?.let { requireOptional(it + SCHEMA_SUFFIX) }
                                }
                        }
                        if (value is Map<*, *> || value is List<*>) stack.addLast(value to childTop)
                    }
                }

                is List<*> -> node.forEach { if (it is Map<*, *> || it is List<*>) stack.addLast(it to top) }
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

    /**
     * Root namespaces whose dictionary no native component can name: librime creates a Dictionary
     * with ticket.name_space + "/dictionary", and components exist only for the engine lists, so no
     * native component reads it. Empty whenever the engine block or a sibling custom file is not
     * fully literal; the caller then keeps the old closure. A Lua component that builds a native
     * translator for such a namespace at run time is not proven either way, which is why the edge is
     * optional and still reported, never ignored.
     */
    private fun relaxedDictionaryNamespaces(
        schemaPath: String,
        root: Any?,
    ): Set<String> {
        if (!schemaPath.endsWith(SCHEMA_SUFFIX)) return emptySet()
        val document = root as? Map<*, *> ?: return emptySet()
        // A missing or null engine block proves nothing about the deployed components.
        if (!document.containsKey("engine")) return emptySet()
        val engine = document["engine"] as? Map<*, *> ?: return emptySet()
        // Only literal lists can be read: a directive composes an engine this checker cannot see.
        for (key in engine.keys) {
            val name = key as? String ?: return emptySet()
            if (name.startsWith("__")) return emptySet()
        }
        val active = RESERVED_SECTIONS.toMutableSet()
        for ((kind, key) in ENGINE_LISTS) {
            if (!engine.containsKey(key)) continue
            val list = engine[key] as? List<*> ?: return emptySet()
            for (item in list) {
                val name = scalar(item) ?: return emptySet()
                val at = name.indexOf('@')
                val klass = if (at < 0) name else name.substring(0, at)
                // Lua components name no config namespace of their own; a native translator they
                // build at run time stays an optional lookup, never a proven one.
                if (klass.startsWith("lua_")) continue
                // A class this checker cannot interpret may remap its config namespace.
                if (klass !in KNOWN_ENGINE_COMPONENTS) return emptySet()
                if (at < 0) {
                    active += kind
                    COMPONENT_SECTIONS[klass]?.let { active += it }
                } else {
                    val namespace = name.substring(at + 1)
                    if (namespace.isEmpty() || namespace.startsWith("*")) return emptySet()
                    active += namespace
                }
            }
        }
        // A sibling custom file can mount the component, or hide a patch behind a form this checker
        // cannot model; then inactivity is not proven.
        val customPath = schemaPath.removeSuffix(SCHEMA_SUFFIX) + ".custom.yaml"
        if (customPath in paths) {
            val custom = read(customPath) ?: return emptySet()
            if (!customPatchLeavesEngineAlone(customPath, custom)) return emptySet()
        }
        return document.keys.mapNotNull { it as? String }.toSet() - active
    }

    /** Only a literal patch that leaves engine alone keeps a custom file's effect provable. */
    private fun customPatchLeavesEngineAlone(
        customPath: String,
        data: ByteArray,
    ): Boolean {
        val text = decodeUtf8(data) ?: return false
        if (text.isBlank()) return true
        val root = parseYaml(customPath, text) as? Map<*, *> ?: return false
        for ((key, value) in root) {
            when (key) {
                "patch" -> {
                    val patch = value as? Map<*, *> ?: return false
                    if (!patchKeysLeaveEngineAlone(patch)) return false
                }
                "__patch" -> {
                    // plume-style list of patch entries; only fully literal maps are modelable.
                    val list = value as? List<*> ?: return false
                    for (item in list) {
                        val entry = item as? Map<*, *> ?: return false
                        if (entry.size != 1) return false
                        val patch = entry["patch/+"] as? Map<*, *> ?: return false
                        if (!patchKeysLeaveEngineAlone(patch)) return false
                    }
                }
                else -> if ((key as? String)?.startsWith("__") == true) return false
            }
        }
        return true
    }

    private fun patchKeysLeaveEngineAlone(patch: Map<*, *>): Boolean {
        var budget = MAX_NODES
        val stack = ArrayDeque<Any?>()
        stack.addLast(patch)
        while (stack.isNotEmpty()) {
            if (--budget < 0) return false
            when (val node = stack.removeLast()) {
                is Map<*, *> ->
                    for ((key, value) in node) {
                        val name = key as? String ?: return false
                        if (name.startsWith("__")) return false
                        if (name == "engine" ||
                            name.startsWith("engine/") ||
                            name.split('/').contains("engine")
                        ) {
                            return false
                        }
                        if (value is Map<*, *> || value is List<*>) stack.addLast(value)
                    }

                is List<*> -> node.forEach { if (it is Map<*, *> || it is List<*>) stack.addLast(it) }
                else -> Unit
            }
        }
        return true
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
        private val LUA_MODULE_COMPONENT =
            Regex("""^lua_(?:processor|translator|filter|segmentor)@\*([A-Za-z0-9_./-]+)""")
        private val LUA_GLOBAL_COMPONENT =
            Regex("""^lua_(?:processor|translator|filter|segmentor)@(?!\*)([A-Za-z0-9_]+)""")
        private val HEADER_END = Regex("""(?m)^\.\.\.[ \t]*(?:#[^\r\n]*)?\r?$""")

        /** kind of an engine list to the config section a component of that kind reads. */
        private val ENGINE_LISTS =
            listOf(
                "processor" to "processors",
                "segmentor" to "segmentors",
                "translator" to "translators",
                "filter" to "filters",
            )

        /**
         * Engine component classes this checker can reason about. A class outside this set may remap
         * its config namespace the way reverse_lookup_translator does, so nothing is relaxed for a
         * schema mounting one. Keep in sync with the pinned librime / librime-lua build.
         */
        private val KNOWN_ENGINE_COMPONENTS =
            setOf(
                "abc_segmentor",
                "affix_segmentor",
                "ascii_composer",
                "ascii_segmentor",
                "charset_filter",
                "chord_composer",
                "cjk_minifier",
                "echo_translator",
                "express_editor",
                "fallback_segmentor",
                "fluency_editor",
                "fluid_editor",
                "grammar",
                "history_translator",
                "key_binder",
                "lua_filter",
                "lua_processor",
                "lua_segmentor",
                "lua_translator",
                "matcher",
                "navigator",
                "predict_translator",
                "predictor",
                "punct_segmentor",
                "punct_translator",
                "punctuator",
                "recognizer",
                "reverse_lookup_filter",
                "reverse_lookup_translator",
                "schema_list_translator",
                "script_translator",
                "selector",
                "shape_formatter",
                "shape_processor",
                "simplifier",
                "single_char_filter",
                "speller",
                "switch_translator",
                "t9_processor",
                "table_translator",
                "uniquifier",
            )

        /**
         * Root config sections a native component reads without an alias, or that this checker must
         * keep strict: translator/dictionary is what deployment compiles, whatever the engine says.
         */
        private val RESERVED_SECTIONS =
            setOf(
                "schema",
                "engine",
                "switches",
                "speller",
                "punctuator",
                "recognizer",
                "key_binder",
                "editor",
                "menu",
                "style",
                "translator",
                "reverse_lookup",
                "history",
                "simplifier",
                "var",
                "__build_info",
            )

        /** A gear constructed without an alias reads its own section instead of the component kind. */
        private val COMPONENT_SECTIONS =
            mapOf(
                "history_translator" to "history",
                "matcher" to "recognizer",
                "recognizer" to "recognizer",
                "reverse_lookup_filter" to "reverse_lookup",
                "reverse_lookup_translator" to "reverse_lookup",
                "simplifier" to "simplifier",
            )

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
