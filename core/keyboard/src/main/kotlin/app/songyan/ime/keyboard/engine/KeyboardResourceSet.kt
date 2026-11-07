package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.keyboard.support.UserTerminology
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A user keyboard added in the settings app: one "input method (schema) x layout" combination.
 *
 * This type is identity only (verbatim schema id + layout); human names are resolved at projection
 * time from the current scheme descriptors, so renames follow automatically.
 */
@Serializable
data class PublishedKeyboard(
    @SerialName("schemaID") val schemaID: String,
    @SerialName("layout") val layout: KeyboardLayoutKind,
) {
    val id: String get() = "$schemaID:${layout.wire}"

    /** Whether the combination needs a generated adapter companion (14/9-key layouts). */
    val needsGeneratedAdapter: Boolean get() = layout.requiresSpellingDisplay

    companion object {
        /** The added-keyboard list of a publication record, duplicates dropped, order kept. */
        fun resolve(published: List<PublishedKeyboard>?): List<PublishedKeyboard> {
            val seen = LinkedHashSet<PublishedKeyboard>()
            return (published ?: emptyList()).filter { seen.add(it) }
        }

        /** Adds an entry (idempotent, keeps insertion order). */
        fun adding(
            keyboard: PublishedKeyboard,
            to: List<PublishedKeyboard>,
        ): List<PublishedKeyboard> = if (keyboard in to) to else to + keyboard

        /** Removes an entry (idempotent). */
        fun removing(
            keyboard: PublishedKeyboard,
            from: List<PublishedKeyboard>,
        ): List<PublishedKeyboard> = from.filter { it != keyboard }

        /** The selection falls back only when the current combination was removed. */
        fun selectionAfterRemoval(
            remaining: List<PublishedKeyboard>,
            current: PublishedKeyboard?,
        ): PublishedKeyboard? = if (current != null && current in remaining) current else remaining.firstOrNull()

        /** Adapter generation tasks (deduplicated, ordered): compact-layout combinations only. */
        fun generationTasks(forRequested: List<PublishedKeyboard>): List<PublishedKeyboard> {
            val seen = LinkedHashSet<PublishedKeyboard>()
            return forRequested.filter { it.needsGeneratedAdapter && seen.add(it) }
        }

        /** Display projection (keeps input order); names come from the current descriptors. */
        fun displays(
            keyboards: List<PublishedKeyboard>,
            descriptors: List<KeyboardSchemaDescriptor>,
        ): List<PublishedKeyboardDisplay> = keyboards.map { display(it, descriptors) }

        fun display(
            keyboard: PublishedKeyboard,
            descriptors: List<KeyboardSchemaDescriptor>,
        ): PublishedKeyboardDisplay =
            PublishedKeyboardDisplay(
                keyboard = keyboard,
                // Only a declared human-readable name counts; an empty name falls back to layout name.
                inputMethodName = descriptors.firstOrNull { it.id == keyboard.schemaID }?.humanReadableName ?: "",
            )
    }
}

/** Display projection of a published keyboard: identity plus the resolved input method name. */
data class PublishedKeyboardDisplay(
    val keyboard: PublishedKeyboard,
    val inputMethodName: String,
) {
    val id: String get() = keyboard.id
    val layout: KeyboardLayoutKind get() = keyboard.layout

    /** Title: `<input method> · <layout>`; layout name only when there is no input method name. */
    val title: String
        get() {
            val trimmed = inputMethodName.trim()
            val layoutName = UserTerminology.layoutName(layout)
            return if (trimmed.isEmpty()) layoutName else "$trimmed · $layoutName"
        }
}

/** Optional auxiliary candidate route owned by one exact published keyboard pair. */
@Serializable
data class EnglishCandidateRoute(
    @SerialName("owner") val owner: PublishedKeyboard,
    @SerialName("candidateSchemaID") val candidateSchemaID: String,
) {
    val id: String get() = owner.id
}

/**
 * Declares an engine resource set: the available layouts and the exact engine schema for each
 * layout, plus the publication facts (added keyboards) that gate which combinations are offered.
 *
 * The physical layout is not decided by the resource brand; compact keyboards report their encoding
 * needs and the scheme's algebra is never rewritten automatically.
 */
data class KeyboardResourceSet(
    /** Stable identifier (e.g. "builtin", "user-pinyin", an installed package id). */
    val id: String,
    /** Display name (UI only; not a unique key). */
    val name: String,
    /** Rime shared directory (default.yaml, *.schema.yaml, dictionaries; read-only). */
    val sharedDirectory: String,
    /** Writable user directory (build/, *.userdb/, sync/). */
    val userDirectory: String,
    /** Available layouts and exact engine schema ids, in suggested display order. */
    val layoutEntries: List<LayoutEntry> = emptyList(),
    /** Read-only compiled resource directory of a published generation; null = normal launch. */
    val prebuiltDirectory: String? = null,
    val schemas: List<KeyboardSchemaDescriptor> = emptyList(),
    /**
     * The schema facts the set was built from (id, name and declared speller alphabet), kept apart
     * from [schemas] because that list is what the UI offers as input methods. They decide a
     * scheme's family without asking the engine on the key path.
     */
    val schemaFacts: List<KeyboardSchemaDescriptor> = emptyList(),
    val selectedSchemaID: String? = null,
    val preferredLayout: KeyboardLayoutKind? = null,
    /** Used for the "same source, same revision" check; null never passes by default. */
    val sourceRevision: String? = null,
    /** Publication generation of the authoritative record; null = builtin/unpublished. */
    val publicationGeneration: String? = null,
    /** Added keyboards; null = unrestricted (builtin/trial path), empty = no keyboards added. */
    val publishedKeyboards: List<PublishedKeyboard>? = null,
    val englishCandidateRoutes: List<EnglishCandidateRoute> = emptyList(),
    val encodingAdapters: List<EncodingAdapter> = emptyList(),
) {
    /** One runnable pair in this resource source. */
    data class Combination(
        /** The user-visible enabled schema. */
        val schemaID: String,
        /** Its explicitly installed, validated encoding companion. */
        val engineSchemaID: String,
        val layout: KeyboardLayoutKind,
        val name: String,
    ) {
        val id: String get() = "$schemaID:${layout.wire}"
    }

    @Serializable
    data class EncodingAdapter(
        @SerialName("sourceSchemaID") val sourceSchemaID: String,
        @SerialName("schemaID") val schemaID: String,
        @SerialName("layout") val layout: KeyboardLayoutKind,
        @SerialName("schemaName") val schemaName: String? = null,
    ) {
        val engineSchemaID: String get() = schemaID

        companion object {
            private val GENERATED_ID = Regex("^keyboard_[0-9a-f]{20}_[0-9]+$")

            /**
             * True for the schema ids minted for generated compact companions
             * (`keyboard_<source hash>_<fold>`): they run as schemas of their own, but are never an
             * input method to offer the user.
             */
            fun isGeneratedID(id: String): Boolean = GENERATED_ID.containsMatchIn(id)
        }
    }

    @Serializable
    data class LayoutEntry(
        @SerialName("layout") val layout: KeyboardLayoutKind,
        @SerialName("schemaID") val schemaID: String,
    ) {
        val id: String get() = "$schemaID:${layout.wire}"
    }

    /**
     * Every source this snapshot knows, with its family resolved once. Layout availability and the
     * keyboard's jump/menu paths read this table instead of asking the language table per call, and
     * a source that is not part of the snapshot falls back to the id-only rule.
     */
    private val families: Map<String, InputSchemeFamily> = knownSources().associateWith(::resolveFamily)

    /** Whether this set selects schemas independently (vs. the plain built-in template pairing). */
    val usesIndependentSelection: Boolean
        get() = selectedSchemaID != null || schemas.isNotEmpty() || encodingAdapters.isNotEmpty()

    /** Every runnable (source scheme, layout) pair this resource offers, in a stable order. */
    val availableCombinations: List<Combination>
        get() {
            val result = mutableListOf<Combination>()
            val auxiliary = englishCandidateRoutes.map { it.candidateSchemaID }.toSet()
            val seenSources = LinkedHashSet<String>()
            val enabled =
                if (schemas.isEmpty()) {
                    layoutEntries.map { it.schemaID }.filter { seenSources.add(it) }
                } else {
                    schemas.map { it.id }
                }.filter { it !in auxiliary }
            for (source in enabled) {
                // Only a declared human name is used; empty stays empty so the layout name is shown.
                val sourceName = schemas.firstOrNull { it.id == source }?.humanReadableName ?: ""
                val family = familyOf(source)
                for (layout in KeyboardLayoutKind.entries) {
                    // The family rule comes before any declared entry, adapter or publication.
                    if (!InputLayoutPolicy.allows(layout, family)) continue
                    if (publishedKeyboards != null) {
                        val published = publishedKeyboards
                        val engine = structurallyRunnableEngineSchema(source, layout)
                        if (PublishedKeyboard(source, layout) !in published || engine == null) continue
                        result += Combination(source, engine, layout, sourceName)
                        continue
                    }
                    val adapter = encodingAdapters.firstOrNull { it.sourceSchemaID == source && it.layout == layout }
                    if (adapter != null) {
                        result +=
                            Combination(
                                source,
                                adapter.schemaID,
                                layout,
                                adapter.schemaName ?: (sourceName + " · " + layout.shortTitle + "配套"),
                            )
                    } else if (layoutEntries.any { it.schemaID == source && it.layout == layout }) {
                        result += Combination(source, source, layout, sourceName)
                    } else if (layout.keyFold == KeyboardLayoutKind.KeyFold.IDENTITY &&
                        (selectedSchemaID != null || layoutEntries.isEmpty())
                    ) {
                        val alphabet = alphabetOf(source)
                        val latin = alphabet.isEmpty() || alphabet.any { it in 'a'..'z' }
                        if (latin) result += Combination(source, source, layout, sourceName)
                    }
                }
            }
            return result
        }

    /**
     * The structured engine schema for a (source, layout):
     *  - generated adapter -> the adapter's schema;
     *  - author/advanced binding -> the source schema itself;
     *  - plain 26-key -> the source schema itself;
     *  - compact layout without adapter or binding -> null (never fall back to the source's 26-key).
     */
    fun structurallyRunnableEngineSchema(
        source: String,
        layout: KeyboardLayoutKind,
    ): String? {
        if (!InputLayoutPolicy.allows(layout, familyOf(source))) return null
        encodingAdapters.firstOrNull { it.sourceSchemaID == source && it.layout == layout }?.let { return it.schemaID }
        if (layoutEntries.any { it.schemaID == source && it.layout == layout }) return source
        if (!layout.requiresSpellingDisplay) return source
        return null
    }

    fun combination(
        layout: KeyboardLayoutKind,
        preferredSchemaID: String? = null,
    ): Combination? {
        val pairs = availableCombinations.filter { it.layout == layout }
        return pairs.firstOrNull { it.schemaID == (preferredSchemaID ?: selectedSchemaID) } ?: pairs.firstOrNull()
    }

    /** Source schemes appear once; generated keyboard companions remain diagnostics. */
    val inputSchemeChoices: List<KeyboardSchemaDescriptor>
        get() {
            val companionIDs =
                encodingAdapters.filter { it.schemaID != it.sourceSchemaID }.map { it.schemaID }.toSet() +
                    englishCandidateRoutes.map { it.candidateSchemaID }
            return schemas.filter { it.id !in companionIDs }
        }

    fun keyboardChoices(forSchemaID: String): List<Combination> =
        availableCombinations.filter { it.schemaID == sourceSchemaID(forSchemaID) }

    /**
     * The engine schema a source scheme runs as on [layout]; null when the layout cannot run it. A
     * compact layout never falls back to the source's 26-key schema: its folded keys would reach
     * an engine that reads them as plain letters.
     */
    fun logicalSchemaID(
        sourceID: String,
        layout: KeyboardLayoutKind,
    ): String? = structurallyRunnableEngineSchema(sourceID, layout)

    fun sourceSchemaID(engineID: String): String =
        encodingAdapters.firstOrNull { it.schemaID == engineID }?.sourceSchemaID ?: engineID

    val availableLayouts: List<KeyboardLayoutKind>
        get() = KeyboardLayoutKind.entries.filter { layout -> availableCombinations.any { it.layout == layout } }

    fun supports(layout: KeyboardLayoutKind): Boolean = layout in availableLayouts

    /**
     * The family of [sourceID], from the table resolved when this snapshot was built. A generated
     * companion answers with the family of the source it belongs to.
     */
    fun familyOf(sourceID: String): InputSchemeFamily {
        val source = sourceSchemaID(sourceID)
        return families[source] ?: InputLayoutPolicy.familyOf(source)
    }

    private fun resolveFamily(sourceID: String): InputSchemeFamily =
        InputLayoutPolicy.familyOf(sourceID, alphabetOf(sourceID))

    private fun knownSources(): Set<String> =
        buildSet {
            layoutEntries.forEach { add(it.schemaID) }
            schemas.forEach { add(it.id) }
            schemaFacts.forEach { add(it.id) }
            encodingAdapters.forEach { add(it.sourceSchemaID) }
            publishedKeyboards?.forEach { add(it.schemaID) }
            selectedSchemaID?.let { add(it) }
        }

    /** The shipped shapes this set can still offer [sourceID] on, in display order. */
    fun layoutsFor(sourceID: String): List<KeyboardLayoutKind> {
        val source = sourceSchemaID(sourceID)
        return availableCombinations.filter { it.schemaID == source }.map { it.layout }
    }

    /**
     * The speller alphabet a descriptor carries; empty when the caller knew none. An empty declared
     * value is "unknown" and must not hide a real alphabet a later fact does carry.
     */
    private fun alphabetOf(sourceID: String): String =
        schemas.firstOrNull { it.id == sourceID }?.alphabet?.takeIf { it.isNotEmpty() }
            ?: schemaFacts.firstOrNull { it.id == sourceID }?.alphabet?.takeIf { it.isNotEmpty() }
            ?: ""

    /** The exact engine schema for a layout; null when the layout is not in this resource set. */
    fun schemaIDFor(layout: KeyboardLayoutKind): String? = combination(layout)?.engineSchemaID

    fun incompatibilityReason(
        layout: KeyboardLayoutKind,
        schemaID: String,
    ): String? {
        val source = sourceSchemaID(schemaID)
        if (availableCombinations.any { it.schemaID == source && it.layout == layout }) return null
        return UserTerminology.layoutUnavailableReason(layout)
    }

    /** A copy carrying an explicit publication selection (schema and, when valid, layout). */
    fun selecting(
        schemaID: String,
        layout: KeyboardLayoutKind? = null,
    ): KeyboardResourceSet {
        if (englishCandidateRoutes.any { it.candidateSchemaID == schemaID }) return this
        val enabled =
            when {
                schemas.isNotEmpty() -> schemas.any { it.id == schemaID }
                layoutEntries.isNotEmpty() -> layoutEntries.any { it.schemaID == schemaID }
                else -> availableCombinations.any { it.schemaID == schemaID }
            }
        if (!enabled) return this
        val validLayout =
            layout?.takeIf { candidate ->
                availableCombinations.any { it.schemaID == schemaID && it.layout == candidate }
            }
        return copy(selectedSchemaID = schemaID, preferredLayout = validLayout)
    }

    /** Picks a launchable (layout, engine schema) from candidate layouts; never crosses sources. */
    fun bootSelection(
        candidates: List<KeyboardLayoutKind>,
        restrictedToSource: String? = null,
    ): Pair<KeyboardLayoutKind, String>? {
        for (candidate in candidates) {
            if (restrictedToSource != null) {
                val combo =
                    availableCombinations.firstOrNull {
                        it.schemaID == restrictedToSource && it.layout == candidate
                    } ?: continue
                if (incompatibilityReason(candidate, combo.engineSchemaID) != null) continue
                return candidate to combo.engineSchemaID
            }
            if (!supports(candidate)) continue
            val schema = schemaIDFor(candidate) ?: continue
            if (incompatibilityReason(candidate, schema) != null) continue
            return candidate to schema
        }
        return null
    }

    /**
     * Whether the target schema belongs to this resource set's actually enabled manifest (the
     * install receipt / publication record). Being compiled is not authorization.
     *
     * Returns a typed refusal instead of a sentence: user-facing copy is owned by the Android
     * modules (strings.xml) and the schema ids stay diagnostics, never display text.
     */
    fun enabledMembershipRefusal(
        schemaID: String,
        engineSchemas: List<KeyboardSchemaDescriptor> = emptyList(),
    ): SchemaMembershipRefusal? {
        val allowed = if (schemas.isEmpty()) engineSchemas else schemas
        val sourceID = sourceSchemaID(schemaID)
        if (allowed.any { it.id == sourceID }) return null
        return SchemaMembershipRefusal(schemaID = sourceID, enabledSchemaIDs = allowed.map { it.id })
    }

    /** A refused selection: the wanted source schema plus what this publication actually enables. */
    data class SchemaMembershipRefusal(
        val schemaID: String,
        val enabledSchemaIDs: List<String>,
    )

    companion object {
        /** The bundled schemes with their bundled compact companions. */
        fun builtin(
            sharedDirectory: String,
            userDirectory: String,
        ): KeyboardResourceSet =
            KeyboardResourceSet(
                id = "builtin",
                name = "内置方案",
                sharedDirectory = sharedDirectory,
                userDirectory = userDirectory,
                layoutEntries = BuiltinTemplateSchemas.layoutEntries,
                encodingAdapters = BuiltinTemplateSchemas.ADAPTERS,
            )

        /** Imported dictionaries run on the built-in schemes and their shared learning. */
        fun userDictionary(
            name: String,
            sharedDirectory: String,
            userDirectory: String,
            prebuiltDirectory: String? = null,
            sourceRevision: String? = null,
            schemas: List<KeyboardSchemaDescriptor> = emptyList(),
        ): KeyboardResourceSet =
            KeyboardResourceSet(
                id = "user-pinyin",
                name = name,
                sharedDirectory = sharedDirectory,
                userDirectory = userDirectory,
                layoutEntries = BuiltinTemplateSchemas.layoutEntries,
                prebuiltDirectory = prebuiltDirectory,
                schemas = schemas,
                sourceRevision = sourceRevision,
            )
    }
}
