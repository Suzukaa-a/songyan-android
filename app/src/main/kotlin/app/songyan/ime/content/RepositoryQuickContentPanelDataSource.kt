package app.songyan.ime.content

import app.songyan.ime.ime.panels.QuickContentPanelContent
import app.songyan.ime.ime.panels.QuickContentPanelDataSource

/**
 * Bridges the keyboard panels to the app's [QuickContentRepository].
 *
 * Lives in the app module because that is where the concrete store paths are known; the keyboard
 * UI only sees the [QuickContentPanelDataSource] interface, so tools and tests never need the app
 * module to render a panel.
 */
class RepositoryQuickContentPanelDataSource(
    private val repository: QuickContentRepository,
    /** Told the stored switch after the panel changes it, so capture starts with the next copy. */
    private val onClipboardEnabledChanged: (Boolean) -> Unit = {},
) : QuickContentPanelDataSource {
    override suspend fun loadContent(): QuickContentPanelContent {
        val panels = repository.loadPanelsData()
        return QuickContentPanelContent(
            catalog = panels.symbols.catalog,
            recentSymbols = panels.symbols.recents,
            phrases = panels.phrases,
            clipboard = panels.clipboard,
            clipboardEnabled = panels.clipboardEnabled,
            builtinCatalog = !panels.symbols.customCatalog,
        )
    }

    override suspend fun recordRecentSymbol(text: String): List<String> = repository.recordRecentSymbol(text)

    override suspend fun deleteClipboardEntry(text: String) {
        repository.deleteClipboardEntry(text)
    }

    override suspend fun setClipboardEnabled(enabled: Boolean) {
        onClipboardEnabledChanged(repository.setClipboardEnabled(enabled).clipboardEnabled)
    }

    override suspend fun clearRecentSymbols() {
        repository.clearRecentSymbols()
    }

    override suspend fun loadRecentEmoji(): List<String> = repository.loadRecentEmoji()

    override suspend fun recordRecentEmoji(text: String) {
        repository.recordRecentEmoji(text)
    }
}
