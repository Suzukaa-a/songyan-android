package app.songyan.ime.userconfig

import java.io.File

/**
 * The temporary files the provider's writers fill. A writer that dies (or a process killed in the
 * middle of a write) leaves its file behind, so the first use of the provider after a start sweeps
 * every one no open writer owns.
 */
internal object WriteTemps {
    const val PREFIX = ".write-"

    /** Writers open at once, across every client. */
    const val MAX_OPEN = 8

    /** How long one writer may keep a file open. */
    const val MAX_OPEN_MS = 10L * 60 * 1000

    /**
     * Deletes the leftovers of the editor and of overrides below [root] (`.write-*`, `.copy-*`),
     * at least [olderThanMs] old: those are written and renamed within moments, so an old one
     * is a casualty. Returns how many went.
     */
    fun sweepTree(
        root: File,
        olderThanMs: Long,
        now: Long = System.currentTimeMillis(),
    ): Int {
        var removed = 0
        root.walkTopDown().maxDepth(MAX_DEPTH).forEach { file ->
            val stale = file.isFile && (file.name.startsWith(PREFIX) || file.name.startsWith(COPY_PREFIX))
            if (stale && now - file.lastModified() >= olderThanMs && file.delete()) removed++
        }
        return removed
    }

    private const val COPY_PREFIX = ".copy-"
    private const val MAX_DEPTH = 6

    /** Deletes the temporary files in [dir] except those named in [open]; returns how many went. */
    fun sweep(
        dir: File,
        open: Collection<String>,
    ): Int =
        dir
            .listFiles { file -> file.isFile && file.name.startsWith(PREFIX) && file.name !in open }
            .orEmpty()
            .count { it.delete() }
}
