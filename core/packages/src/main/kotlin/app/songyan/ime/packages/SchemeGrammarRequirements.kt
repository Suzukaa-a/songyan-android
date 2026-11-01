package app.songyan.ime.packages

import java.io.File

/**
 * Install-time discovery of the grammar models (`.gram`) a scheme package's configuration names.
 *
 * It only reads the configuration the engine would compile for the installed input methods, never a
 * recipe's own declarations, and never claims a file is compatible: the deployed configuration and
 * the live model are checked later. This is a bounded projection of the grammar and schema-list
 * entries, not a second config compiler: an expression it cannot resolve becomes a diagnostic, not a
 * mandatory download. Mirrors the iOS `SchemeGrammarRequirements`.
 */
object SchemeGrammarRequirements {
    /** [paths] are the `.gram` files named, sorted; [diagnostics] say what could not be decided. */
    data class Discovery(
        val paths: List<String>,
        val diagnostics: List<String>,
    )

    /** Curated upstream location of the models the popular packages use; offered, never fetched on its own. */
    private val SUGGESTED_MODELS = setOf("wanxiang-lts-zh-hans.gram", "wanxiang-lts-zh-hant.gram")
    private const val SUGGESTED_BASE = "https://github.com/amzxyz/RIME-LMDG/releases/download/LTS/"

    /** Published size of the suggested models, which the download button states. */
    const val SUGGESTED_MODEL_MEGABYTES: Int = 420

    fun suggestedUrl(path: String): String? = if (path in SUGGESTED_MODELS) SUGGESTED_BASE + path else null

    /** [paths] are the package's relative file paths; [read] returns a file's bytes, or null when unreadable. */
    fun discover(
        paths: Set<String>,
        read: (String) -> ByteArray?,
    ): Discovery = Resolver(paths, read).run()

    /**
     * The grammar models the installed source folder [root] names but does not hold, sorted. Unreadable
     * or oversized configuration files count as undecidable, like any other expression this cannot
     * resolve.
     */
    fun missingModels(root: File): List<String> {
        val files =
            root
                .walkTopDown()
                .filter { it.isFile }
                .associateBy { it.relativeTo(root).invariantSeparatorsPath }
        val found =
            discover(files.keys) { path ->
                files[path]?.takeIf { it.length() <= MAXIMUM_CONFIG_BYTES }?.let {
                    runCatching { it.readBytes() }
                        .getOrNull()
                }
            }
        return found.paths.filter { it !in files }
    }

    private class Unknown : Exception()

    private class Resolver(
        private val paths: Set<String>,
        private val read: (String) -> ByteArray?,
    ) {
        private val resolving = HashSet<String>()
        private val resolvingScalars = HashSet<String>()

        private val found = sortedSetOf<String>()
        private val diagnostics = ArrayList<String>()
        private val visited = HashSet<String>()

        fun run(): Discovery {
            val entries = ArrayList<String>()
            try {
                if ("default.yaml" in paths) {
                    val list =
                        (configuration("default")["schema_list"] as? List<*>)?.map { it.asStringMap() }
                            ?: throw Unknown()
                    entries += list.map { it?.get("schema") as? String ?: throw Unknown() }
                } else {
                    // Same fallback as the engine when the source has no default.yaml.
                    entries +=
                        paths
                            .filter { !it.contains('/') && it.endsWith(SCHEMA_SUFFIX) }
                            .map { it.removeSuffix(SCHEMA_SUFFIX) }
                }
            } catch (_: Unknown) {
                diagnostics += "which input methods are enabled is only known at install time"
            }
            for (entry in entries.sorted()) visit(entry)
            return Discovery(found.toList(), diagnostics)
        }

        private fun visit(id: String) {
            if (!visited.add(id) || visited.size > MAXIMUM_SCHEMAS) return
            try {
                val config = configuration(id + ".schema")
                config["grammar"].asStringMap()?.let { grammar ->
                    val language = evaluate(grammar)["language"]?.let { scalar(it) }
                    if (!language.isNullOrEmpty()) {
                        val path = language + ".gram"
                        if (!GrammarModelPath.isValid(path)) throw Unknown()
                        found += path
                    }
                }
                config["schema"].asStringMap()?.let { schema ->
                    for (dependency in evaluate(schema)["dependencies"].asStringList().orEmpty()) visit(dependency)
                }
            } catch (_: Unknown) {
                diagnostics += "$id: the models it needs are only known at install time"
            }
        }

        private fun configuration(name: String): Map<String, Any?> = reference("$name:/")

        private fun reference(text: String): Map<String, Any?> = referenceValue(text).asStringMap() ?: throw Unknown()

        private fun referenceValue(text: String): Any? {
            val colon = text.indexOf(':')
            if (colon < 0) throw Unknown()
            val name = text.substring(0, colon)
            val file = if (name.endsWith(".yaml")) name else "$name.yaml"
            // Recipes are executable inputs to the planner, never config includes.
            if (file.endsWith(".recipe.yaml") ||
                !SchemeSourceCheck.safe(file) ||
                text in resolving ||
                resolving.size >= MAXIMUM_DEPTH
            ) {
                throw Unknown()
            }
            if (file !in paths) {
                if (text.endsWith("?")) return emptyMap<String, Any?>()
                throw Unknown()
            }
            resolving += text
            try {
                val bytes = read(file) ?: throw Unknown()
                val loaded =
                    try {
                        BoundedYaml.load(decodeUtf8(bytes) ?: throw Unknown())
                    } catch (_: Unknown) {
                        throw Unknown()
                    } catch (_: Exception) {
                        // Unparseable or over-budget configuration is "cannot decide", never a crash.
                        throw Unknown()
                    } ?: throw Unknown()
                var value: Any? = loaded
                loaded.asStringMap()?.let { map ->
                    val effective = evaluate(map).toMutableMap()
                    // A schema's own custom file is part of its effective configuration, so a
                    // reference to the schema sees it before a subtree is selected.
                    if (file.endsWith(SCHEMA_SUFFIX) || file == "default.yaml") {
                        val customName =
                            if (file.endsWith(
                                    SCHEMA_SUFFIX,
                                )
                            ) {
                                file.removeSuffix(SCHEMA_SUFFIX)
                            } else {
                                "default"
                            }
                        if ("$customName.custom.yaml" in paths) {
                            val custom = reference("$customName.custom:/")
                            if (custom.containsKey("patch")) patch(custom["patch"], effective)
                            patch(custom.filterKeys { it in CUSTOM_KEYS }, effective)
                        }
                    }
                    value = effective
                }
                val path = text.substring(colon + 1).trim { it == '/' || it == '?' }
                for (key in path.split('/').filter { it.isNotEmpty() }) {
                    val node = value.asStringMap()
                    if (node == null || !node.containsKey(key)) {
                        if (text.endsWith("?")) return emptyMap<String, Any?>()
                        throw Unknown()
                    }
                    value = node[key]
                }
                val node = value.asStringMap()
                if (path.isNotEmpty() && node != null) {
                    // Resolve the selected node while the reference is still on the stack: this
                    // permits chains ending in a scalar and detects cycles before the guard unwinds.
                    val include = node["__include"]
                    if (node.size == 1 && include is String) return referenceValue(include)
                    return evaluate(node)
                }
                return value
            } finally {
                resolving -= text
            }
        }

        private fun scalar(value: Any?): String? {
            if (value == null) return null
            if (value is String) return value
            val node = value.asStringMap()
            val include = node?.get("__include")
            if (node != null && node.size == 1 && include is String) {
                if (resolvingScalars.size >= MAXIMUM_DEPTH || !resolvingScalars.add(include)) throw Unknown()
                try {
                    return scalar(referenceValue(include))
                } finally {
                    resolvingScalars -= include
                }
            }
            // A present non-scalar language is not evidence of "no grammar".
            throw Unknown()
        }

        private fun patch(
            value: Any?,
            into: MutableMap<String, Any?>,
        ) {
            when {
                value is String -> patch(reference(value), into)
                value is List<*> -> value.forEach { patch(it, into) }
                value.asStringMap() != null -> {
                    val map = value.asStringMap()!!
                    for (key in map.keys.sorted()) {
                        val entry = map[key]
                        when {
                            key == "patch/+" || key == "patch" -> patch(entry, into)
                            key in TOP_LEVEL -> into[key] = entry
                            key == "grammar/language" -> {
                                val grammar = (into["grammar"].asStringMap() ?: emptyMap()).toMutableMap()
                                grammar["language"] = entry
                                into["grammar"] = grammar
                            }
                            key == "schema/dependencies" -> {
                                val schema = (into["schema"].asStringMap() ?: emptyMap()).toMutableMap()
                                schema["dependencies"] = entry
                                into["schema"] = schema
                            }
                            // A patch of something finer than this projection reads is not decidable here.
                            isUndecidable(key) -> throw Unknown()
                        }
                    }
                }
                else -> throw Unknown()
            }
        }

        private fun isUndecidable(key: String): Boolean =
            key.startsWith("grammar") ||
                key.startsWith("language") ||
                key.startsWith("schema_list") ||
                key.startsWith("schema/dependencies") ||
                key == "__patch" ||
                key == "__include"

        private fun evaluate(map: Map<String, Any?>): Map<String, Any?> {
            var result: Map<String, Any?> = emptyMap()
            if (map.containsKey("__include")) {
                result = reference(map["__include"] as? String ?: throw Unknown())
            }
            result = merge(result, map.filterKeys { !it.startsWith("__") })
            if (map.containsKey("__patch")) {
                val mutable = result.toMutableMap()
                patch(map["__patch"], mutable)
                result = mutable
            }
            return result
        }

        private fun merge(
            lhs: Map<String, Any?>,
            rhs: Map<String, Any?>,
        ): Map<String, Any?> {
            val result = lhs.toMutableMap()
            for ((key, value) in rhs) {
                val a = result[key].asStringMap()
                val b = value.asStringMap()
                result[key] = if (a != null && b != null) merge(a, b) else value
            }
            return result
        }
    }

    private const val SCHEMA_SUFFIX = ".schema.yaml"
    private const val MAXIMUM_CONFIG_BYTES = 8L * 1024 * 1024
    private const val MAXIMUM_DEPTH = 128
    private const val MAXIMUM_SCHEMAS = 256
    private val TOP_LEVEL = setOf("grammar", "schema_list", "schema", "language")
    private val CUSTOM_KEYS = setOf("grammar", "grammar/language", "schema_list", "schema")
}
