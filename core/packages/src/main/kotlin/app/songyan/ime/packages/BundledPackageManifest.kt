package app.songyan.ime.packages

/**
 * Description file of a bundled scheme package (`Resources/BuiltinPackages/<name>/manifest.yaml`),
 * parsed and validated from in-memory bytes. Ported from the iOS `prepareBundledPackage` read.
 *
 * The iOS loader validates only the identity fields it needs — `id`, `name` and a non-empty
 * `entrypoints/schemas` list — and the remaining fields are carried as data for the install layer.
 * Keeping the same split means a manifest iOS accepts is never rejected here.
 */
data class BundledPackageManifest(
    val formatVersion: Int?,
    val id: String,
    val version: String?,
    val kind: String?,
    val name: String,
    val minimumAppVersion: String?,
    val requiresEngineFeatures: List<String>,
    val entrypointSchemas: List<String>,
    val dictionaries: List<Dictionary>,
) {
    /** One dictionary the package ships and the user dictionary it feeds. */
    data class Dictionary(
        val name: String,
        val file: String,
        val sharedUserDict: String?,
        val note: String?,
    )

    companion object {
        private const val MANIFEST_FILE_NAME = "manifest.yaml"

        /** Parses one manifest; every failure is [RecipeInstallError]. */
        fun parse(source: ByteArray): BundledPackageManifest {
            if (source.size > RecipeResourceBudget.SOURCE_FILE) {
                throw RecipeInstallError.InvalidManifest("manifest exceeds the source file size limit")
            }
            val text =
                decodeUtf8(source)
                    ?: throw RecipeInstallError.InvalidManifest("manifest is not UTF-8")
            val parsed =
                try {
                    BoundedYaml.load(text).asStringMap()
                } catch (error: Exception) {
                    // The KDoc promises every failure is a RecipeInstallError; a YAML syntax error
                    // must not escape as a snakeyaml exception.
                    throw RecipeInstallError.InvalidManifest(
                        "manifest YAML is invalid: " + (error.message ?: "parse error"),
                    )
                }
            val root: Map<String, Any?> =
                parsed ?: throw RecipeInstallError.InvalidManifest("manifest is not a mapping")
            val id = root["id"] as? String
            val name = root["name"] as? String
            val entries = root["entrypoints"].asStringMap()?.get("schemas").asStringList()
            if (id == null || name == null || entries.isNullOrEmpty()) {
                throw RecipeInstallError.InvalidManifest("built-in input package manifest is invalid")
            }
            val requires = root["requires"].asStringMap()
            val dictionaries = mutableListOf<Dictionary>()
            // A dropped entry would install the package without its dictionary and without an
            // error, so a structurally wrong list is refused instead of being skipped.
            val rawDictionaries =
                when (val node = root["dictionaries"]) {
                    null -> emptyList<Any?>()
                    is List<*> -> node
                    else -> throw RecipeInstallError.InvalidManifest("manifest dictionaries must be a list")
                }
            for (element in rawDictionaries) {
                val entry =
                    element.asStringMap()
                        ?: throw RecipeInstallError.InvalidManifest("manifest dictionary entry is not a mapping")
                val dictionaryName =
                    entry["name"] as? String
                        ?: throw RecipeInstallError.InvalidManifest("manifest dictionary entry is missing its name")
                val file =
                    entry["file"] as? String
                        ?: throw RecipeInstallError.InvalidManifest(
                            "manifest dictionary " + dictionaryName + " is missing its file",
                        )
                dictionaries.add(
                    Dictionary(
                        name = dictionaryName,
                        file = file,
                        sharedUserDict = entry["sharedUserDict"] as? String,
                        note = entry["note"] as? String,
                    ),
                )
            }
            return BundledPackageManifest(
                formatVersion = (root["formatVersion"] as? Number)?.toInt(),
                id = id,
                version = root["version"]?.let { describeScalar(it) },
                kind = root["kind"] as? String,
                name = name,
                minimumAppVersion = root["minimumAppVersion"]?.let { describeScalar(it) },
                requiresEngineFeatures = requires?.get("engineFeatures").asStringList() ?: emptyList(),
                entrypointSchemas = entries,
                dictionaries = dictionaries,
            )
        }

        /**
         * The YAML sources a bundled package installs: the manifest and every file the keyboard
         * bundle does not own are dropped, only entry schemas are kept, and `default.yaml` keeps
         * only the entry schemas in its `schema_list`.
         *
         * [files] is the package directory as a path-to-bytes map; the manifest itself is read by
         * [parse] and is never part of the installed tree.
         */
        fun selectInstallFiles(
            manifest: BundledPackageManifest,
            files: Map<String, ByteArray>,
        ): Map<String, ByteArray> {
            val entries = manifest.entrypointSchemas.toSet()
            val bytes = LinkedHashMap<String, ByteArray>()
            for ((path, data) in files) {
                if (path == MANIFEST_FILE_NAME || !path.endsWith(".yaml")) continue
                if (path.endsWith(".schema.yaml") && path.dropLast(12) !in entries) continue
                bytes[path] = data
            }
            val defaults =
                bytes["default.yaml"]
                    ?: throw RecipeInstallError.InvalidManifest("built-in package is missing default.yaml")
            val kept =
                splitLinesKeepingEmpty(decodeUtf8Lenient(defaults)).filter { line ->
                    val item = line.trim { it.isWhitespace() }
                    if (!item.startsWith("- schema:")) return@filter true
                    item.drop(9).trim { it.isWhitespace() } in entries
                }
            bytes["default.yaml"] = kept.joinToString("\n").toByteArray(Charsets.UTF_8)
            return bytes
        }
    }
}
