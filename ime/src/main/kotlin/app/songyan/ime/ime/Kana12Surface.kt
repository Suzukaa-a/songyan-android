package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutKeyTitleMode
import app.songyan.ime.layout.LayoutPageTarget

/**
 * The twelve-key kana surface as the selected layout describes it.
 *
 * The layout authored the whole board: a 4x3 grid of kana keys between its leading and trailing
 * auxiliary areas. The grid keys keep their authored labels, so a page the user edited still reads
 * as authored while the kana model owns what each row types. A definition that is not a twelve-key
 * board (another row count, a grid key that types no kana, or a missing rail) returns null, and the
 * ordinary row rendering keeps the keyboard usable.
 */
internal class Kana12Surface private constructor(
    val gridRows: List<List<LayoutKey>>,
    /** What each grid key types, in [gridKeys] order; null for a grid key with no kana row. */
    val gridRowIds: List<String?>,
    val leading: List<LayoutKey>,
    val trailing: List<LayoutKey>,
) {
    val gridKeys: List<LayoutKey> = gridRows.flatten()

    companion object {
        /**
         * The surface [definition] describes, or null when it is not a twelve-key kana board.
         *
         * [mainLayout] is the selected main layout when the page's own definition carries no
         * auxiliary areas: the page builder keeps a page's rows but not the main layout's rails.
         */
        fun of(
            definition: LayoutDefinition,
            mainLayout: LayoutDefinition? = null,
        ): Kana12Surface? {
            val rows = definition.rows
            if (rows.size != Kana12Geometry.BAND_COUNT) return null
            val grid = rows.take(Kana12Geometry.BAND_COUNT)
            if (grid.any { row -> row.keys.size != Kana12Geometry.GRID_COLUMNS }) return null
            if (!grid.all { row -> row.keys.all { isKana12GridKey(it.action) } }) return null
            if (!grid.any { row -> row.keys.any { kana12RowId(it.action) != null } }) return null
            val areas = definition.auxiliaryAreas.orEmpty() + mainLayout?.auxiliaryAreas.orEmpty()
            val leading = areas.firstOrNull { it.position == LayoutAuxiliaryArea.Position.LEADING }?.keys.orEmpty()
            val trailing = areas.firstOrNull { it.position == LayoutAuxiliaryArea.Position.TRAILING }?.keys.orEmpty()
            if (leading.isEmpty() || trailing.isEmpty()) return null
            val gridRows = grid.map { row -> row.keys }
            return Kana12Surface(gridRows, gridRows.flatten().map(::kana12GridRowId), leading, trailing)
        }
    }
}

/**
 * A grid key of the twelve-key board: a kana row, the modifier, or the punctuation literal.
 *
 * The punctuation key is authored as a plain literal because its tap mark is inserted as text
 * rather than sent to the engine; the kana model still owns that row's flicks.
 */
internal fun isKana12GridKey(action: LayoutAction): Boolean =
    action.type == LayoutActionKind.KANA ||
        action.type == LayoutActionKind.KANA_MODIFIER ||
        action.type == LayoutActionKind.LITERAL

/** A key the kana model itself types: a kana row or the modifier, never a surrounding control. */
internal fun isKana12KanaRow(action: LayoutAction): Boolean =
    action.type == LayoutActionKind.KANA || action.type == LayoutActionKind.KANA_MODIFIER

/** A kana key or the modifier draw as keycaps; every other key of the surface is a control. */
internal fun isKana12KeyFace(action: LayoutAction): Boolean = isKana12KanaRow(action)

/** The caret key of the leading rail; it draws its stored arrow and moves the composition caret. */
internal fun isKana12CaretKey(action: LayoutAction): Boolean = action.type == LayoutActionKind.CURSOR_LEFT

/** The row id a kana key types, or null for the modifier, the punctuation literal and every control. */
internal fun kana12RowId(action: LayoutAction): String? =
    if (action.type == LayoutActionKind.KANA) action.value?.takeIf { it.isNotEmpty() } else null

/**
 * The kana row a grid key types.
 *
 * A kana key names its row. The punctuation literal is matched to the kana model's punctuation row
 * by the cap it shows or the mark it types, so its flicks stay available while the key keeps the
 * face its author saved.
 */
internal fun kana12GridRowId(key: LayoutKey): String? {
    kana12RowId(key.action)?.let { return it }
    if (key.action.type != LayoutActionKind.LITERAL) return null
    Kana12Rows.all.firstOrNull { row -> row.keycap == key.label }?.let { return it.id }
    val text = key.action.value ?: return null
    return Kana12Rows.all.firstOrNull { row -> row.center == text }?.id
}

/**
 * The leading rail as drawn. The system switch key only shows when the user asked for it; without
 * it the slot becomes the numbers key instead of a hole or a stretched neighbour, because the kana
 * board has no digits of its own.
 */
internal fun kana12LeadingRail(
    leading: List<LayoutKey>,
    showsGlobe: Boolean,
): List<LayoutKey> =
    if (showsGlobe) {
        leading
    } else {
        leading.map { key ->
            if (key.action.type == LayoutActionKind.GLOBE) {
                key.copy(
                    label = "123",
                    secondaryLabel = null,
                    action = LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC),
                    longPress = null,
                    titleMode = LayoutKeyTitleMode.AUTOMATIC,
                )
            } else {
                key
            }
        }
    }

/**
 * What [action] means on the twelve-key surface, or null when the surface does not own it and the
 * ordinary layout dispatch must handle the key (for example a page switch to a page this contract
 * has no intent for).
 */
internal fun kana12Intent(
    action: LayoutAction,
    direction: Kana12Direction = Kana12Direction.Center,
): Kana12Action? =
    when (action.type) {
        LayoutActionKind.KANA ->
            kana12RowId(action)?.let { rowId -> Kana12Action.Kana(rowId, direction) }
        LayoutActionKind.KANA_MODIFIER -> Kana12Action.Modify
        LayoutActionKind.CURSOR_LEFT -> Kana12Action.CursorLeft
        LayoutActionKind.LITERAL ->
            Kana12Rows.all
                .firstOrNull { row -> row.center == action.value }
                ?.let { row -> Kana12Action.Kana(row.id, direction) }
        LayoutActionKind.PAGE_SWITCH ->
            if (action.pageSwitchTarget == LayoutPageTarget.SYMBOLS) Kana12Action.Symbols else null
        LayoutActionKind.LANGUAGE_SWITCH -> Kana12Action.Language
        LayoutActionKind.BACKSPACE -> Kana12Action.Backspace
        LayoutActionKind.SPACE -> Kana12Action.Space
        LayoutActionKind.ENTER -> Kana12Action.Enter
        LayoutActionKind.GLOBE -> Kana12Action.Globe
        else -> null
    }
