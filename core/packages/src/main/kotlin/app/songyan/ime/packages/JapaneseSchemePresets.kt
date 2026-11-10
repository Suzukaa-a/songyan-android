package app.songyan.ime.packages

import java.io.File

/**
 * The install-time Rime patch for the frozen kagiroi package.
 *
 * The scheme binds minus/equal to paging while a menu is open, which takes the romaji long-vowel
 * key away from the speller, and its half-width comma and period are candidate lists instead of
 * direct commits. Both are frontend concerns, so the install writes a `*.custom.yaml` beside the
 * package bytes; the package sources are never edited, and a source that does not install kagiroi
 * is left alone.
 */
object JapaneseSchemePresets {
    private const val SCHEMA_ID = "kagiroi"
    private const val SCHEMA_FILE = SCHEMA_ID + ".schema.yaml"
    private const val PATCH_FILE = SCHEMA_ID + ".custom.yaml"

    /** The preset file the source schema patches for its paging bindings. */
    private const val PAGING_PRESET = "key_bindings/paging_with_minus_equal"
    private const val HALF_SHAPE_COMMA = "punctuator/half_shape/,"
    private const val HALF_SHAPE_PERIOD = "punctuator/half_shape/."

    /**
     * Adds or extends the scheme's patch file in [directory]. The package's own files stay byte for
     * byte, and an existing custom file keeps the entries it already has.
     */
    fun supplement(directory: File) {
        val schemaFile = File(directory, SCHEMA_FILE)
        if (!schemaFile.isFile) return
        val schema = readDocument(readText(schemaFile) ?: return).asStringMap() ?: return
        val keyBinder = schema["key_binder"].asStringMap() ?: return

        val patch = LinkedHashMap<String, Any?>()
        patch[HALF_SHAPE_COMMA] = mapOf("commit" to "、")
        patch[HALF_SHAPE_PERIOD] = mapOf("commit" to "。")
        pagingPatch(keyBinder, directory)?.let { patch.putAll(it) }

        val patchFile = File(directory, PATCH_FILE)
        val existing =
            if (patchFile.isFile) {
                // A patch that cannot be read is left alone, like one that does not parse:
                // rewriting it would drop entries the author put there.
                try {
                    patchFile.readBytes()
                } catch (_: java.io.IOException) {
                    return
                }
            } else {
                null
            }
        patchFile.writeBytes(mergedPatch(existing, patch) ?: return)
    }

    /**
     * The paging half of the patch, which depends on the shape of the package.
     *
     * The source package keeps key_binder/bindings as an in-place `__patch` of preset files whose
     * content the frontend cannot flatten, so emptying that preset's `__append` is the only
     * declarative removal. A prebuilt package already carries one flat list, and its import_preset
     * has to go with it: librime's legacy preset link appends the frontend prelude's default.yaml
     * (which links the paging preset again) after custom patches, and a null patch entry is dropped
     * before it applies, so the section is replaced as a whole.
     *
     * The build branch is the third shape: the in-place `__patch` of the source package without the
     * package's own default.yaml, so import_preset reaches the frontend prelude's default.yaml, which
     * links the paging pair again and keeps minus bound whenever a menu is open. Every preset link of
     * both files is expanded instead and the section replaced as a whole, as for a prebuilt package.
     */
    private fun pagingPatch(
        keyBinder: Map<String, Any?>,
        directory: File,
    ): Map<String, Any?>? {
        val bindings = keyBinder["bindings"] ?: return null
        if (bindings is List<*> && bindings.isNotEmpty()) {
            val list = bindings.map { it.asStringMap() ?: return null }
            val kept = list.filterNot(::isPagingBinding)
            if (kept.size >= list.size) return null
            return mapOf("key_binder" to mapOf("bindings" to kept))
        }
        val inPlace = bindings.asStringMap() ?: return null
        val path = pagingPresetPath(inPlace["__patch"]) ?: return null
        val importedBindings =
            (keyBinder["import_preset"] as? String)
                ?.let { loadMapping(File(directory, "$it.yaml")) }
                ?.get("key_binder")
                .asStringMap()
                ?.get("bindings")
        if (importedBindings != null && linksPaging(importedBindings)) {
            val before = expanded(importedBindings, directory) ?: return null
            val own = expanded(bindings, directory) ?: return null
            val list = before + own
            val kept = list.filterNot(::isPagingBinding)
            if (kept.size >= list.size) return null
            return mapOf("key_binder" to mapOf("bindings" to kept))
        }
        return mapOf(path + "/__append" to emptyList<Any>())
    }

    /** Whether a bindings node links the paging preset, by name, through `__patch`. */
    private fun linksPaging(bindings: Any): Boolean =
        links(bindings.asStringMap()?.get("__patch")).any {
            (it as? String)?.endsWith("/paging_with_minus_equal") ==
                true
        }

    /**
     * A bindings node as the flat list librime builds from it: a plain list as it is, an in-place
     * `__patch` as the `__append` lists of the presets it links, in order. Null when a link does not
     * resolve to such a list, so nothing is guessed.
     */
    private fun expanded(
        bindings: Any,
        directory: File,
    ): List<Map<String, Any?>>? {
        if (bindings is List<*>) return mappings(bindings)
        val inPlace = bindings.asStringMap()?.takeIf { it.size == 1 } ?: return null
        val result = ArrayList<Map<String, Any?>>()
        for (link in links(inPlace["__patch"])) {
            val reference = link as? String ?: return null
            val separator = reference.indexOf(":/")
            if (separator < 0) return null
            val document = loadMapping(File(directory, reference.substring(0, separator) + ".yaml")) ?: return null
            var node: Any? = document
            for (key in reference.substring(separator + 2).split('/').filter { it.isNotEmpty() }) {
                node = node.asStringMap()?.get(key) ?: return null
            }
            val items = node.asStringMap()?.get("__append") ?: node
            result += mappings(items as? List<*> ?: return null) ?: return null
        }
        return result
    }

    private fun links(directive: Any?): List<Any?> = directive as? List<*> ?: listOf(directive)

    private fun mappings(list: List<*>): List<Map<String, Any?>>? = list.map { it.asStringMap() ?: return null }

    private fun loadMapping(file: File): Map<String, Any?>? =
        file
            .takeIf { it.isFile }
            ?.let(::readText)
            ?.let(::readDocument)
            .asStringMap()

    private fun pagingPresetPath(directive: Any?): String? =
        when (directive) {
            is List<*> -> directive.firstNotNullOfOrNull { pagingPresetPath(it) }
            is String -> {
                if (!directive.contains(PAGING_PRESET)) return null
                val separator = directive.indexOf(":/")
                if (separator < 0) return null
                directive.substring(separator + 2).trim('/')
            }
            else -> null
        }

    private fun isPagingBinding(binding: Map<String, Any?>): Boolean {
        val accept = binding["accept"] as? String
        if (accept != "minus" && accept != "equal") return false
        val send = binding["send"] as? String ?: return false
        return send == "Page_Up" || send == "Page_Down"
    }

    /**
     * The installed custom file with the patch entries added. A file that does not load as a
     * mapping is left as it is: an unreadable author patch is not worth overwriting.
     */
    private fun mergedPatch(
        existing: ByteArray?,
        patch: Map<String, Any?>,
    ): ByteArray? {
        val root = LinkedHashMap<String, Any?>()
        if (existing != null) {
            val text =
                try {
                    existing.decodeToString(throwOnInvalidSequence = true)
                } catch (_: java.nio.charset.CharacterCodingException) {
                    return null
                }
            root.putAll(readDocument(text).asStringMap() ?: return null)
        }
        val entries = LinkedHashMap<String, Any?>()
        root["patch"].asStringMap()?.let(entries::putAll)
        entries.putAll(patch)
        root["patch"] = entries
        return try {
            YamsYaml.dump(root).toByteArray(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /** Unreadable or invalid UTF-8 stays untouched so the importer can reject the original bytes. */
    private fun readText(file: File): String? =
        try {
            file.readBytes().decodeToString(throwOnInvalidSequence = true)
        } catch (_: java.io.IOException) {
            null
        }

    /** A loaded document as String-keyed containers with plain scalars, or null when it does not load. */
    private fun readDocument(text: String): Any? =
        try {
            normalize(BoundedYaml.load(text))
        } catch (_: Exception) {
            null
        }

    /**
     * A parsed document rebuilt from plain containers, so it can be merged and dumped again
     * whatever the parser produced. A key that is not a name is dropped, because such a mapping
     * cannot take part in a Rime patch.
     */
    private fun normalize(value: Any?): Any? =
        when (value) {
            is Map<*, *> -> {
                val result = LinkedHashMap<String, Any?>(value.size * 2)
                for ((key, item) in value) if (key is String) result[key] = normalize(item)
                result
            }
            is List<*> -> value.map { normalize(it) }
            else -> value
        }
}
