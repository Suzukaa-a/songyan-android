package app.songyan.ime.ime.panels

import app.songyan.ime.content.CommonPhrase
import app.songyan.ime.content.SymbolCatalog

/** Everything the three quick-content panels render, loaded in one pass. */
data class QuickContentPanelContent(
    val catalog: SymbolCatalog,
    val recentSymbols: List<String>,
    val phrases: List<CommonPhrase>,
    val clipboard: List<String>,
    val clipboardEnabled: Boolean,
)

/**
 * Where the panels read and write their content.
 *
 * Defined here, in the keyboard UI, and implemented by the app process' repository, so the views
 * and their controller never depend on the app module. All methods suspend: the implementation
 * moves file work onto its own IO dispatcher and never blocks the panel.
 */
interface QuickContentPanelDataSource {
    suspend fun loadContent(): QuickContentPanelContent

    /** A symbol was inserted; the recent list moves it to the front. */
    suspend fun recordRecentSymbol(text: String)

    suspend fun deleteClipboardEntry(text: String)

    /** Turns clipboard history on from the panel, as the switch in settings would. */
    suspend fun setClipboardEnabled(enabled: Boolean) = Unit

    suspend fun clearRecentSymbols()

    /** The recently inserted emoji, newest first (the exact forms, skin tone included). */
    suspend fun loadRecentEmoji(): List<String> = emptyList()

    /** An emoji was inserted; the recent list moves it to the front. */
    suspend fun recordRecentEmoji(text: String) = Unit
}
