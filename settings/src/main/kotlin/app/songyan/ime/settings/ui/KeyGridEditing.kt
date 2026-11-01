package app.songyan.ime.settings.ui

import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutPageJump
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRow
import app.songyan.ime.layout.LayoutRowsEditing

/** The key the editor has selected: its row and its place in the row. */
data class KeySelection(
    val row: Int,
    val key: Int,
)

/** The rows after an edit and the key the editor should select next. */
data class GridEdit(
    val rows: List<LayoutRow>,
    val selection: KeySelection,
)

/**
 * The structural edits the layout and page editors offer on the key grid. Pure, so the rules (what
 * is selected after a delete, where a new key lands) are unit-tested; every edit returns null when it
 * cannot finish and the grid stays as it was.
 */
object KeyGridEditing {
    /** A new key after the selected one, in the same row. */
    fun addKeyAfter(
        rows: List<LayoutRow>,
        at: KeySelection,
        template: LayoutKey,
    ): GridEdit? =
        LayoutRowsEditing
            .insertKey(rows, at.row, at.key + 1, template)
            ?.let { GridEdit(it, KeySelection(at.row, at.key + 1)) }

    /** Removes the selected key; the selection moves to its neighbour, or to the row before when the row went. */
    fun removeKey(
        rows: List<LayoutRow>,
        at: KeySelection,
    ): GridEdit? {
        val next = LayoutRowsEditing.removeKey(rows, at.row, at.key) ?: return null
        val row =
            if (at.row in next.indices &&
                next[at.row].keys.isNotEmpty() &&
                next.size == rows.size
            ) {
                at.row
            } else {
                (at.row - 1).coerceAtLeast(0)
            }
        val key = if (next.size == rows.size) at.key.coerceAtMost(next[row].keys.lastIndex) else 0
        return GridEdit(next, KeySelection(row.coerceAtMost(next.lastIndex), key))
    }

    fun moveKey(
        rows: List<LayoutRow>,
        at: KeySelection,
        delta: Int,
    ): GridEdit? =
        LayoutRowsEditing
            .moveKey(
                rows,
                at.row,
                at.key,
                delta,
            )?.let { GridEdit(it, KeySelection(at.row, at.key + delta)) }

    /** A new row under the last one, selecting its only key. */
    fun addRow(
        rows: List<LayoutRow>,
        template: LayoutKey,
    ): GridEdit? = LayoutRowsEditing.addRow(rows, template)?.let { GridEdit(it, KeySelection(it.lastIndex, 0)) }

    fun moveRow(
        rows: List<LayoutRow>,
        at: KeySelection,
        delta: Int,
    ): GridEdit? =
        LayoutRowsEditing.moveRow(rows, at.row, delta)?.let {
            GridEdit(it, KeySelection(at.row + delta, at.key))
        }

    /** Replaces the selected key; an address that is gone is a no-op. */
    fun replaceKey(
        rows: List<LayoutRow>,
        at: KeySelection,
        key: LayoutKey,
    ): List<LayoutRow> =
        rows.mapIndexed { r, row ->
            if (r !=
                at.row
            ) {
                row
            } else {
                LayoutRow(row.keys.mapIndexed { k, existing -> if (k == at.key) key else existing })
            }
        }
}

/** The actions a key of the editor can have, in the order the picker lists them. */
enum class KeyActionChoice(
    val kind: LayoutActionKind,
) {
    CODE(LayoutActionKind.CODE),
    LITERAL(LayoutActionKind.LITERAL),
    SPACE(LayoutActionKind.SPACE),
    BACKSPACE(LayoutActionKind.BACKSPACE),
    ENTER(LayoutActionKind.ENTER),
    SHIFT(LayoutActionKind.SHIFT),
    LANGUAGE_SWITCH(LayoutActionKind.LANGUAGE_SWITCH),
    PAGE_SWITCH(LayoutActionKind.PAGE_SWITCH),
    JUMP(LayoutActionKind.LAYOUT_PAGE_SWITCH),
    SYMBOL_LOCK(LayoutActionKind.SYMBOL_LOCK),
    CLEAR_INPUT(LayoutActionKind.CLEAR_INPUT),
    MODE_SWITCH(LayoutActionKind.MODE_SWITCH),
    GLOBE(LayoutActionKind.GLOBE),
    ;

    /** Whether the action carries a value the user types. */
    val takesText: Boolean get() = this == CODE || this == LITERAL

    /** The action of this kind for [key]; a value is carried over only when it makes sense for the kind. */
    fun actionFor(key: LayoutKey): LayoutAction =
        when (this) {
            CODE ->
                LayoutAction.code(
                    key.action.value?.takeIf { key.action.type == LayoutActionKind.CODE } ?: key.label.take(1),
                )
            LITERAL ->
                LayoutAction.literal(
                    key.action.value?.takeIf { key.action.type == LayoutActionKind.LITERAL } ?: key.label,
                )
            PAGE_SWITCH -> LayoutAction.pageSwitch(key.action.pageSwitchTarget ?: LayoutPageTarget.NUMERIC)
            JUMP -> LayoutAction.layoutPageSwitch(key.action.layoutPageJump ?: LayoutPageJump.all.first())
            MODE_SWITCH -> LayoutAction(LayoutActionKind.MODE_SWITCH, value = "nextLayout")
            else -> LayoutAction(kind)
        }

    companion object {
        /**
         * The kinds a key may take: pages never send engine codes, and only a page (never a main
         * layout's letters) carries a key that jumps to another layout's page.
         */
        fun allowed(
            allowCode: Boolean,
            allowJump: Boolean = false,
        ): List<KeyActionChoice> = entries.filter { (allowCode || it != CODE) && (allowJump || it != JUMP) }

        fun of(action: LayoutAction): KeyActionChoice? = entries.firstOrNull { it.kind == action.type }
    }
}

/** Editing a key that jumps to another layout's page. */
object JumpKeyEditing {
    /**
     * [key] made to jump to [target]. Its label follows the target's page ([pageLabel], e.g. "123")
     * while it is empty or still the label of the page it pointed at; a label the user wrote is kept.
     */
    fun withTarget(
        key: LayoutKey,
        target: LayoutPageJump,
        pageLabel: (LayoutPageTarget) -> String,
    ): LayoutKey {
        val previous = key.action.layoutPageJump
        val generated = key.label.isBlank() || (previous != null && key.label == pageLabel(previous.page))
        val label = if (generated || previous == null) pageLabel(target.page) else key.label
        return key.copy(label = label, action = LayoutAction.layoutPageSwitch(target))
    }
}
