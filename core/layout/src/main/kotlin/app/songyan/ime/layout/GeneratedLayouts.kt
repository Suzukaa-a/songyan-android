package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.SchemaKeyProfile

/**
 * Layouts made for one scheme from its key profile: the 26-key board with the codes it lacks added,
 * and the stroke pad. Pure builders; the app layer passes the saved name from its strings.
 */
object GeneratedLayouts {
    /** Id prefix every generated layout carries, so the app can tell it needs installing. */
    const val ID_PREFIX = "auto."

    const val KEYS26_SUFFIX = ".keys26"

    fun keys26Id(schemaId: String): String = "$ID_PREFIX$schemaId$KEYS26_SUFFIX"

    /** The codes a generated 26-key [layout] sends beyond the standard 26-key board, in key order. */
    fun addedCodes(layout: LayoutDefinition): List<String> {
        val standard = LayoutCodeCoverage.declared(BuiltinLayouts.qwerty26)
        return LayoutCodeCoverage.declared(layout).filter { it !in standard }
    }

    fun strokesId(schemaId: String): String = "$ID_PREFIX$schemaId.strokes"

    fun isGeneratedId(id: String): Boolean = id.startsWith(ID_PREFIX)

    private const val ROW_TWO_EXTRAS = 2
    private const val DIGITS = "1234567890"

    /**
     * The built-in 26-key board plus the codes it cannot send: needed digits become a new top row in
     * keyboard order, the first two other symbols end row two, and any more join the top row. Every
     * added key is a code key captioned with its character.
     */
    fun keys26(
        schemaId: String,
        name: String,
        profile: SchemaKeyProfile,
    ): LayoutDefinition {
        val base = BuiltinLayouts.qwerty26
        val missing = LayoutCodeCoverage.missing(base, profile)
        val digits = DIGITS.map { it.toString() }.filter { it in missing }
        val symbols = missing.filter { it !in digits }
        val rows = base.rows.toMutableList()
        val rowTwo = rows[1].keys + symbols.take(ROW_TWO_EXTRAS).map(::codeKey)
        rows[1] = LayoutRow(rowTwo)
        val rest = symbols.drop(ROW_TWO_EXTRAS).map(::codeKey)
        if (digits.isNotEmpty()) {
            rows.add(0, LayoutRow(digits.map(::codeKey) + rest))
        } else if (rest.isNotEmpty()) {
            rows[0] = LayoutRow(rows[0].keys + rest)
        }
        return base.copy(
            id = keys26Id(schemaId),
            name = name,
            input = LayoutInput(schemaID = schemaId, builtinLayout = LayoutInput.Builtin.QWERTY26),
            rows = rows,
        )
    }

    /**
     * The stroke pad: the first three strokes on row one, the rest and backspace on row two, then the
     * standard bottom row. Keys carry the scheme's captions.
     */
    fun strokes(
        schemaId: String,
        name: String,
        profile: SchemaKeyProfile,
    ): LayoutDefinition {
        val keys = profile.strokes.map { LayoutKey(label = it.label, action = LayoutAction.code(it.code)) }
        val backspace = LayoutKey(label = "⌫", action = LayoutAction(LayoutActionKind.BACKSPACE))
        return LayoutDefinition(
            id = strokesId(schemaId),
            name = name,
            input = LayoutInput(schemaID = schemaId, builtinLayout = LayoutInput.Builtin.QWERTY26),
            rows =
                listOf(
                    LayoutRow(keys.take(3)),
                    LayoutRow(keys.drop(3) + backspace),
                    BuiltinLayouts.qwerty26.rows.last(),
                ),
        )
    }

    private fun codeKey(code: String): LayoutKey = LayoutKey(label = code, action = LayoutAction.code(code))
}
