package app.songyan.ime.layout

/**
 * Character-row geometry for a keyboard page, in dp. The page editor, the keyboard and the preview
 * all resolve the same character and control widths through this type.
 */
data class KeyboardPageRowGeometry(
    val keyIndices: List<Int>,
    val keyWidths: List<Double>,
    /**
     * Legacy (chrome == null) pages only: the geometry still synthesizes the leading control (Shift
     * on ABC, the 123/symbol switch elsewhere) and Delete around the last row. Authored pages carry
     * those keys as data, so both stay false and [keyWidths] has one entry per [keyIndices] element.
     */
    val showsLeadingControl: Boolean,
    val showsDelete: Boolean,
    val sideWidth: Double,
    val rowWidth: Double,
    val inset: Double,
    val gap: Double,
    /** Full-row index of the split cut, or the row's key count when it is not split. */
    val splitIndex: Int,
    /**
     * The rows are the standard 10 / 9 / (Shift + 7 letters + Delete) alphabet or the 10 / 10 /
     * (switch + 7 marks + Delete) 123 / symbol shape, so they keep equal character columns instead
     * of relative weights.
     */
    val usesStandardAlphabet: Boolean,
) {
    companion object {
        private val EMPTY =
            KeyboardPageRowGeometry(
                keyIndices = emptyList(),
                keyWidths = emptyList(),
                showsLeadingControl = false,
                showsDelete = false,
                sideWidth = 0.0,
                rowWidth = 0.0,
                inset = 0.0,
                gap = 0.0,
                splitIndex = 0,
                usesStandardAlphabet = false,
            )

        fun resolve(
            width: Double,
            page: KeyboardPageDefinition,
            row: Int,
            split: LayoutSplitConfiguration?,
            showsGlobe: Boolean = true,
            spacing: Double = 4.0,
        ): KeyboardPageRowGeometry {
            if (row !in page.rows.indices) return EMPTY
            val resolvedWidth = if (width.isFinite()) maxOf(0.0, width) else 0.0
            val keys = page.rows[row].keys
            val chrome = page.chrome
            if (chrome != null) return authored(resolvedWidth, page, row, keys.size, split, spacing)
            // Legacy page: exactly the old synthesis, so old catalogs render unchanged.
            val keyIndices =
                keys.indices.filter { showsGlobe || keys[it].action.type != LayoutActionKind.GLOBE }
            val visible = keyIndices.map { keys[it] }
            val counts = page.rows.map { it.keys.size }
            val declared = page.rows.flatMap { it.keys }
            val english = page.kind == KeyboardPageKind.ENGLISH
            val alphabet = english && counts == listOf(10, 9, 7) && declared.all { isSingleLetterKey(it) }
            val markRows = !english && counts == listOf(10, 10, 7) && declared.all { isMarkKey(it) }
            val standardRows = alphabet || markRows
            val last = row == page.rows.size - 1
            val leadingType = if (english) LayoutActionKind.SHIFT else LayoutActionKind.PAGE_SWITCH
            val showsLeadingControl = last && declared.none { it.action.type == leadingType }
            val showsDelete = last && declared.none { it.action.type == LayoutActionKind.BACKSPACE }
            val standard = StandardKeyRowGeometry.resolve(resolvedWidth, split, spacing)
            val sideWidth = if (standardRows) standard.thirdRowSideWidth else maxOf(44.0, standard.thirdRowSideWidth)
            val rowWidth =
                maxOf(
                    0.0,
                    resolvedWidth -
                        (if (showsLeadingControl) sideWidth + spacing else 0.0) -
                        (if (showsDelete) sideWidth + spacing else 0.0),
                )
            val gap =
                if (standardRows) {
                    standard.gap
                } else {
                    split?.gap(rowWidth, visible.size, spacing) ?: 0.0
                }
            val splitIndex = split?.breakIndex(row, visible.size, width) ?: visible.size
            val available = maxOf(0.0, rowWidth - maxOf(0, visible.size - 1) * spacing - gap)
            val keyWidths: List<Double>
            val inset: Double
            if (standardRows) {
                keyWidths = visible.map { standard.keyWidth }
                inset = maxOf(0.0, (available - visible.size * standard.keyWidth) / 2)
            } else {
                val projection = LayoutRowWidthProjection.of(visible.map { it.widthWeight })
                keyWidths = projection.keyFractions.map { available * it }
                inset = available * projection.insetFraction
            }
            return KeyboardPageRowGeometry(
                keyIndices = keyIndices,
                keyWidths = keyWidths,
                showsLeadingControl = showsLeadingControl,
                showsDelete = showsDelete,
                sideWidth = sideWidth,
                rowWidth = rowWidth,
                inset = inset,
                gap = gap,
                splitIndex = splitIndex,
                usesStandardAlphabet = standardRows,
            )
        }

        /** An authored (chrome != null) page carries its controls as data, so nothing is synthesized. */
        private fun authored(
            width: Double,
            page: KeyboardPageDefinition,
            row: Int,
            keyCount: Int,
            split: LayoutSplitConfiguration?,
            spacing: Double,
        ): KeyboardPageRowGeometry {
            val keys = page.rows[row].keys
            val last = row == page.rows.size - 1
            val standard = standardRowsStructure(page)
            val metrics = StandardKeyRowGeometry.resolve(width, split, spacing)
            if (!standard) {
                val gap = if (keys.size > 1) split?.gap(width, keys.size, spacing) ?: 0.0 else 0.0
                val splitIndex = split?.breakIndex(row, keyCount, width) ?: keyCount
                val available = maxOf(0.0, width - maxOf(0, keys.size - 1) * spacing - gap)
                val keyWidths: List<Double>
                val inset: Double
                if (keys.isEmpty()) {
                    keyWidths = emptyList()
                    inset = 0.0
                } else {
                    val projection = LayoutRowWidthProjection.of(keys.map { it.widthWeight })
                    keyWidths = projection.keyFractions.map { available * it }
                    inset = available * projection.insetFraction
                }
                return KeyboardPageRowGeometry(
                    keyIndices = keys.indices.toList(),
                    keyWidths = keyWidths,
                    showsLeadingControl = false,
                    showsDelete = false,
                    sideWidth = 0.0,
                    rowWidth = maxOf(0.0, width),
                    inset = inset,
                    gap = gap,
                    splitIndex = splitIndex,
                    usesStandardAlphabet = false,
                )
            }
            val gap = metrics.gap
            val sideWidth = metrics.thirdRowSideWidth
            val splitIndex = split?.breakIndex(row, keyCount, width) ?: keyCount
            val rowWidth: Double
            val keyWidths: List<Double>
            val inset: Double
            if (last) {
                // Shift/Delete take StandardKeyRowGeometry's real side width, not a literal
                // 1.5 * keyWidth: the two differ by spacing / 2, and the row must still end exactly
                // on the keyboard edge.
                val middle = keys.drop(1).dropLast(1)
                rowWidth = maxOf(0.0, width - sideWidth * 2 - spacing * 2)
                keyWidths = listOf(sideWidth) + middle.map { metrics.keyWidth } + listOf(sideWidth)
                val available = maxOf(0.0, rowWidth - maxOf(0, middle.size - 1) * spacing - gap)
                inset = maxOf(0.0, (available - middle.size * metrics.keyWidth) / 2)
            } else {
                rowWidth = maxOf(0.0, width)
                keyWidths = keys.map { metrics.keyWidth }
                val available = maxOf(0.0, width - maxOf(0, keys.size - 1) * spacing - gap)
                inset = maxOf(0.0, (available - keys.size * metrics.keyWidth) / 2)
            }
            return KeyboardPageRowGeometry(
                keyIndices = keys.indices.toList(),
                keyWidths = keyWidths,
                showsLeadingControl = false,
                showsDelete = false,
                sideWidth = sideWidth,
                rowWidth = rowWidth,
                inset = inset,
                gap = gap,
                splitIndex = splitIndex,
                usesStandardAlphabet = true,
            )
        }

        /**
         * Structural recognition (never by page id): 10 / 9 / Shift + 7 letters + Delete, or 10 / 10 /
         * switch + 7 marks + Delete on a 123 / symbol page, with side weights 1.5 and every other
         * weight nil or 1. Any other valid weight falls back to real relative sizing, so a side set to
         * 1 really gets narrower.
         */
        private fun standardRowsStructure(page: KeyboardPageDefinition): Boolean {
            if (page.rows.size != 3) return false
            val english = page.kind == KeyboardPageKind.ENGLISH
            if (page.rows.map { it.keys.size } != listOf(10, if (english) 9 else 10, 9)) return false
            val lastRow = page.rows[2].keys
            val leadingType = if (english) LayoutActionKind.SHIFT else LayoutActionKind.PAGE_SWITCH
            if (lastRow.first().action.type != leadingType ||
                lastRow.last().action.type != LayoutActionKind.BACKSPACE ||
                lastRow.first().widthWeight != 1.5 ||
                lastRow.last().widthWeight != 1.5
            ) {
                return false
            }
            val character: (LayoutKey) -> Boolean = if (english) ::isSingleLetterKey else ::isMarkKey
            for ((index, row) in page.rows.withIndex()) {
                val middle = if (index == 2) row.keys.drop(1).dropLast(1) else row.keys
                if (!middle.all(character)) return false
            }
            return true
        }

        /** One mark per equal-width column on a 123 / symbol page. */
        private fun isMarkKey(key: LayoutKey): Boolean =
            key.widthWeight == 1.0 && key.action.type == LayoutActionKind.LITERAL

        /**
         * The standard alphabet's letters are literal single characters, not just equal-weight keys:
         * replacing one with a control or a phrase must fall back to real relative sizing.
         */
        private fun isSingleLetterKey(key: LayoutKey): Boolean {
            if (key.widthWeight != 1.0 || key.action.type != LayoutActionKind.LITERAL) return false
            val value = key.action.value ?: return false
            return value.length == 1 && value[0].isLetter()
        }
    }
}
