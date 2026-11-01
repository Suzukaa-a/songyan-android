package app.songyan.ime.layout

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The authored function area of a page: the footer row and the two side rails.
 *
 * A null [KeyboardPageDefinition.chrome] keeps the legacy synthesis, so an old catalog renders as
 * before. A non-nil chrome is fully authored data: the host draws only these keys and never invents
 * a Shift, Delete, footer or rail the user deleted or never added.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class KeyboardPageChrome(
    /** Footer row under the key area. A key's width is a relative width. Written even when empty: iOS needs it. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val bottom: List<LayoutKey> = emptyList(),
    /** Left rail. A key's width is a relative vertical span. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val leading: List<LayoutKey> = emptyList(),
    /** Right rail. A key's width is a relative vertical span. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val trailing: List<LayoutKey> = emptyList(),
    /** The trailing rail also covers the footer row (the phone shell's full-height editing column). */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val trailingSpansBottom: Boolean = false,
    /** Rail width in dp. Null keeps the host's existing default for the surface. */
    val leadingWidth: Double? = null,
    val trailingWidth: Double? = null,
) {
    @Serializable
    enum class Region {
        @SerialName("bottom")
        BOTTOM,

        @SerialName("leading")
        LEADING,

        @SerialName("trailing")
        TRAILING,
    }

    fun keys(region: Region): List<LayoutKey> =
        when (region) {
            Region.BOTTOM -> bottom
            Region.LEADING -> leading
            Region.TRAILING -> trailing
        }

    /**
     * Replaces one key; an out-of-range region index returns null instead of silently doing
     * nothing, so an editor can reject the move.
     */
    fun replacingKey(
        region: Region,
        index: Int,
        key: LayoutKey,
    ): KeyboardPageChrome? {
        val current = keys(region)
        if (index !in current.indices) return null
        val updated = current.toMutableList()
        updated[index] = key
        return when (region) {
            Region.BOTTOM -> copy(bottom = updated)
            Region.LEADING -> copy(leading = updated)
            Region.TRAILING -> copy(trailing = updated)
        }
    }

    val allKeys: List<LayoutKey> get() = bottom + leading + trailing

    /** Rail span weights, or null when the rail is empty (the footer has no rail weights). */
    fun railWeights(region: Region): List<Double>? {
        if (region == Region.BOTTOM) return null
        val keys = keys(region)
        if (keys.isEmpty()) return null
        return keys.map { it.widthWeight }
    }

    @Throws(LayoutException::class)
    fun validate() {
        for (region in Region.entries) {
            val keys = keys(region)
            LayoutResourceBudget.checkBudget(keys.size <= LayoutResourceBudget.KEYS_PER_REGION) {
                "chromeKeys:" + region.name
            }
            for (key in keys) KeyboardPageDefinition.validateKey(key)
        }
        for (width in listOfNotNull(leadingWidth, trailingWidth)) {
            if (!width.isFinite() || width <= 0.0) {
                throw LayoutException.Invalid(LayoutInvalidCode.PAGE_RAIL_WIDTH_MUST_BE_POSITIVE)
            }
        }
    }
}
