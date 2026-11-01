package app.songyan.ime.layout

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One category of a categorized symbol page. Recent categories retain authored keys so switching
 * back to fixed content is reversible.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class KeyboardPageCategory(
    val id: String,
    val name: String,
    /** Columns in the category grid; the keys flow into rows of this width. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val columns: Int = 4,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val keys: List<LayoutKey> = emptyList(),
    val split: LayoutSplitConfiguration? = null,
    val source: Source? = null,
) {
    @Serializable
    enum class Source {
        @SerialName("fixed")
        FIXED,

        @SerialName("recent")
        RECENT,
    }

    /** Declared keys laid out in rows of [columns], a partial final row included. */
    val rows: List<LayoutRow>
        get() {
            if (columns <= 0) return emptyList()
            return keys.chunked(columns).map { LayoutRow(it) }
        }

    fun withName(name: String): KeyboardPageCategory = copy(name = name)

    fun withKeys(keys: List<LayoutKey>): KeyboardPageCategory = copy(keys = keys)

    fun withSource(source: Source?): KeyboardPageCategory = copy(source = source)

    fun withSplit(split: LayoutSplitConfiguration?): KeyboardPageCategory = copy(split = split)

    @Throws(LayoutException::class)
    fun validate() {
        if (id.isBlank() ||
            id.encodeToByteArray().size > 256 ||
            name.isBlank() ||
            name.encodeToByteArray().size > 256
        ) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_CATEGORY_ID_AND_NAME_REQUIRED)
        }
        if (columns < 1 || columns > LayoutResourceBudget.CATEGORY_COLUMNS) {
            throw LayoutException.Invalid(
                LayoutInvalidCode.PAGE_CATEGORY_COLUMNS_OUT_OF_RANGE,
                "columns=" + columns,
            )
        }
        if (source != Source.RECENT && keys.isEmpty()) {
            throw LayoutException.Invalid(LayoutInvalidCode.PAGE_CATEGORY_KEYS_REQUIRED)
        }
        LayoutResourceBudget.checkBudget(keys.size <= LayoutResourceBudget.CATEGORY_KEYS) { "categoryKeys" }
        for (key in keys) KeyboardPageDefinition.validateKey(key)
        // Recent content changes independently of the fixed-key backup, so its grid is sized by the
        // recent allowance instead of the stored key count.
        val rowCount =
            if (source == Source.RECENT) {
                (LayoutResourceBudget.RECENT_CATEGORY_KEYS + columns - 1) / columns
            } else {
                rows.size
            }
        // A partial final row keeps the full grid's split boundary.
        split?.validate(List(rowCount) { columns })
    }
}
