package app.songyan.ime.layout

import app.songyan.ime.settings.state.LayoutEditResult
import app.songyan.ime.settings.state.LayoutImportOutcome
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * The stored layout catalog and every change to it, without any Android type so the rules are
 * unit-tested on the JVM. Reads and writes of the catalog file run on [io]; each stored catalog is
 * handed to [publish] (the keyboard) right after it is on disk.
 *
 * A catalog that cannot be read leaves the bundled layouts in place (fail closed) and is only
 * replaced when the user saves an edit.
 */
class LayoutCatalog(
    private val store: LayoutStore,
    private val io: CoroutineDispatcher,
    private val publish: (LayoutRegistry) -> Unit,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val mutex = Mutex()
    private val mutable = MutableStateFlow<LayoutRegistry?>(null)

    val registry: StateFlow<LayoutRegistry?> = mutable.asStateFlow()

    suspend fun load() =
        withContext(io) {
            mutex.withLock { publishState(runCatching { store.load() }.getOrElse { LayoutRegistry.create() }) }
        }

    suspend fun edit(change: (LayoutRegistry) -> LayoutRegistry): LayoutEditResult =
        withContext(io) {
            mutex.withLock {
                try {
                    val next = change(mutable.value ?: store.load())
                    store.save(next)
                    publishState(next)
                    LayoutEditResult.Saved
                } catch (error: LayoutException) {
                    LayoutEditResult.Rejected(error)
                }
            }
        }

    /**
     * Drops the pairings of input methods nothing on the device provides any more, and returns how
     * many went. Nothing is written when none is obsolete. Such a pairing is invisible in settings (the
     * pairing screen shows only keyboards that can be used) yet keeps its pages from being deleted.
     */
    suspend fun pruneCompositions(isObsoleteInputMethod: (String) -> Boolean): Int =
        withContext(io) {
            mutex.withLock {
                try {
                    val (next, removed) = (mutable.value ?: store.load()).withPrunedCompositions(isObsoleteInputMethod)
                    if (removed > 0) {
                        store.save(next)
                        publishState(next)
                    }
                    removed
                } catch (_: LayoutException) {
                    0
                }
            }
        }

    /** Adds the layout in [text]; an id that is already here is kept next to the existing one, never over it. */
    suspend fun import(text: String): LayoutImportOutcome =
        withContext(io) {
            mutex.withLock {
                try {
                    val definition = LayoutJson.decode(text)
                    val current = mutable.value ?: store.load()
                    val taken = current.layout(definition.id) != null
                    val incoming = if (taken) definition.copyWithId(newId()) else definition
                    val next = current.install(incoming.encoded())
                    store.save(next)
                    publishState(next)
                    LayoutImportOutcome.Imported(incoming.name, copied = taken)
                } catch (error: LayoutException) {
                    LayoutImportOutcome.Rejected(error)
                }
            }
        }

    /** The exported JSON of one layout (a bundled one included), or null when it does not exist. */
    suspend fun export(id: String): String? =
        withContext(io) {
            mutex.withLock { (mutable.value ?: store.load()).resolvedLayout(id)?.encoded() }
        }

    private fun publishState(registry: LayoutRegistry) {
        mutable.value = registry
        publish(registry)
    }
}
