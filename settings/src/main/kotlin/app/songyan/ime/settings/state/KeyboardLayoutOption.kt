package app.songyan.ime.settings.state

import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.settings.R

/**
 * The built-in keyboard layouts in display order. [available] mirrors what the keyboard can render
 * today, so the list never offers a choice that would do nothing.
 */
enum class KeyboardLayoutOption(
    val id: String,
    val nameRes: Int,
    val available: Boolean,
) {
    QWERTY26("qwerty26", R.string.layout_name_26, true),
    MERGED14("merged14", R.string.layout_name_14, true),
    MERGED18("merged18", R.string.layout_name_18, true),
    PHONE9("phone9", R.string.layout_name_9, true),
    ;

    /**
     * Relative key widths per row, read from the real layout so the thumbnail can never drift from
     * the keyboard; empty when the layout is unknown.
     */
    val thumbnailRows: List<List<Double>>
        get() =
            BuiltinLayouts
                .byId(id)
                ?.rows
                ?.map { row -> row.keys.map { it.widthWeight } }
                .orEmpty()

    /** Supporting note for the row; null when the layout needs no explanation. */
    fun noteRes(available: Boolean): Int? = if (available) null else R.string.layout_unavailable_note

    /**
     * Whether the input method in use can switch to this shape. A null set means no scheme facts
     * are known yet, and the static capability answers as before.
     */
    fun availableIn(supportedLayouts: Set<String>?): Boolean = supportedLayouts?.contains(id) ?: available

    companion object {
        /** Resolves a persisted layout id; unknown ids fall back to the default layout. */
        fun fromId(id: String?): KeyboardLayoutOption = entries.firstOrNull { it.id == id } ?: QWERTY26
    }
}
