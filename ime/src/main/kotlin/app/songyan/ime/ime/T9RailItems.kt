package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.SpellingRailChoices

/** What one slot of the nine-key leading rail holds while a composition runs. */
internal enum class RailKind {
    /** The spelling already applied to the composition; not tappable, Delete undoes it. */
    LOCKED,

    /** The readings already chosen to narrow the candidates; not tappable, Delete undoes them. */
    PREFIX,

    /** A spelling the engine accepts for the tail segment. */
    LOCK,

    /** A proven reading of a loaded candidate. */
    READING,

    /** The emoji search's query with the spelling in progress; shown like a chosen reading, not tappable. */
    ECHO,
}

internal data class RailItem(
    val label: String,
    val kind: RailKind,
) {
    val selected: Boolean get() = kind == RailKind.LOCKED || kind == RailKind.PREFIX || kind == RailKind.ECHO
}

/**
 * The nine-key leading rail: three slots that replace the quick symbols while composing. Choices
 * come only from live engine reports and complete candidate readings ([SpellingRailChoices]); more
 * choices than slots scroll vertically.
 */
internal object T9RailPolicy {
    const val VISIBLE_SLOTS = 3

    /** Every item in display order: what is chosen first, then spelling locks, then readings. */
    fun items(choices: SpellingRailChoices): List<RailItem> =
        buildList {
            choices.lockedOption?.let { add(RailItem(it, RailKind.LOCKED)) }
            if (choices.isFiltering) add(RailItem(choices.readingPrefix.joinToString(" "), RailKind.PREFIX))
            // A lock in force replaces the spelling options: Delete undoes it first.
            if (!choices.canUndoLock) choices.lockOptions.forEach { add(RailItem(it, RailKind.LOCK)) }
            // The applied spelling is also a reading of the words it left; showing it twice adds nothing.
            choices.readings.filter { it != choices.lockedOption }.forEach { add(RailItem(it, RailKind.READING)) }
        }

    fun clampScroll(
        itemCount: Int,
        scroll: Int,
    ): Int = scroll.coerceIn(0, maxOf(0, itemCount - VISIBLE_SLOTS))

    /** The items the three slots show at [scroll]. */
    fun window(
        items: List<RailItem>,
        scroll: Int,
    ): List<RailItem> = items.drop(clampScroll(items.size, scroll)).take(VISIBLE_SLOTS)
}
