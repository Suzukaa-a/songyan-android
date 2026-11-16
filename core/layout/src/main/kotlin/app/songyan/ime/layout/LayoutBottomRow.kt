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

    fun authored(layout: LayoutDefinition): List<LayoutKey>? {
        val index = index(layout) ?: return null
        val keys = layout.rows[index].keys
        val bundled = layout.input.builtinLayout?.let { builtin -> BuiltinLayouts.byId(builtin.wire) }
        return keys.takeUnless { it == bundled?.rows?.lastOrNull()?.keys }
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
