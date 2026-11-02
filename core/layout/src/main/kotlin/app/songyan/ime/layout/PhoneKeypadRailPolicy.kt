package app.songyan.ime.layout

/** What a phone-keypad rail should render. Kept as data so the decision is testable without UI. */
sealed class PhoneKeypadRailContent {
    /** Bundled T9 only: the shell own authored rail. Never produced for an imported definition. */
    data object BundledAuthored : PhoneKeypadRailContent()

    /** An imported definition declared this many keys; render exactly those keys. */
    data class Declared(
        val keyCount: Int,
    ) : PhoneKeypadRailContent()

    /** Nothing declared and not the bundled shell: render nothing. */
    data object Empty : PhoneKeypadRailContent()
}

/**
 * The single rail policy shared by both phone-keypad rails. Only the bundled T9 may receive the
 * shell-authored static keys; an imported keypad keeps its declared keys, so the shell never
 * fabricates a Delete/separator for a layout that did not author one.
 */
object PhoneKeypadRailPolicy {
    /**
     * The fixed trailing column spans for the bundled shell, as on mainstream pinyin 9-key pads:
     * Delete on row 0, 重输 on row 1, and Return/Confirm across row 2 and the control row.
     */
    val BUNDLED_TRAILING_SPANS: List<Int> = listOf(1, 1, 2)

    fun content(
        isBuiltinPhonePad: Boolean,
        declaredKeyCount: Int,
    ): PhoneKeypadRailContent =
        when {
            isBuiltinPhonePad -> PhoneKeypadRailContent.BundledAuthored
            declaredKeyCount > 0 -> PhoneKeypadRailContent.Declared(declaredKeyCount)
            else -> PhoneKeypadRailContent.Empty
        }

    fun leading(
        isBuiltinPhonePad: Boolean,
        declaredKeyCount: Int,
    ): PhoneKeypadRailContent = content(isBuiltinPhonePad, declaredKeyCount)

    fun trailing(
        isBuiltinPhonePad: Boolean,
        declaredKeyCount: Int,
    ): PhoneKeypadRailContent = content(isBuiltinPhonePad, declaredKeyCount)

    /**
     * The keys a definition declares for one rail. Engine-disambiguation areas are excluded (they
     * are runtime-only).
     */
    fun declaredKeys(
        definition: LayoutDefinition,
        position: LayoutAuxiliaryArea.Position,
    ): List<LayoutKey> =
        (definition.auxiliaryAreas ?: emptyList())
            .filter { it.position == position && !it.requiresEngineDisambiguation }
            .flatMap { it.keys }

    /** The definition declared non-encoding control row, if any. */
    fun controlRowKeys(definition: LayoutDefinition): List<LayoutKey>? {
        val last = definition.rows.lastOrNull() ?: return null
        val isControl =
            last.keys.all {
                it.action.type != LayoutActionKind.CODE
            }
        return if (isControl) last.keys else null
    }

    /**
     * Whether the definition still declares the preset's rails. Only then does the shell draw its own
     * quick-symbol and editing rails; any rail edit makes the declared keys what is drawn.
     */
    fun usesBundledRails(definition: LayoutDefinition): Boolean =
        definition.auxiliaryAreas == BuiltinLayouts.phone9.auxiliaryAreas

    /** A rail shows at most this many keys: more would shrink each key below a comfortable tap target. */
    const val MAX_RAIL_KEYS: Int = 4

    /**
     * Vertical spans, in grid rows, of the keys a declared rail draws beside the three digit rows.
     * Keys without an authored span follow the telephone-pad rule ([PhoneKeypadMetrics.slotSpans]: a
     * short rail gives its surplus to the first key, a long one shares the height equally); once any
     * key carries a span, the spans share the height in proportion. Keys past [MAX_RAIL_KEYS] get none.
     */
    fun declaredSpans(keys: List<LayoutKey>): List<Double> {
        val shown = keys.take(MAX_RAIL_KEYS)
        if (shown.isEmpty()) return emptyList()
        val rows = PhoneKeypadMetrics.MAIN_ROW_COUNT.toDouble()
        if (shown.any { it.width != null }) {
            val total = shown.sumOf { it.widthWeight }
            return shown.map { rows * it.widthWeight / total }
        }
        if (shown.size > PhoneKeypadMetrics.MAIN_ROW_COUNT) return shown.map { rows / shown.size }
        return PhoneKeypadMetrics.slotSpans(shown.size).map { it.toDouble() }
    }

    /**
     * The areas of [definition] with both rails declared as data: what the shell draws for the preset
     * (quick symbols on the left, Delete and 重输 on the right) becomes editable keys. The engine's
     * reading areas and any other areas are kept as they are.
     */
    fun withDeclaredRails(
        definition: LayoutDefinition,
        savedQuickSymbols: String = "",
    ): LayoutDefinition {
        if (!usesBundledRails(definition)) return definition
        val kept =
            (definition.auxiliaryAreas ?: emptyList()).filter {
                it.role == LayoutAuxiliaryArea.Role.DISAMBIGUATION
            }
        val symbols = definition.effectiveT9QuickSymbols(savedQuickSymbols)
        val leading =
            BuiltinLayouts
                .t9QuickRailValues(symbols, ascii = false)
                .zip(BuiltinLayouts.t9QuickRailValues(symbols, ascii = true)) { value, asciiValue ->
                    LayoutKey(
                        value,
                        action =
                            LayoutAction.literal(
                                value,
                                asciiValue =
                                    asciiValue.takeIf {
                                        it != value
                                    },
                            ),
                        titleMode = LayoutKeyTitleMode.AUTOMATIC,
                    )
                }
        val trailing =
            listOf(
                LayoutKey("⌫", action = LayoutAction(LayoutActionKind.BACKSPACE)),
                LayoutKey("重输", width = 2.0, action = LayoutAction(LayoutActionKind.CLEAR_INPUT)),
            )
        val areas =
            kept +
                LayoutAuxiliaryArea(
                    position = LayoutAuxiliaryArea.Position.LEADING,
                    role = LayoutAuxiliaryArea.Role.PUNCTUATION,
                    keys = leading,
                ) +
                LayoutAuxiliaryArea(
                    position = LayoutAuxiliaryArea.Position.TRAILING,
                    role = LayoutAuxiliaryArea.Role.EDITING,
                    keys = trailing,
                )
        return definition.copy(auxiliaryAreas = areas)
    }
}
