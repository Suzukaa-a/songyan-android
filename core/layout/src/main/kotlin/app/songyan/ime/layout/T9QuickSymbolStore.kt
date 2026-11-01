package app.songyan.ime.layout

import kotlinx.serialization.Serializable

/**
 * Machine-readable reasons a quick-symbol edit is rejected.
 */
enum class T9QuickSymbolError {
    INVALID_SYMBOLS,
    INVALID_SLOT,
    MALFORMED_FILE,
}

/** Raised by [T9QuickSymbolStore] edits and reads. */
class T9QuickSymbolException(
    val error: T9QuickSymbolError,
) : Exception("t9 quick symbols: " + error.name)

/**
 * Value type for the user's nine-key quick symbol slots.
 *
 * A missing file is the untouched default; nothing is written until the user edits. File IO lives in
 * [T9QuickSymbolStore], never here, so the model stays usable from a pure JVM test.
 */
@Serializable
data class T9QuickSymbolSnapshot(
    val version: Int = 1,
    val symbols: String = T9QuickSymbolStore.DEFAULT_SYMBOLS,
) {
    /** The slot characters in order. */
    val characters: List<Char> get() = symbols.toList()
}

object T9QuickSymbolStore {
    /** The default quick symbol sequence. */
    const val DEFAULT_SYMBOLS: String = "，。？！、…"

    /** Grapheme count does not bound Unicode storage; bound the whole JSON too. */
    const val MAXIMUM_FILE_BYTES: Int = 16 * 1024

    /** The largest number of slots a quick symbol list may hold. */
    const val MAXIMUM_SLOTS: Int = 12

    /**
     * A valid list is nonempty, within the slot cap, and free of whitespace/control characters.
     *
     * A slot is exactly one drawable BMP character, which is what the rail draws and what
     * [replaceSlotResult] can address: surrogates are rejected so a saved list can never hold half an
     * astral scalar.
     */
    fun isValid(symbols: String): Boolean {
        if (symbols.isEmpty()) return false
        if (symbols.encodeToByteArray().size > MAXIMUM_FILE_BYTES) return false
        if (symbols.codePointCount(0, symbols.length) > MAXIMUM_SLOTS) return false
        return symbols.none { it.isWhitespace() || it.isISOControl() || it.isSurrogate() }
    }

    /**
     * An explicit whole-list edit replaces the latest saved list. Duplicates are rejected rather than
     * silently changing the requested order.
     */
    @Throws(T9QuickSymbolException::class)
    fun replaceSymbolsResult(
        current: T9QuickSymbolSnapshot?,
        symbols: String,
    ): Pair<T9QuickSymbolSnapshot, Boolean> {
        if (!isValid(symbols) || symbols.toSet().size != symbols.length) {
            throw T9QuickSymbolException(T9QuickSymbolError.INVALID_SYMBOLS)
        }
        if (current != null && current.symbols == symbols) return current to false
        return T9QuickSymbolSnapshot(symbols = symbols) to true
    }

    /**
     * Replaces one slot in the latest value, never the caller's stale list. Selecting the displayed
     * default explicitly still persists it; re-selecting an already saved value writes nothing.
     */
    @Throws(T9QuickSymbolException::class)
    fun replaceSlotResult(
        current: T9QuickSymbolSnapshot?,
        index: Int,
        symbol: String,
    ): Pair<T9QuickSymbolSnapshot, Boolean> {
        if (symbol.length != 1 || !isValid(symbol)) {
            throw T9QuickSymbolException(T9QuickSymbolError.INVALID_SYMBOLS)
        }
        val existing = current ?: T9QuickSymbolSnapshot()
        val values = existing.symbols.toMutableList()
        if (index !in values.indices) throw T9QuickSymbolException(T9QuickSymbolError.INVALID_SLOT)
        values[index] = symbol[0]
        val updated = values.joinToString("")
        // Validate the resulting list with the whole-list rules: a slot edit must never create the
        // duplicate or unrenderable list that the list edit rejects.
        if (!isValid(updated) || updated.toSet().size != updated.length) {
            throw T9QuickSymbolException(T9QuickSymbolError.INVALID_SYMBOLS)
        }
        if (current != null && existing.symbols == updated) return existing to false
        return T9QuickSymbolSnapshot(symbols = updated) to true
    }
}
