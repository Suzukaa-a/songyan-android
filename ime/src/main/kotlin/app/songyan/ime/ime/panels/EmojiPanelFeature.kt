package app.songyan.ime.ime.panels

import android.content.Context
import app.songyan.ime.content.EmojiCatalog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Owns emoji loading, recents and search hand-over; editor actions arrive as injected callbacks. */
internal class EmojiPanelFeature(
    private val context: Context,
    private val host: PanelFeatureHost,
    private val scope: CoroutineScope,
    private val dataSource: QuickContentPanelDataSource,
    private val insert: (String) -> Unit,
    private val deleteBackward: () -> Unit,
    private val startSearch: () -> Unit,
    private val metadataVersion: () -> Int,
) {
    private val source = EmojiSource(context)

    private val panel: Lazy<EmojiPanelView> =
        host.lazyPanel {
            EmojiPanelView(context).apply {
                onInsert = { text ->
                    insert(text)
                    scope.launch {
                        dataSource.recordRecentEmoji(text)
                        refreshRecents()
                    }
                }
                onSearch = {
                    host.close()
                    startSearch()
                }
                onBackspace = { deleteBackward() }
            }
        }

    /** Opens the panel and fills it once the catalog and the recents are read. */
    fun open() {
        val view = panel.value
        view.reset()
        host.show(view)
        scope.launch {
            val catalog = source.catalog(metadataVersion())
            val recents = dataSource.loadRecentEmoji()
            // The panel may have been closed or replaced while the catalog loaded.
            if (host.isVisible(view)) {
                view.lookup(catalog::find)
                view.update(catalog.categories, recents)
            }
        }
    }

    /** Back to the category grid; a closed panel never reopens on a variants page. */
    fun reset() {
        panel.initializedValue?.reset()
    }

    /** The catalog for the editor's renderer, for the keyboard's own emoji search. */
    suspend fun catalog(): EmojiCatalog = source.catalog(metadataVersion())

    /** Releases the catalog unless the open panel shows it; reopening loads it again. */
    fun trim(visible: QuickContentPanelView?) {
        val view = panel.initializedValue
        if (view == null || visible !== view) source.release()
    }

    /** The recent list changed after an insert; the rows are loaded again when the panel opens. */
    private suspend fun refreshRecents() {
        val recents = dataSource.loadRecentEmoji()
        val view = panel.initializedValue ?: return
        if (host.isVisible(view)) view.updateRecents(recents)
    }
}
