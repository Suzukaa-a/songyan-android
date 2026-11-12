// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.scripttools

import app.songyan.ime.ime.tools.ToolCatalog
import app.songyan.ime.tools.ToolDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The keyboard's tool list, read ahead of the view build.
 *
 * The sources are not free: the script catalog reads its snapshot file (the extension catalog only
 * reads the in-memory registry). [tools] is called on the main thread while the toolbar is built, so
 * it must never touch the disk; [refresh] reads every source on [ioDispatcher] and swaps a volatile
 * snapshot, and the service calls it when the input view is created, the keyboard shows and the
 * input-method menu opens.
 *
 * Refreshes are serialized: two callers racing would otherwise let an older read land last and
 * briefly show a stale list.
 */
internal class PreloadedToolCatalog(
    private val sources: List<ToolCatalog>,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ToolCatalog {
    @Volatile
    private var snapshot: List<ToolDescriptor> = emptyList()
    private val refreshMutex = Mutex()

    override fun tools(): List<ToolDescriptor> = snapshot

    /**
     * Reads the sources off the caller's thread and swaps the snapshot. Returns true when the list
     * the toolbar shows changed, so callers can skip a redraw otherwise.
     */
    suspend fun refresh(): Boolean =
        refreshMutex.withLock {
            val next = withContext(ioDispatcher) { sources.flatMap { it.tools() } }
            val changed = next != snapshot
            snapshot = next
            changed
        }
}
