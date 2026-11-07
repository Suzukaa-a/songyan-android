package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/**
 * Value-type snapshot of the user's layouts, pages and keyboard pairings. Which layout a keyboard
 * uses is its pairing, not a list of enabled layouts.
 *
 * The registry is pure logic: it never touches disk. [LayoutStore] owns persistence. Every edit
 * returns a new validated registry, so a failed edit leaves the current one intact.
 */
class LayoutRegistry private constructor(
    val layouts: List<LayoutDefinition>,
    val pages: List<KeyboardPageDefinition>,
    val compositions: Map<String, KeyboardComposition>,
) {
    fun layout(id: String): LayoutDefinition? = layouts.firstOrNull { it.id == id }

    fun page(id: String): KeyboardPageDefinition? = pages.firstOrNull { it.id == id }

    /**
     * The stored layout, or the bundled preset with this id that an older catalog left out.
     * Bundled presets are always available as main layouts.
     */
    fun resolvedLayout(id: String): LayoutDefinition? = layout(id) ?: BuiltinLayouts.all.firstOrNull { it.id == id }

    /** Every layout [isSupported] accepts, plus the bundled presets an older catalog left out. */
    fun usableLayouts(isSupported: (LayoutDefinition) -> Boolean): List<LayoutDefinition> {
        val missing = BuiltinLayouts.all.filter { preset -> layouts.none { it.id == preset.id } }
        return (layouts + missing).filter(isSupported)
    }

    /** The layouts the page pairing offers as this keyboard's main layout. */
    fun mainLayoutCandidates(
        key: KeyboardCompositionKey,
        families: LayoutFamilyContext = LayoutFamilyContext.UNKNOWN,
    ): List<LayoutDefinition> {
        val family = families.familyOf(key.inputMethodId)
        // A family without a known exception keeps the shipped shapes; the identity shape stays the
        // transport for the scheme-driven physical layouts.
        val kind = kindOf(key.layout) ?: return usableLayouts { it.supportsComposition(key.inputMethodId, key.layout) }
        if (!InputLayoutPolicy.allows(kind, family)) return emptyList()
        return usableLayouts {
            it.supportsComposition(key.inputMethodId, key.layout) &&
                InputLayoutPolicy.allows(it.id, family)
        }
    }

    /**
     * Keyboards that use this layout as the main layout, in a stable order: every saved pairing, plus
     * each of [among] whose effective pairing (saved or default) names it.
     */
    fun keyboardsUsingLayout(
        id: String,
        among: List<KeyboardCompositionKey> = emptyList(),
        families: LayoutFamilyContext = LayoutFamilyContext.UNKNOWN,
    ): List<KeyboardCompositionKey> {
        val raw = compositions.filterValues { it.mainLayoutID == id }.keys.toMutableSet()
        for (key in among) if (composition(key, families).mainLayoutID == id) raw.add(key.rawValue)
        return raw.sorted().mapNotNull { KeyboardCompositionKey.parse(it) }
    }

    /** Keyboards whose saved pairing uses this page, in a stable order. */
    fun keyboardsUsingPage(id: String): List<KeyboardCompositionKey> =
        compositions
            .filterValues { composition -> KeyboardPageKind.entries.any { composition.pageId(it) == id } }
            .keys
            .sorted()
            .mapNotNull { KeyboardCompositionKey.parse(it) }

    /**
     * The saved pairing, or the default one built on [defaultMainLayoutId]. A saved pairing whose
     * main layout the scheme's family (or the shape binding) no longer allows reads as the default
     * one instead; the stored entry itself is left alone so the user's data survives.
     */
    fun composition(
        key: KeyboardCompositionKey,
        families: LayoutFamilyContext = LayoutFamilyContext.UNKNOWN,
    ): KeyboardComposition {
        val family = families.familyOf(key.inputMethodId)
        val saved = compositions[key.rawValue]
        if (kindOf(key.layout)?.let { !InputLayoutPolicy.allows(it, family) } == true) {
            // A pairing saved before the family rule reads through the identity shape that family uses.
            val identity = KeyboardCompositionKey(key.inputMethodId, LayoutInput.Builtin.QWERTY26)
            val base = saved ?: KeyboardComposition(mainLayoutID = defaultMainLayoutId(identity, families))
            return base.copy(mainLayoutID = defaultMainLayoutId(identity, families))
        }
        if (saved == null) return KeyboardComposition(mainLayoutID = defaultMainLayoutId(key, families))
        val legal = legalMainLayoutId(key, saved.mainLayoutID, family, families)
        return if (legal == saved.mainLayoutID) saved else saved.copy(mainLayoutID = legal)
    }

    /**
     * The main layout of a keyboard without a saved pairing; the settings and the keyboard both
     * resolve it here. A layout of this shape bound to this input method wins (the smallest id when
     * there are several, so catalog order never decides); then the physical layout the scheme's
     * family types on, when it shipped; otherwise the bundled preset of the shape.
     */
    fun defaultMainLayoutId(
        key: KeyboardCompositionKey,
        families: LayoutFamilyContext = LayoutFamilyContext.UNKNOWN,
    ): String {
        val family = families.familyOf(key.inputMethodId)
        if (kindOf(key.layout)?.let { !InputLayoutPolicy.allows(it, family) } == true) {
            // A shape the family forbids still answers with a layout it can draw: the identity one.
            val identity = KeyboardCompositionKey(key.inputMethodId, LayoutInput.Builtin.QWERTY26)
            return defaultMainLayoutId(identity, families)
        }
        return layouts
            .filter {
                it.input.schemaID == key.inputMethodId &&
                    it.input.builtinLayout == key.layout &&
                    InputLayoutPolicy.allows(it.id, family)
            }.minOfOrNull { it.id }
            ?: familyLayoutId(key, family)
            ?: BuiltinLayouts.preset(key.layout).id
    }

    /** Replace in place, preserving all ordering/state; a failed edit leaves this registry intact. */
    @Throws(LayoutException::class)
    fun replace(definition: LayoutDefinition): LayoutRegistry {
        definition.validate()
        if (layouts.none { it.id == definition.id }) throw LayoutException.UnknownId(definition.id)
        val candidate = layouts.map { if (it.id == definition.id) definition else it }
        return create(candidate, pages, compositions)
    }

    /**
     * Copies a definition under a fresh id. [newId] is supplied by the caller so this type stays
     * platform-free.
     */
    @Throws(LayoutException::class)
    fun copy(
        definition: LayoutDefinition,
        newId: String,
        name: String? = null,
    ): Pair<LayoutRegistry, LayoutDefinition> {
        definition.validate()
        val copy = definition.copyWithId(newId, name)
        val installed = install(copy.encoded(), replaceExisting = false)
        return installed to copy
    }

    /** Installs one encoded definition. New ids are appended; a same-id import replaces in place only when asked. */
    @Throws(LayoutException::class)
    fun install(
        encoded: String,
        replaceExisting: Boolean = false,
    ): LayoutRegistry {
        val definition = LayoutJson.decode(encoded)
        if (layouts.any { it.id == definition.id } && !replaceExisting) {
            throw LayoutException.DuplicateId(definition.id)
        }
        val replaced = layouts.any { it.id == definition.id }
        val candidateLayouts =
            if (replaced) {
                layouts.map { if (it.id == definition.id) definition else it }
            } else {
                layouts + definition
            }
        return create(candidateLayouts, pages, compositions)
    }

    @Throws(LayoutException::class)
    fun remove(id: String): LayoutRegistry {
        if (layout(id) == null) throw LayoutException.UnknownId(id)
        // Built-ins ship with the app and are the default main layouts, so they stay: the user
        // copies one and edits the copy, the same rule the page library applies to its presets.
        if (BuiltinLayouts.all.any { it.id == id }) {
            throw LayoutException.Invalid(LayoutInvalidCode.LAYOUT_PRESET_IS_READ_ONLY, id)
        }
        if (compositions.values.any { it.mainLayoutID == id }) {
            throw LayoutException.Invalid(LayoutInvalidCode.LAYOUT_IN_USE, id)
        }
        return create(layouts.filterNot { it.id == id }, pages, compositions)
    }

    /** Saves one page in place, or appends a new one; the whole catalog is revalidated. */
    @Throws(LayoutException::class)
    fun withPage(page: KeyboardPageDefinition): LayoutRegistry {
        val candidate =
            if (pages.any { it.id == page.id }) {
                pages.map { if (it.id == page.id) page else it }
            } else {
                pages + page
            }
        return create(layouts, candidate, compositions)
    }

    /** Drops a user page. A page a pairing still uses, or a built-in preset, is rejected. */
    @Throws(LayoutException::class)
    fun withoutPage(id: String): LayoutRegistry {
        if (page(id) == null) throw LayoutException.Invalid(LayoutInvalidCode.PAGE_UNKNOWN, id)
        val inUse =
            compositions.values.any {
                it.symbolPageID == id || it.numericPageID == id || it.englishPageID == id
            }
        if (inUse) throw LayoutException.Invalid(LayoutInvalidCode.PAGE_IN_USE, id)
        if (KeyboardPagePresets.all.any { it.id == id }) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_PRESET_IS_READ_ONLY, id)
        }
        return create(layouts, pages.filterNot { it.id == id }, compositions)
    }

    /** Saves one keyboard pairing; an incompatible layout or a missing page is rejected. */
    @Throws(LayoutException::class)
    fun withComposition(
        composition: KeyboardComposition,
        keyboardId: String,
    ): LayoutRegistry {
        refuseFamilyIncompatiblePairing(composition, keyboardId)
        return create(layouts, pages, compositions + (keyboardId to composition))
    }

    /** The shipped shape a stored builtin layout names, or null for an unknown wire. */
    private fun kindOf(layout: LayoutInput.Builtin): KeyboardLayoutKind? = KeyboardLayoutKind.fromWire(layout.wire)

    private fun familyOf(inputMethodID: String): InputSchemeFamily {
        // The catalog holds schema ids only: an alphabet is never read here, so an unknown scheme
        // stays in the unrestricted family exactly as it did before the rule.
        return InputLayoutPolicy.familyOf(inputMethodID)
    }

    /** The physical layout the family types on, when it shipped and this keyboard can draw it. */
    private fun familyLayoutId(
        key: KeyboardCompositionKey,
        family: InputSchemeFamily,
    ): String? {
        val id = InputLayoutPolicy.preferredLayoutID(family) ?: return null
        val definition = resolvedLayout(id) ?: return null
        return id.takeIf { definition.supportsComposition(key.inputMethodId, key.layout) }
    }

    /** The saved main layout when the family and the shape binding still allow it. */
    private fun legalMainLayoutId(
        key: KeyboardCompositionKey,
        mainLayoutID: String,
        family: InputSchemeFamily,
        families: LayoutFamilyContext,
    ): String {
        if (!InputLayoutPolicy.allows(mainLayoutID, family)) return defaultMainLayoutId(key, families)
        val definition = resolvedLayout(mainLayoutID) ?: return defaultMainLayoutId(key, families)
        val supports = definition.supportsComposition(key.inputMethodId, key.layout)
        return if (supports) mainLayoutID else defaultMainLayoutId(key, families)
    }

    /** Rejects a new pairing the scheme's family forbids; already-stored pairings are left alone. */
    private fun refuseFamilyIncompatiblePairing(
        composition: KeyboardComposition,
        keyboardId: String,
    ) {
        val separator = keyboardId.lastIndexOf(':')
        val inputMethodId = if (separator > 0) keyboardId.substring(0, separator) else keyboardId
        val family = familyOf(inputMethodId)
        val kind = if (separator > 0) LayoutInput.Builtin.fromWire(keyboardId.substring(separator + 1)) else null
        val forbidden =
            (kind != null && kindOf(kind)?.let { !InputLayoutPolicy.allows(it, family) } == true) ||
                !InputLayoutPolicy.allows(composition.mainLayoutID, family)
        if (forbidden) {
            throw LayoutException.Invalid(LayoutInvalidCode.COMPOSITION_INPUT_METHOD_INCOMPATIBLE, keyboardId)
        }
    }

    /**
     * Drops the pairings whose input method has no provider left, and returns how many were removed.
     * The caller owns the "no provider" judgement (an install was deleted and no other install or
     * built-in scheme still offers that input method); this type owns the map.
     */
    @Throws(LayoutException::class)
    fun withPrunedCompositions(isObsoleteInputMethod: (String) -> Boolean): Pair<LayoutRegistry, Int> {
        val obsolete =
            compositions.keys.filter { keyboardId ->
                val separator = keyboardId.lastIndexOf(':')
                separator >= 0 && isObsoleteInputMethod(keyboardId.substring(0, separator))
            }
        if (obsolete.isEmpty()) return this to 0
        val remaining = compositions.filterKeys { it !in obsolete }
        return create(layouts, pages, remaining) to obsolete.size
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayoutRegistry) return false
        return layouts == other.layouts &&
            pages == other.pages &&
            compositions == other.compositions
    }

    override fun hashCode(): Int {
        var result = layouts.hashCode()
        result = 31 * result + pages.hashCode()
        result = 31 * result + compositions.hashCode()
        return result
    }

    override fun toString(): String =
        "LayoutRegistry(layouts=" +
            layouts.size +
            ", pages=" +
            pages.size +
            ", compositions=" +
            compositions.size +
            ")"

    @Throws(LayoutException::class)
    fun validate() {
        LayoutResourceBudget.validateRegistry(layouts.size, pages, compositions.size)
        if (layouts.map { it.id }.toSet().size != layouts.size) {
            throw LayoutException.Invalid(LayoutInvalidCode.DUPLICATE_LAYOUT_IDS)
        }
        if (pages.map { it.id }.toSet().size != pages.size) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_DUPLICATE_IDS)
        }
        for (page in pages) page.validate()
        for ((keyboardId, composition) in compositions) validateComposition(keyboardId, composition)
        for (layout in layouts) layout.validate()
        LayoutResourceBudget.validateCatalogTotal(layouts.map { LayoutResourceBudget.validate(it) })
        // Presets ship in code and are merged into every catalog, so user pages get their own budget.
        val presetIds = KeyboardPagePresets.codeOwnedIds
        LayoutResourceBudget.validatePageCatalogTotal(
            pages.filter { it.id !in presetIds }.map { LayoutResourceBudget.validate(it) },
        )
    }

    @Throws(LayoutException::class)
    private fun validateComposition(
        keyboardId: String,
        composition: KeyboardComposition,
    ) {
        if (keyboardId.isEmpty() || keyboardId.encodeToByteArray().size > 512) {
            throw LayoutException.Invalid(LayoutInvalidCode.COMPOSITION_KEYBOARD_ID_INVALID, keyboardId)
        }
        val separator = keyboardId.lastIndexOf(':')
        val kind = if (separator > 0) LayoutInput.Builtin.fromWire(keyboardId.substring(separator + 1)) else null
        val main = resolvedLayout(composition.mainLayoutID)
        if (kind == null ||
            main == null ||
            !main.supportsComposition(keyboardId.substring(0, separator), kind)
        ) {
            throw LayoutException.Invalid(
                LayoutInvalidCode.COMPOSITION_INPUT_METHOD_INCOMPATIBLE,
                keyboardId,
            )
        }
        for (kind in KeyboardPageKind.entries) {
            val page = page(composition.pageId(kind))
            if (page == null || page.kind != kind) {
                throw LayoutException.Invalid(
                    LayoutInvalidCode.COMPOSITION_PAGE_KIND_MISMATCH,
                    kind.name + ":" + composition.pageId(kind),
                )
            }
        }
    }

    companion object {
        /**
         * Builds a validated registry. A failed build throws, so callers never hold an invalid one.
         */
        @Throws(LayoutException::class)
        fun create(
            layouts: List<LayoutDefinition> = BuiltinLayouts.all,
            pages: List<KeyboardPageDefinition> = KeyboardPagePresets.all,
            compositions: Map<String, KeyboardComposition> = emptyMap(),
        ): LayoutRegistry {
            val registry =
                LayoutRegistry(layouts, pages, compositions)
            registry.validate()
            return registry
        }

        /**
         * Structural form for the store codec. Decoding must keep corrupt input visible so the single
         * validation step reports it; callers validate right after reading.
         */
        internal fun fromWire(
            layouts: List<LayoutDefinition>,
            pages: List<KeyboardPageDefinition>,
            compositions: Map<String, KeyboardComposition>,
        ): LayoutRegistry = LayoutRegistry(layouts, pages, compositions)
    }
}
