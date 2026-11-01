package app.songyan.ime.layout

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/**
 * The key a pairing is stored under: `"<inputMethodId>:<builtin layout>"`. The input method id may
 * itself contain ":", so the layout is the part after the last one.
 */
data class KeyboardCompositionKey(
    val inputMethodId: String,
    val layout: LayoutInput.Builtin,
) {
    val rawValue: String get() = inputMethodId + ":" + layout.wire

    companion object {
        /** Null for a malformed key: no separator, an empty input method or an unknown layout. */
        fun parse(rawValue: String): KeyboardCompositionKey? {
            val separator = rawValue.lastIndexOf(':')
            if (separator <= 0) return null
            val layout = LayoutInput.Builtin.fromWire(rawValue.substring(separator + 1)) ?: return null
            return KeyboardCompositionKey(rawValue.substring(0, separator), layout)
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
