package app.songyan.ime.script

import android.content.Context
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Reads the script catalog without starting anything: no Binder, no proxy process, no engine, no
 * script source. The proxy process refreshes the snapshot whenever the catalog changes (add,
 * confirm, enable, reorder, delete), so a cold IME process sees the same list the panel will.
 *
 * The snapshot lives beside the store's own files (same UID, app-internal storage):
 *
 *   script/catalog-snapshot.json    entries in panel order, enabled first is NOT applied here
 *
 * Reading is cheap enough for the keyboard process: one small file, parsed once per process and
 * reloaded only when its modification time moves.
 */
class ScriptCatalogCache(
    context: Context,
) {
    private val appContext = context.applicationContext

    // Resolved on first use: constructing the cache happens on the main thread when the keyboard
    // view is built, and getFilesDir creates the private directory on first call.
    private val snapshotFile by lazy { File(appContext.filesDir, "script/catalog-snapshot.json") }

    // Written by the keyboard's IO preload, read by the main-thread voice list.
    @Volatile
    private var cachedAt: Long = -1

    @Volatile
    private var cached: List<ScriptEntry> = emptyList()

    /** The catalog as of the last proxy write; empty when nothing is installed yet. */
    fun entries(): List<ScriptEntry> {
        val modified = snapshotFile.lastModified()
        if (modified <= 0) {
            cached = emptyList()
            cachedAt = modified
            return cached
        }
        if (modified != cachedAt) {
            cached = runCatching { ScriptProxyProto.parseSnapshot(readSnapshot()) }.getOrDefault(emptyList())
            cachedAt = modified
        }
        return cached
    }

    /** Enabled scripts in panel order - what the keyboard toolbar needs. */
    fun enabledEntries(): List<ScriptEntry> = entries().filter { it.enabled && !it.confirmationPending }

    /**
     * The last parsed list without touching the disk. The keyboard preloads [entries] on IO when
     * it shows, so callers on the main thread (the voice-input list) read from here.
     */
    fun cachedEntries(): List<ScriptEntry> = cached

    private fun readSnapshot(): String = snapshotFile.readText()
}

/** Writes the snapshot from the proxy side after every catalog mutation. */
internal object ScriptCatalogSnapshotWriter {
    fun write(
        root: File,
        entries: List<ScriptEntry>,
    ) {
        val file = File(root, "catalog-snapshot.json")
        file.parentFile?.mkdirs()
        file.writeText(ScriptProxyProto.snapshot(entries))
    }

    private val json = Json { prettyPrint = false }
}
