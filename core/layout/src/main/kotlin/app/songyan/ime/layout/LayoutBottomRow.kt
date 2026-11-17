package app.songyan.ime.layout

/** The authored footer stays separate from encoding rows even when its space key is removed. */
object LayoutBottomRow {
    fun index(layout: LayoutDefinition): Int? =
        layout.rows.lastIndex.takeIf { index ->
            index >= 0 &&
                (
                    layout.rows[index].keys.any { it.action.type == LayoutActionKind.SPACE } ||
                        layout.rows[index].keys.none { it.action.isEngineInput }
                )
        }

    /**
     * Whether the layout's last row is the stand-in a bundled layout stores (the shared control row
     * of its shape): both platforms read it as "draw the keyboard's own bottom row".
     */
    private fun standsIn(layout: LayoutDefinition): Boolean {
        val index = index(layout) ?: return false
        val bundled = layout.input.builtinLayout?.let { builtin -> BuiltinLayouts.byId(builtin.wire) }
        return layout.rows[index].keys == bundled?.rows?.lastOrNull()?.keys
    }

    /**
     * The bottom row the layout writes itself, or null when it leaves it to the keyboard. A row
     * without any 中/英 key gets one: the keyboard menu opens from that key, so a layout can never
     * take the way to it away.
     */
    fun authored(layout: LayoutDefinition): List<LayoutKey>? {
        val index = index(layout) ?: return null
        if (standsIn(layout)) return null
        val keys = layout.rows[index].keys
        return if (hasLanguageSwitch(layout)) keys else listOf(FooterKeys.LANGUAGE) + keys
    }

    private fun hasLanguageSwitch(layout: LayoutDefinition): Boolean =
        (
            layout.rows.flatMap { it.keys } +
                layout.split
                    ?.halves
                    .orEmpty()
                    .flatMap { it.keys } +
                layout.auxiliaryAreas.orEmpty().flatMap { it.keys }
        ).any { it.action.type == LayoutActionKind.LANGUAGE_SWITCH }

    /**
     * [layout] as it is exported: a bottom row that stands in for the bundled one is written out as
     * the row the keyboard draws, so a file made from the export keeps 123, 中/英 and the marks
     * however the user edits it.
     */
    fun exportable(layout: LayoutDefinition): LayoutDefinition {
        val index = index(layout) ?: return layout
        if (!standsIn(layout)) return layout
        val rows = layout.rows.toMutableList()
        rows[index] = LayoutRow(preview(layout))
        return layout.copy(rows = rows)
    }

    /**
     * The bottom row the keyboard draws for [layout] in a text field, which the editor shows and
     * edits: the layout's own row, the nine-key shell's row, or the generated letters footer.
     */
    fun preview(layout: LayoutDefinition): List<LayoutKey> {
        authored(layout)?.let { return it }
        if (PhoneKeypadStructure.matches(layout) && PhoneKeypadRailPolicy.usesBundledRails(layout)) {
            return listOf(
                LayoutKey("123", action = LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC)),
                LayoutKey("中/英", action = LayoutAction.languageSwitch()),
                LayoutKey("空格", width = 2.4, action = LayoutAction(LayoutActionKind.SPACE)),
                LayoutKey("0", action = LayoutAction.literal("0")),
            )
        }
        return FooterKeys.letters()
    }
}
