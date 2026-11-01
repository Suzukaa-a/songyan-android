package app.songyan.ime.userconfig

import java.io.File

/**
 * The temporary files the provider's writers fill. A writer that dies (or a process killed in the
 * middle of a write) leaves its file behind, so the first use of the provider after a start sweeps
 * every one no open writer owns.
 */
internal object WriteTemps {
    const val PREFIX = ".write-"

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
