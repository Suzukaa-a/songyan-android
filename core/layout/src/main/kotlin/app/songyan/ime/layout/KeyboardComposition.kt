package app.songyan.ime.layout

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The key a pairing is stored under: `"<inputMethodId>:<shape>"`, plus `"#<layoutId>"` for a keyboard
 * that names the layout it draws. The layout id may hold any character, so the key is cut at its
 * first "#"; the input method id may itself contain ":", so the shape is the part after the last one.
 *
 * A key without a layout id is the pairing of a whole shape, as every pairing was before keyboards
 * named their layout: its main layout is whatever that pairing says.
 */
data class KeyboardCompositionKey(
    val inputMethodId: String,
    val layout: LayoutInput.Builtin,
    val layoutId: String? = null,
) {
    val rawValue: String get() = if (layoutId == null) shapeRawValue else "$shapeRawValue#$layoutId"

    /** The key of the shape this keyboard shares its pages with until it saves its own. */
    val shapeRawValue: String get() = inputMethodId + ":" + layout.wire

    /** The same input method and shape without a named layout. */
    val shapeKey: KeyboardCompositionKey get() = if (layoutId == null) this else copy(layoutId = null)

    companion object {
        /** Null for a malformed key: no separator, an empty part or an unknown shape. */
        fun parse(rawValue: String): KeyboardCompositionKey? {
            val mark = rawValue.indexOf('#')
            val head = if (mark < 0) rawValue else rawValue.substring(0, mark)
            val layoutId = if (mark < 0) null else rawValue.substring(mark + 1)
            val separator = head.lastIndexOf(':')
            if (separator <= 0 || layoutId?.isEmpty() == true) return null
            val layout = LayoutInput.Builtin.fromWire(head.substring(separator + 1)) ?: return null
            return KeyboardCompositionKey(head.substring(0, separator), layout, layoutId)
        }
    }
}

/**
 * Which pages one keyboard pairing uses, indexed by the keyboard id (input method x layout
 * capability) the host stores in [LayoutRegistry].
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class KeyboardComposition(
    val mainLayoutID: String,
    /** The symbol page follows the configured presentation unless the user chose one. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val symbolPageID: String = KeyboardPagePresets.SYMBOLS_SYSTEM_ID,
    /** The 123 page follows the live keyboard (marks and keypad shape) unless the user chose one. */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val numericPageID: String = KeyboardPagePresets.NUMERIC_AUTOMATIC_ID,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS) val englishPageID: String = KeyboardPagePresets.ENGLISH_QWERTY_ID,
) {
    fun pageId(kind: KeyboardPageKind): String =
        when (kind) {
            KeyboardPageKind.SYMBOLS -> symbolPageID
            KeyboardPageKind.NUMERIC -> numericPageID
            KeyboardPageKind.ENGLISH -> englishPageID
        }

    fun withPageId(
        id: String,
        kind: KeyboardPageKind,
    ): KeyboardComposition =
        when (kind) {
            KeyboardPageKind.SYMBOLS -> copy(symbolPageID = id)
            KeyboardPageKind.NUMERIC -> copy(numericPageID = id)
            KeyboardPageKind.ENGLISH -> copy(englishPageID = id)
        }

    fun withMainLayoutId(id: String): KeyboardComposition = copy(mainLayoutID = id)
}
