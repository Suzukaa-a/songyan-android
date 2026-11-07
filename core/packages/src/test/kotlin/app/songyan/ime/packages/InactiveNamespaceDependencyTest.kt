package app.songyan.ime.packages

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The dependency closure may treat a dictionary as required only when a native component can name
 * its namespace. librime creates a Dictionary with ticket.name_space + "/dictionary" (rime dict
 * dictionary.cc), and components exist only for the entries of
 * engine/{processors,segmentors,translators,filters} (rime engine.cc, ticket.cc). A dictionary
 * under a root namespace no engine entry claims is never loaded natively, so the closure keeps it
 * optional: reported and still fetchable, but not blocking the import.
 *
 * These cases pin only the closure the importer runs (SchemeSourceDependencies.missing, called from
 * SchemePackageImport). Ported case-for-case from the iOS InactiveNamespaceDependencyTests. They
 * make no claim about Lua components that construct translators dynamically at run time; a
 * dictionary missing for such a component is a silently absent lookup, exactly like a declared
 * optional dependency. Every other dependency edge (translator dictionary, __include / __patch,
 * opencc, import_preset) keeps its old strictness.
 */
class InactiveNamespaceDependencyTest {
    private val moranDictionary = "moran.chars.dict.yaml"
    private val mungyeongDictionary = "mungyeong.extended.dict.yaml"

    /**
     * The translators of a schema shaped like the frozen kagiroi source: the reverse-lookup
     * components are absent (they are comments in the upstream YAML, i.e. not data).
     */
    private val defaultTranslators =
        listOf(
            "punct_translator",
            "table_translator@custom_phrase",
            "lua_translator@*probe/translator",
        )

    // Fixtures

    private fun engineLines(translators: List<String>): List<String> {
        val lines =
            mutableListOf(
                "engine:",
                "  processors:",
                "    - ascii_composer",
                "    - recognizer",
                "    - key_binder",
                "    - lua_processor@*probe/kana",
                "    - speller",
                "    - punctuator",
                "    - selector",
                "    - navigator",
                "    - express_editor",
                "  segmentors:",
                "    - ascii_segmentor",
                "    - matcher",
                "    - abc_segmentor",
                "    - punct_segmentor",
                "    - fallback_segmentor",
                "  translators:",
            )
        for (translator in translators) lines.add("    - " + translator)
        lines.add("  filters:")
        lines.add("    - uniquifier")
        return lines
    }

    private fun namespaceLines(
        moran: List<String>,
        mungyeong: List<String>,
    ): List<String> {
        fun block(
            name: String,
            dictionary: String,
            extra: List<String>,
        ): List<String> {
            val lines =
                mutableListOf(
                    name + ":",
                    "  dictionary: " + dictionary,
                    "  target: probe",
                )
            for (line in extra) lines.add("  " + line)
            lines.add("  tag: " + name)
            return lines
        }
        return block("moran", "moran.chars", moran) + block("mungyeong", "mungyeong.extended", mungyeong)
    }

    /**
     * engineBlock null writes the default four lists; an empty string omits the section; anything
     * else is inserted verbatim (a null section, an opaque __include, ...).
     */
    private fun schema(
        translators: List<String>? = null,
        engineBlock: String? = null,
        translatorDictionary: String = "probe",
        additionalRoot: String = "",
        moran: List<String> = emptyList(),
        mungyeong: List<String> = emptyList(),
    ): String {
        val lines = mutableListOf("schema:", "  schema_id: probe", "  name: Probe", "")
        if (engineBlock == null) {
            lines.addAll(engineLines(translators ?: defaultTranslators))
        } else if (engineBlock.isNotEmpty()) {
            lines.addAll(engineBlock.split("\n"))
        }
        lines.addAll(listOf("", "translator:", "  dictionary: " + translatorDictionary, ""))
        lines.addAll(
            listOf(
                "recognizer:",
                "  import_preset: default",
                "  patterns:",
                "    moran: '^C'",
                "    mungyeong: '^K'",
                "",
            ),
        )
        if (additionalRoot.isNotEmpty()) lines.addAll(additionalRoot.split("\n"))
        lines.addAll(namespaceLines(moran, mungyeong))
        return lines.joinToString("\n")
    }

    private fun files(
        schema: String,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> {
        val result =
            linkedMapOf(
                "default.yaml" to "schema_list:\n  - schema: probe\n",
                "probe.schema.yaml" to schema,
                "probe.dict.yaml" to "---\nname: probe\nversion: '1'\n...\n",
                "lua/probe/kana.lua" to "return {}\n",
                "lua/probe/translator.lua" to "return {}\n",
            )
        result.putAll(extra)
        return result
    }

    private fun dependencyClosure(files: Map<String, String>): SchemeSourceDependencies.Closure {
        val bytes = files.mapValues { it.value.toByteArray(Charsets.UTF_8) }
        return SchemeSourceDependencies(bytes.keys) { bytes[it] }.closure()
    }

    // Inactive namespace: dictionary becomes optional

    @Test
    fun anInactiveNativeNamespaceDictionaryIsOptional() {
        val subject = files(schema())
        val closure = dependencyClosure(subject)
        assertEquals(emptyList<String>(), closure.missing)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.optional)
    }

    // A native component alias claims its namespace again

    @Test
    fun aComponentAliasMakesItsNamespaceDictionaryRequired() {
        val subject = files(schema(translators = defaultTranslators + "reverse_lookup_translator@moran"))
        val closure = dependencyClosure(subject)
        assertEquals(listOf(moranDictionary), closure.missing)
        assertEquals(listOf(mungyeongDictionary), closure.optional)
    }

    // Unknown capability stays exactly as strict as before

    /**
     * A component class this checker cannot interpret may read config sections other than the alias
     * it names (reverse_lookup_translator also reads reverse_lookup/), so nothing is relaxed for
     * schemas mounting one.
     */
    @Test
    fun anUnknownComponentAliasKeepsEveryDictionaryRequired() {
        val subject = files(schema(translators = defaultTranslators + "mystery_translator@moran"))
        val closure = dependencyClosure(subject)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.missing)
    }

    /** Without an alias the component's config namespace is not knowable at all: fail closed. */
    @Test
    fun anUnknownComponentWithoutAliasKeepsEveryDictionaryRequired() {
        val subject = files(schema(translators = defaultTranslators + "mystery_translator"))
        val closure = dependencyClosure(subject)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.missing)
    }

    // Native default namespaces keep their dictionaries

    /**
     * A bare reverse_lookup_translator reads reverse_lookup/ (librime remaps the component kind),
     * so that root section stays a hard dependency.
     */
    @Test
    fun theDefaultReverseLookupSectionStaysRequiredForABareReverseLookupTranslator() {
        val subject =
            files(
                schema(
                    translators = defaultTranslators + "reverse_lookup_translator",
                    additionalRoot = "reverse_lookup:\n  dictionary: luna_lookup\n",
                ),
            )
        val closure = dependencyClosure(subject)
        assertEquals(listOf("luna_lookup.dict.yaml"), closure.missing)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.optional)
    }

    // Only dictionary edges change; references stay hard

    @Test
    fun anIncludeInsideAnInactiveBlockStaysRequired() {
        val subject = files(schema(moran = listOf("__include: extra:/node")))
        val closure = dependencyClosure(subject)
        assertEquals(listOf("extra.yaml"), closure.missing)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.optional)
    }

    @Test
    fun onlyDictionaryEdgesInsideAnInactiveBlockBecomeOptional() {
        val subject =
            files(
                schema(moran = listOf("opencc_config: moran.json", "import_preset: moran_preset")),
            )
        val closure = dependencyClosure(subject)
        assertEquals(listOf("moran_preset.yaml", "opencc/moran.json"), closure.missing)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.optional)
    }

    /**
     * translator/dictionary is what SchemaUpdate compiles, so it is required whatever the engine
     * block says.
     */
    @Test
    fun theMainTranslatorDictionaryIsAlwaysRequired() {
        val subject = files(schema()) - "probe.dict.yaml"
        val closure = dependencyClosure(subject)
        assertEquals(listOf("probe.dict.yaml"), closure.missing)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.optional)
    }

    // Unreadable engine: keep the old strictness

    @Test
    fun anAbsentEngineKeepsEveryDictionaryRequired() {
        val subject = files(schema(engineBlock = ""))
        val closure = dependencyClosure(subject)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.missing)
    }

    @Test
    fun aNullEngineKeepsEveryDictionaryRequired() {
        val subject = files(schema(engineBlock = "engine:"))
        val closure = dependencyClosure(subject)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.missing)
    }

    @Test
    fun anOpaqueEngineIncludeKeepsEveryDictionaryRequired() {
        val subject =
            files(
                schema(engineBlock = "engine:\n  __include: shared_engine:/engine"),
                mapOf("shared_engine.yaml" to "engine:\n  translators:\n    - punct_translator\n"),
            )
        val closure = dependencyClosure(subject)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.missing)
    }

    // A custom patch that can enable the namespace: keep the old strictness

    @Test
    fun aCustomPatchThatCanEnableTheNamespaceKeepsItRequired() {
        val subject =
            files(
                schema(),
                mapOf(
                    "probe.custom.yaml" to
                        "patch:\n  engine/translators/+:\n    - reverse_lookup_translator@moran\n",
                ),
            )
        val closure = dependencyClosure(subject)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.missing)
    }

    /** A patch expression this checker cannot model could also mount the component: fail closed. */
    @Test
    fun anOpaqueCustomPatchKeepsEveryDictionaryRequired() {
        val subject =
            files(
                schema(),
                mapOf(
                    "probe.custom.yaml" to "__patch: [\"shared_patch:/engine\"]\n",
                    "shared_patch.yaml" to "engine:\n  translators:\n    - punct_translator\n",
                ),
            )
        val closure = dependencyClosure(subject)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.missing)
    }

    /**
     * A custom file whose literal patch leaves the engine alone cannot enable the component, so the
     * inactive namespace stays optional: otherwise every package with any custom file would fall
     * back to the old over-strict closure.
     */
    @Test
    fun anUnrelatedCustomPatchDoesNotDisableRelaxation() {
        val subject =
            files(
                schema(),
                mapOf("probe.custom.yaml" to "patch:\n  menu/page_size: 7\n"),
            )
        val closure = dependencyClosure(subject)
        assertEquals(emptyList<String>(), closure.missing)
        assertEquals(listOf(moranDictionary, mungyeongDictionary), closure.optional)
    }
}
