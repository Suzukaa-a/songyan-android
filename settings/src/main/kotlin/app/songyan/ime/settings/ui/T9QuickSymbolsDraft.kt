package app.songyan.ime.settings.ui

import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.T9QuickSymbolStore

/** Why the quick-symbol list as typed cannot be saved; the screen maps each to copy. */
enum class T9QuickSymbolsProblem {
    /** A nonempty order is too long or holds a space, control or non-BMP character. */
    INVALID,
    DUPLICATE,
}

/** The quick-symbol rules and the keyboard preview, pure so the screen and its tests share them. */
object T9QuickSymbolsDraft {
    /** The first reason [symbols] cannot be saved, or null when it can. */
    fun check(symbols: String): T9QuickSymbolsProblem? =
        when {
            symbols.isNotEmpty() && !T9QuickSymbolStore.isValid(symbols) -> T9QuickSymbolsProblem.INVALID
            symbols.toSet().size != symbols.length -> T9QuickSymbolsProblem.DUPLICATE
            else -> null
        }

    /** The keys the nine-key keyboard draws for [symbols]: the first three on the side rail, the rest on the first key. */
    fun preview(symbols: String): Pair<List<String>, List<String>> {
        val values = BuiltinLayouts.t9QuickSymbolValues(symbols, ascii = false)
        return values.take(RAIL_SLOTS) to values.drop(RAIL_SLOTS)
    }

    private const val RAIL_SLOTS = 3
}
