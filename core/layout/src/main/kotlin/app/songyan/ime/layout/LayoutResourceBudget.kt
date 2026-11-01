package app.songyan.ime.layout

/**
 * Shared limits for imported definitions and the catalog consumed by the keyboard. Byte limits
 * bound JSON decoding; model limits bound rendering, lookup and editing work.
 */
object LayoutResourceBudget {
    const val DEFINITION_BYTES: Int = 1024 * 1024
    const val CATALOG_BYTES: Int = 8 * 1024 * 1024
    const val LAYOUTS: Int = 64

    /** User pages one catalog may carry. Built-in presets ship in code and are not counted. */
    const val PAGES: Int = 96
    const val COMPOSITIONS: Int = 256
    const val PAGE_CATEGORIES: Int = 64
    const val CATEGORY_COLUMNS: Int = 10
    const val CATEGORY_KEYS: Int = 2048
    const val RECENT_CATEGORY_KEYS: Int = 100
    const val CATEGORIZED_PAGE_KEYS: Int = 4096
    const val CATEGORIZED_CATALOG_KEYS: Int = 32768

    // MARK: - Materialisation allowance

    /**
     * A legacy page never stored the controls the renderer synthesized for it (Shift/Delete in the
     * last row, the footer and the editing rail). Materializing them into real data must not force
     * the user to delete already-saved keys just to open and save the page, so an authored page gets
     * this bounded allowance on top of the legacy limits.
     */
    const val PAGE_CONTROL_KEYS: Int = 12

    /** The default function area's labels, action values and title modes. */
    const val PAGE_CONTROL_TEXT_BYTES: Int = 1024

    /** The last row may gain the synthesized Shift and Delete. */
    const val PAGE_CONTROL_ROW_KEYS: Int = 2

    /** The default footer's comma carries the five other sentence marks as its long press. */
    const val PAGE_CONTROL_ALTERNATES: Int = 5

    const val CATALOG_CONTROL_KEYS: Int = PAGES * PAGE_CONTROL_KEYS
    const val CATALOG_CONTROL_TEXT_BYTES: Int = PAGES * PAGE_CONTROL_TEXT_BYTES
    const val CATALOG_CONTROL_ALTERNATES: Int = PAGES * PAGE_CONTROL_ALTERNATES

    const val ROWS: Int = 16
    const val AUXILIARY_AREAS: Int = 4
    const val KEYS_PER_REGION: Int = 32
    const val KEYS: Int = 256
    const val ALTERNATES_PER_KEY: Int = 16
    const val ALTERNATES: Int = 1024
    const val TEXT_BYTES: Int = 64 * 1024
    const val CATALOG_KEYS: Int = 4096
    const val CATALOG_ALTERNATES: Int = 16 * 1024
    const val CATALOG_TEXT_BYTES: Int = 1024 * 1024

    /**
     * Text and per-region scale limits, plus bounded aggregate limits. Main layouts keep the legacy
     * limits; an authored page offsets only what materializing its function area adds.
     */
    @Throws(LayoutException::class)
    fun validate(
        layout: LayoutDefinition,
        rowKeyLimit: Int = KEYS_PER_REGION,
        keyLimit: Int = KEYS,
        alternatesLimit: Int = ALTERNATES,
        textLimit: Int = TEXT_BYTES,
    ): Usage {
        checkBudget(layout.rows.size <= ROWS) { "rows" }
        checkBudget((layout.auxiliaryAreas?.size ?: 0) <= AUXILIARY_AREAS) { "auxiliaryAreas" }
        val usage = Usage()

        fun text(
            value: String?,
            maximum: Int,
            detail: String,
        ) {
            if (value == null) return
            val bytes = value.encodeToByteArray().size
            checkBudget(bytes <= maximum) { detail }
            usage.textBytes += bytes
        }

        text(layout.id, 256, "id")
        text(layout.name, 256, "name")
        text(layout.input.schemaID, 256, "schemaID")
        text(layout.note, 8192, "note")

        fun region(
            keys: List<LayoutKey>,
            limit: Int,
        ) {
            checkBudget(keys.size <= limit) { "regionKeys" }
            usage.keys += keys.size
            for (key in keys) {
                val options = key.longPress ?: emptyList()
                checkBudget(options.size <= ALTERNATES_PER_KEY) { "alternatesPerKey" }
                usage.alternates += options.size
                text(key.label, 256, "label")
                text(key.secondaryLabel, 256, "secondaryLabel")
                text(key.action.value, 4096, "actionValue")
                text(key.action.asciiValue, 4096, "actionAsciiValue")
                for (option in options) {
                    text(option.label, 256, "optionLabel")
                    text(option.action.value, 4096, "optionValue")
                    text(option.action.asciiValue, 4096, "optionAsciiValue")
                }
            }
        }

        for (row in layout.rows) region(row.keys, rowKeyLimit)
        for (area in layout.auxiliaryAreas ?: emptyList()) region(area.keys, KEYS_PER_REGION)
        checkBudget(usage.keys <= keyLimit) { "keys" }
        checkBudget(usage.alternates <= alternatesLimit) { "alternates" }
        checkBudget(usage.textBytes <= textLimit) { "textBytes" }
        return usage
    }

    /**
     * Counts an authored function area. Chrome keys are real keys: their labels, alternates and
     * action values spend the same page budget as row keys.
     */
    @Throws(LayoutException::class)
    fun chromeUsage(chrome: KeyboardPageChrome): Usage {
        val usage = Usage()

        fun text(
            value: String?,
            maximum: Int,
        ) {
            if (value == null) return
            val bytes = value.encodeToByteArray().size
            checkBudget(bytes <= maximum) { "chromeText" }
            usage.textBytes += bytes
        }

        fun keys(values: List<LayoutKey>) {
            checkBudget(values.size <= KEYS_PER_REGION) { "chromeRegionKeys" }
            usage.keys += values.size
            for (key in values) {
                val options = key.longPress ?: emptyList()
                checkBudget(options.size <= ALTERNATES_PER_KEY) { "alternatesPerKey" }
                usage.alternates += options.size
                text(key.label, 256)
                text(key.secondaryLabel, 256)
                text(key.action.value, 4096)
                text(key.action.asciiValue, 4096)
                for (option in options) {
                    text(option.label, 256)
                    text(option.action.value, 4096)
                    text(option.action.asciiValue, 4096)
                }
            }
        }

        for (region in KeyboardPageChrome.Region.entries) keys(chrome.keys(region))
        return usage
    }

    @Throws(LayoutException::class)
    fun validate(page: KeyboardPageDefinition): Usage {
        if (page.symbolPresentation != KeyboardSymbolPresentation.CATEGORIZED) {
            val chrome = page.chrome ?: return validate(page.layoutDefinition)
            val control = chromeUsage(chrome)
            // The inserted Shift/Delete labels are part of the row text, so the intermediate check
            // already uses the materialization allowance instead of rejecting a page whose legacy
            // text sat exactly on the old limit.
            val usage =
                validate(
                    page.layoutDefinition,
                    rowKeyLimit = KEYS_PER_REGION + PAGE_CONTROL_ROW_KEYS,
                    keyLimit = KEYS + PAGE_CONTROL_ROW_KEYS,
                    textLimit = TEXT_BYTES + PAGE_CONTROL_TEXT_BYTES,
                )
            usage.keys += control.keys
            usage.alternates += control.alternates
            usage.textBytes += control.textBytes
            checkBudget(usage.keys <= KEYS + PAGE_CONTROL_KEYS) { "pageKeys" }
            checkBudget(usage.alternates <= ALTERNATES + PAGE_CONTROL_ALTERNATES) { "pageAlternates" }
            checkBudget(usage.textBytes <= TEXT_BYTES + PAGE_CONTROL_TEXT_BYTES) { "pageTextBytes" }
            return usage
        }
        checkBudget((page.categories?.size ?: 0) <= PAGE_CATEGORIES) { "pageCategories" }
        // Legacy browser rows remain readable, but authored categories are stored only once.
        val usage = validate(page.layoutDefinition, rowKeyLimit = KEYS_PER_REGION + PAGE_CONTROL_ROW_KEYS)

        fun text(
            value: String?,
            maximum: Int,
        ) {
            if (value == null) return
            val bytes = value.encodeToByteArray().size
            checkBudget(bytes <= maximum) { "categoryText" }
            usage.textBytes += bytes
        }

        for (category in page.categories ?: emptyList()) {
            text(category.id, 256)
            text(category.name, 256)
            checkBudget(category.keys.size <= CATEGORY_KEYS) { "categoryKeys" }
            usage.keys += category.keys.size
            for (key in category.keys) {
                text(key.label, 256)
                text(key.secondaryLabel, 256)
                text(key.action.value, 4096)
                text(key.action.asciiValue, 4096)
                val options = key.longPress ?: emptyList()
                checkBudget(options.size <= ALTERNATES_PER_KEY) { "alternatesPerKey" }
                usage.alternates += options.size
                for (option in options) {
                    text(option.label, 256)
                    text(option.action.value, 4096)
                    text(option.action.asciiValue, 4096)
                }
            }
        }
        val chrome = page.chrome
        if (chrome != null) {
            val control = chromeUsage(chrome)
            usage.keys += control.keys
            usage.alternates += control.alternates
            usage.textBytes += control.textBytes
        }
        checkBudget(usage.keys <= CATEGORIZED_PAGE_KEYS + PAGE_CONTROL_KEYS) { "pageKeys" }
        checkBudget(usage.alternates <= CATALOG_ALTERNATES) { "pageAlternates" }
        checkBudget(usage.textBytes <= CATALOG_TEXT_BYTES + PAGE_CONTROL_TEXT_BYTES) { "pageTextBytes" }
        return usage
    }

    /**
     * Catalog-level limits including the page library. Built-in pages ship in code and are merged
     * into every catalog, so neither the page count nor the catalog totals charge for preset ids;
     * each preset is still validated on its own.
     */
    @Throws(LayoutException::class)
    fun validateRegistry(
        layouts: Int,
        pages: List<KeyboardPageDefinition>,
        compositions: Int,
    ) {
        checkBudget(layouts <= LAYOUTS) { "layouts" }
        val presetIds = KeyboardPagePresets.codeOwnedIds
        val userPages = pages.count { it.id !in presetIds }
        if (userPages > PAGES) {
            throw LayoutException.Invalid(LayoutInvalidCode.REGISTRY_PAGE_LIMIT, "pages=" + userPages)
        }
        if (compositions > COMPOSITIONS) {
            throw LayoutException.Invalid(
                LayoutInvalidCode.REGISTRY_COMPOSITION_LIMIT,
                "compositions=" + compositions,
            )
        }
    }

    /** Aggregate totals so one catalog cannot be unbounded even with individually legal layouts. */
    @Throws(LayoutException::class)
    fun validateCatalogTotal(usages: List<Usage>) {
        val total = Usage()
        for (usage in usages) {
            total.keys += usage.keys
            total.alternates += usage.alternates
            total.textBytes += usage.textBytes
        }
        if (total.keys > CATALOG_KEYS ||
            total.alternates > CATALOG_ALTERNATES ||
            total.textBytes > CATALOG_TEXT_BYTES
        ) {
            throw LayoutException.Invalid(LayoutInvalidCode.CATALOG_TOTAL_EXCEEDED)
        }
    }

    /**
     * Aggregate totals of the page library, which receives its own budget so legacy catalogs retain
     * their existing capacity. Pages get a bounded control-allowance so materializing every page at
     * a legacy limit still validates.
     */
    @Throws(LayoutException::class)
    fun validatePageCatalogTotal(usages: List<Usage>) {
        val total = Usage()
        for (usage in usages) {
            total.keys += usage.keys
            total.alternates += usage.alternates
            total.textBytes += usage.textBytes
        }
        if (total.keys > CATEGORIZED_CATALOG_KEYS + CATALOG_CONTROL_KEYS ||
            total.alternates > CATALOG_ALTERNATES + CATALOG_CONTROL_ALTERNATES ||
            total.textBytes > CATALOG_TEXT_BYTES + CATALOG_CONTROL_TEXT_BYTES
        ) {
            throw LayoutException.Invalid(LayoutInvalidCode.CATALOG_TOTAL_EXCEEDED)
        }
    }

    @Throws(LayoutException::class)
    fun checkBudget(
        condition: Boolean,
        detail: () -> String,
    ) {
        if (!condition) {
            throw LayoutException.Invalid(LayoutInvalidCode.RESOURCE_BUDGET_EXCEEDED, detail())
        }
    }

    /** Bytes of text and counts of keys a layout or page spends. */
    data class Usage(
        var keys: Int = 0,
        var alternates: Int = 0,
        var textBytes: Int = 0,
    )
}
