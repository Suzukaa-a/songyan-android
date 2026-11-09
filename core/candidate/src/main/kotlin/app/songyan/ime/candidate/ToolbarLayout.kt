package app.songyan.ime.candidate

import kotlin.math.floor

/** The toolbar entries the user can turn on and off and reorder; [wire] is the stored name. */
enum class ToolbarItem(
    val wire: String,
) {
    CLIPBOARD("clipboard"),
    INPUT_METHODS("input_methods"),
    EMOJI("emoji"),
    EDIT("edit"),
    ONE_HAND("one_hand"),
    SETTINGS("settings"),
}

/**
 * The user's choice for the idle toolbar: every [ToolbarItem] once, in order, each shown or not. Off
 * items keep their place so turning one back on does not lose where it was.
 */
data class ToolbarLayout(
    val entries: List<Entry>,
) {
    data class Entry(
        val item: ToolbarItem,
        val shown: Boolean,
    )

    init {
        require(entries.map { it.item }.toSet().size == entries.size && entries.size == ToolbarItem.entries.size) {
            "a toolbar layout lists every item exactly once"
        }
    }

    /** The shown items in order. */
    val shown: List<ToolbarItem> get() = entries.filter { it.shown }.map { it.item }

    val isDefault: Boolean get() = this == DEFAULT

    fun withShown(
        item: ToolbarItem,
        shown: Boolean,
    ): ToolbarLayout = ToolbarLayout(entries.map { if (it.item == item) it.copy(shown = shown) else it })

    /** Moves [item] one place toward the front; among all entries, so an off item can be reordered too. */
    fun moveEarlier(item: ToolbarItem): ToolbarLayout = move(item, -1)

    fun moveLater(item: ToolbarItem): ToolbarLayout = move(item, 1)

    fun canMoveEarlier(item: ToolbarItem): Boolean = entries.indexOfFirst { it.item == item } > 0

    fun canMoveLater(item: ToolbarItem): Boolean = entries.indexOfFirst { it.item == item } in 0 until entries.lastIndex

    private fun move(
        item: ToolbarItem,
        delta: Int,
    ): ToolbarLayout {
        val from = entries.indexOfFirst { it.item == item }
        val to = from + delta
        if (from < 0 || to !in entries.indices) return this
        val moved = entries.toMutableList()
        moved[from] = entries[to]
        moved[to] = entries[from]
        return ToolbarLayout(moved)
    }

    /** `edit,-emoji,...`: names in order, a leading `-` marks an item that is off. */
    fun toWire(): String = entries.joinToString(",") { (if (it.shown) "" else "-") + it.item.wire }

    companion object {
        /** Today's idle toolbar, nothing turned off. */
        val DEFAULT: ToolbarLayout =
            ToolbarLayout(
                listOf(
                    ToolbarItem.CLIPBOARD,
                    ToolbarItem.INPUT_METHODS,
                    ToolbarItem.EMOJI,
                    ToolbarItem.EDIT,
                    ToolbarItem.ONE_HAND,
                    ToolbarItem.SETTINGS,
                ).map { Entry(it, shown = true) },
            )

        /**
         * Reads a stored value. Nothing stored gives [DEFAULT]; unknown or repeated names are skipped
         * and items the value does not mention are appended on, so an older or damaged value never
         * hides a button for good.
         */
        fun parse(wire: String?): ToolbarLayout {
            if (wire.isNullOrBlank()) return DEFAULT
            val seen = LinkedHashMap<ToolbarItem, Boolean>()
            for (token in wire.split(',')) {
                val name = token.trim()
                val off = name.startsWith("-")
                val item = ToolbarItem.entries.firstOrNull { it.wire == name.removePrefix("-") } ?: continue
                if (item !in seen) seen[item] = !off
            }
            if (seen.isEmpty()) return DEFAULT
            for (item in DEFAULT.entries) if (item.item !in seen) seen[item.item] = true
            return ToolbarLayout(seen.map { Entry(it.key, it.value) })
        }
    }
}

/**
 * How many toolbar entries fit a width. The keyboard and the settings screen both call this, so the
 * count the settings shows is the one the keyboard enforces.
 */
object ToolbarCapacity {
    /** The smallest slot an icon entry keeps: a comfortable touch target, so a full toolbar drops entries instead of cramming them. */
    const val MIN_ICON_SLOT_DP: Float = 44f

    /** The collapse button is always drawn and always last. */
    private const val RESERVED_SLOTS = 1

    /** The most chosen icon entries a toolbar [widthDp] wide shows when nothing else takes room. */
    fun limit(widthDp: Float): Int = (floor(widthDp / MIN_ICON_SLOT_DP).toInt() - RESERVED_SLOTS).coerceAtLeast(0)

    /** One drawn entry: the least room it needs, and whether overflow may drop it. */
    data class Slot(
        val minDp: Float,
        val droppable: Boolean,
    )

    /**
     * Which of [slots] stay: while they need more than [widthDp], the last droppable one goes, so
     * the front of the user's order wins. Entries that cannot be dropped always stay.
     */
    fun keep(
        widthDp: Float,
        slots: List<Slot>,
    ): List<Boolean> {
        val kept = MutableList(slots.size) { true }
        if (widthDp <= 0f) return kept
        var need = slots.sumOf { it.minDp.toDouble() }
        var index = slots.lastIndex
        while (need > widthDp && index >= 0) {
            if (slots[index].droppable) {
                kept[index] = false
                need -= slots[index].minDp
            }
            index--
        }
        return kept
    }
}
