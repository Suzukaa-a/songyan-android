package app.songyan.ime.packages

/**
 * Per-schema on/off switches for the Lua components (`lua_processor@...`, `lua_segmentor@...`,
 * `lua_translator@...`, `lua_filter@...`) a source mounts in its compiled engine config. Ported
 * from the iOS `LuaComponents`.
 *
 * Pure logic, no source or scheme names. Every Lua component stays enabled unless the user turns it
 * off. librime's patch syntax cannot delete a list item, so a switched-off component is removed by
 * assigning the affected `engine/<list>` in a managed Rx block of `<schema>.custom.yaml`, computed
 * from a recorded baseline (the source's own lists, without our managed prediction components).
 * Keys of a custom `patch` map are applied in key order, so `engine/processors` is assigned before
 * `engine/processors/@before 0` from the prediction block: the two managed blocks compose in either
 * order. With nothing switched off the block records only `songyan_lua_managed: false`, which pins
 * no list.
 */
object LuaComponents {
    const val RECIPE_NAME = "lua_components"

    /** Source lists at the time the first component was switched off, so a restore keeps the original order. */
    const val BASELINE_KEY = "songyan_lua_baseline"
    const val MANAGED_KEY = "songyan_lua_managed"
    val LISTS = listOf("processors", "segmentors", "translators", "filters")
    private val LUA_KINDS = setOf("lua_processor", "lua_segmentor", "lua_translator", "lua_filter")

    data class Component(
        val list: String,
        val prescription: String,
        val enabled: Boolean,
    ) {
        val id: String get() = "$list/$prescription"

        /** Processors see every key before the rest of the engine; turning one off can change basic typing. */
        val mayAffectBasicInput: Boolean get() = list == "processors"

        /** `lua_filter@*module*name` shows as `module*name`, the part users recognise. */
        val displayName: String
            get() {
                val at = prescription.indexOf('@')
                if (at < 0) return prescription
                val rest = prescription.substring(at + 1)
                return if (rest.startsWith("*")) rest.substring(1) else rest
            }
    }

    data class SchemaState(
        val schemaID: String,
        /** `schema/name`, or the ID when absent. */
        val name: String,
        val components: List<Component>,
        val baseline: Map<String, List<String>>,
    ) {
        val disabled: Set<String> get() = components.filter { !it.enabled }.map { it.prescription }.toSet()
    }

    fun isLua(prescription: String): Boolean = Prediction.component(prescription) in LUA_KINDS

    /** Reads the compiled schema. Null when unreadable or not the schema asked for (fail closed). */
    fun state(
        compiled: ByteArray,
        schemaID: String,
    ): SchemaState? {
        if (!TypoCorrection.validIdentifier(schemaID) || compiled.size > RecipeResourceBudget.SOURCE_FILE) return null
        val root = decodeUtf8(compiled)?.let { YamsYaml.loadMap(it) } ?: return null
        val schema = root["schema"].asStringMap()
        if (schema?.get("schema_id") != schemaID) return null
        val engine = root["engine"].asStringMap() ?: return null
        val current = LinkedHashMap<String, List<String>>()
        for (list in LISTS) {
            val value = engine[list] ?: continue
            current[list] = value.asStringList() ?: return null
        }
        val recorded = root[BASELINE_KEY].asStringMap()
        val baseline: Map<String, List<String>> =
            if (recorded != null) {
                val lists = LinkedHashMap<String, List<String>>()
                for ((key, value) in recorded) {
                    if (key !in LISTS) return null
                    lists[key] = value.asStringList() ?: return null
                }
                lists
            } else {
                withoutManagedPrediction(current, root)
            }
        val components = ArrayList<Component>()
        for (list in LISTS) {
            for (item in baseline[list] ?: emptyList()) {
                if (isLua(item)) components.add(Component(list, item, (current[list] ?: emptyList()).contains(item)))
            }
        }
        val name = if (schema.containsKey("name")) describe(schema["name"]) else schemaID
        return SchemaState(schemaID, name, components, baseline)
    }

    // Swift `String(describing:)`; a YAML null is `NSNull`.
    private fun describe(value: Any?): String = if (value === YamsYaml.Null) "<null>" else describeScalar(value)

    /**
     * Our prediction block inserts its components on top of whatever lists are assigned, so they
     * never belong to a Lua baseline.
     */
    internal fun withoutManagedPrediction(
        lists: Map<String, List<String>>,
        root: Map<String, Any?>,
    ): Map<String, List<String>> {
        if (root[Prediction.MARKER_KEY] != true) return lists
        val result = LinkedHashMap(lists)
        lists["processors"]?.let { items ->
            result["processors"] =
                items.filter { Prediction.component(it) != Prediction.PROCESSOR }
        }
        lists["translators"]?.let { items ->
            result["translators"] =
                items.filter { Prediction.component(it) != Prediction.TRANSLATOR }
        }
        return result
    }

    /** Lists the managed block assigns: the baseline minus [disabled], only for lists that actually lose something. */
    fun assignedLists(
        baseline: Map<String, List<String>>,
        disabled: Set<String>,
    ): Map<String, List<String>> {
        val result = LinkedHashMap<String, List<String>>()
        for (list in LISTS) {
            val items = baseline[list] ?: continue
            if (items.none { it in disabled }) continue
            result[list] = items.filter { it !in disabled }
        }
        return result
    }

    fun rx(packageReference: String): String = packageReference + ":" + RECIPE_NAME + ":"

    /**
     * True when the user's own custom content (outside this recipe's block and our other managed
     * blocks) edits engine lists; such a schema is left to the user.
     */
    fun userOwnsSetting(customYaml: ByteArray?): Boolean {
        val text = customYaml?.let { decodeUtf8(it) } ?: return false
        var inManaged = false
        for (line in splitLinesKeepingEmpty(text)) {
            if (line.startsWith("# Rx: ") && line.endsWith(" {")) {
                inManaged = true
                continue
            }
            if (inManaged) {
                if (line == "# }") inManaged = false
                continue
            }
            val content = line.substringBefore('#')
            if (content.contains("engine/") || content.contains(BASELINE_KEY)) return true
            if (content.trimFoundationWhitespace().startsWith("engine:")) return true
        }
        return false
    }

    /**
     * One recipe for one input method. Every prescription in [disabled] must be a Lua component of
     * [baseline]; an empty set writes the marker-only block.
     */
    fun make(
        schemaID: String,
        baseline: Map<String, List<String>>,
        disabled: Set<String>,
    ): DeclarativeRecipe {
        if (!TypoCorrection.validIdentifier(schemaID) || CompactSchemaAdapter.isGeneratedCompanionID(schemaID)) {
            throw RecipeInstallError.InvalidResource("invalid input method id: $schemaID")
        }
        val known = LISTS.flatMap { baseline[it] ?: emptyList() }.filter { isLua(it) }.toSet()
        if (!known.containsAll(
                disabled,
            )
        ) {
            throw RecipeInstallError.InvalidResource("not a Lua component of this input method")
        }
        // Recipe metadata that ends up in the generated recipe file, kept identical to iOS.
        val lines =
            arrayListOf(
                "recipe:",
                "  Rx: $RECIPE_NAME",
                "  description: Lua 组件开关",
                "patch_files:",
                "  $schemaID.custom.yaml:",
                "    - patch/+:",
            )
        if (disabled.isEmpty()) {
            lines.add("        $MANAGED_KEY: false")
        } else {
            val assigned = assignedLists(baseline, disabled)
            for (list in LISTS) {
                val items = assigned[list] ?: continue
                lines.add("        engine/$list: [${items.joinToString(", ") { quoted(it) }}]")
            }
            lines.add("        $MANAGED_KEY: true")
            val recorded =
                LISTS.mapNotNull { list ->
                    baseline[list]?.let { "$list: [${it.joinToString(", ") { item -> quoted(item) }}]" }
                }
            lines.add("        $BASELINE_KEY: {${recorded.joinToString(", ")}}")
        }
        return DeclarativeRecipe.parse(
            "$RECIPE_NAME.recipe.yaml",
            (lines.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8),
        )
    }

    /** JSON string literal: a valid YAML flow scalar for any prescription. */
    internal fun quoted(value: String): String {
        val out = StringBuilder("\"")
        value.codePoints().forEach { scalar ->
            when {
                scalar == '"'.code -> out.append("\\\"")
                scalar == '\\'.code -> out.append("\\\\")
                scalar < 0x20 -> out.append("\\u%04x".format(scalar))
                else -> out.appendCodePoint(scalar)
            }
        }
        return out.append('"').toString()
    }

    /** Checks a deployed compiled schema against the intended state: each list equals the baseline minus [disabled]. */
    fun matches(
        compiled: ByteArray,
        schemaID: String,
        baseline: Map<String, List<String>>,
        disabled: Set<String>,
    ): Boolean {
        val root = decodeUtf8(compiled)?.let { YamsYaml.loadMap(it) } ?: return false
        if (root["schema"].asStringMap()?.get("schema_id") != schemaID) return false
        val engine = root["engine"].asStringMap() ?: return false
        val current = LinkedHashMap<String, List<String>>()
        for (list in LISTS) engine[list].asStringList()?.let { current[list] = it }
        val effective = withoutManagedPrediction(current, root)
        for (list in LISTS) {
            val expected = (baseline[list] ?: emptyList()).filter { it !in disabled }
            if ((effective[list] ?: emptyList()) != expected) return false
        }
        return true
    }
}
