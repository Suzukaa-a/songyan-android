package app.songyan.ime.settings.ui

import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.PhoneKeypadRailPolicy

/** A key of one side column of a nine-key layout: which column and its place in it. */
data class RailSelection(
    val position: LayoutAuxiliaryArea.Position,
    val index: Int,
)

/**
 * The structural edits of a nine-key layout's side columns. A side column is the keys its layout
 * declares ([PhoneKeypadRailPolicy.declaredKeys]); while the layout still declares the preset's
 * columns the keyboard draws its own, so editing starts by declaring them as data. The engine's
 * reading area is never touched. Every edit returns null when it cannot finish.
 */
object RailEditing {
    fun keys(
        layout: LayoutDefinition,
        position: LayoutAuxiliaryArea.Position,
    ): List<LayoutKey> = PhoneKeypadRailPolicy.declaredKeys(layout, position)

    /** Replaces the keys of one column; its areas collapse into one that keeps the first one's role. */
    fun withKeys(
        layout: LayoutDefinition,
        position: LayoutAuxiliaryArea.Position,
        keys: List<LayoutKey>,
    ): LayoutDefinition {
        val areas = layout.auxiliaryAreas.orEmpty()
        val isDeclared = { area: LayoutAuxiliaryArea ->
            area.position == position && !area.requiresEngineDisambiguation
        }
        val role =
            areas.firstOrNull(isDeclared)?.role
                ?: if (position == LayoutAuxiliaryArea.Position.LEADING) {
                    LayoutAuxiliaryArea.Role.PUNCTUATION
                } else {
                    LayoutAuxiliaryArea.Role.EDITING
                }
        val first = areas.indexOfFirst(isDeclared)
        val rebuilt = LayoutAuxiliaryArea(position, role, keys)
        val result =
            if (first < 0) {
                areas + rebuilt
            } else {
                areas.mapIndexedNotNull { index, area ->
                    when {
                        index == first -> rebuilt
                        isDeclared(area) -> null
                        else -> area
                    }
                }
            }
        return layout.copy(auxiliaryAreas = result)
    }

    fun add(
        layout: LayoutDefinition,
        position: LayoutAuxiliaryArea.Position,
        key: LayoutKey,
    ): Pair<LayoutDefinition, RailSelection>? {
        val current = keys(layout, position)
        if (current.size >= PhoneKeypadRailPolicy.MAX_RAIL_KEYS) return null
        return withKeys(layout, position, current + key) to RailSelection(position, current.size)
    }

    fun remove(
        layout: LayoutDefinition,
        at: RailSelection,
    ): Pair<LayoutDefinition, RailSelection?>? {
        val current = keys(layout, at.position)
        if (at.index !in current.indices) return null
        val next = current.filterIndexed { index, _ -> index != at.index }
        val selection = if (next.isEmpty()) null else RailSelection(at.position, minOf(at.index, next.lastIndex))
        return withKeys(layout, at.position, next) to selection
    }

    fun move(
        layout: LayoutDefinition,
        at: RailSelection,
        delta: Int,
    ): Pair<LayoutDefinition, RailSelection>? {
        val current = keys(layout, at.position)
        val target = at.index + delta
        if (at.index !in current.indices || target !in current.indices) return null
        val moved = current.toMutableList()
        moved[at.index] = current[target]
        moved[target] = current[at.index]
        return withKeys(layout, at.position, moved) to RailSelection(at.position, target)
    }

    fun replace(
        layout: LayoutDefinition,
        at: RailSelection,
        key: LayoutKey,
    ): LayoutDefinition {
        val current = keys(layout, at.position)
        if (at.index !in current.indices) return layout
        return withKeys(layout, at.position, current.mapIndexed { index, old -> if (index == at.index) key else old })
    }
}
