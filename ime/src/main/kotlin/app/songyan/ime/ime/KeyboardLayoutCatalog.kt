package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The user's layout catalog (layouts, page library and keyboard pairings) as the keyboard sees it.
 *
 * The app loads the catalog off the main thread and installs it here, exactly like the appearance:
 * the keyboard never reads layout storage on its show path. Until something is installed the bundled
 * layouts and pages apply, so a keyboard shown before the first load still types.
 */
object KeyboardLayoutCatalog {
    /** A catalog with its install counter; the counter tells caches the catalog changed. */
    data class Snapshot(
        val registry: LayoutRegistry,
        val revision: Long,
    )

    private val state = MutableStateFlow(Snapshot(LayoutRegistry.create(), 0L))

    val snapshot: StateFlow<Snapshot> = state.asStateFlow()

    fun install(registry: LayoutRegistry) {
        state.value = Snapshot(registry, state.value.revision + 1)
    }
}
