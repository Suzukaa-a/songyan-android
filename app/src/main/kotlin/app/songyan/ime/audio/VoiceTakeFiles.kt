package app.songyan.ime.audio

import java.io.File

/**
 * The small scratch directory a voice take is written to before its bytes go to the script.
 *
 * The take has to reach disk: the script host hands the audio to the isolated script process as a
 * memfd descriptor (a Binder transaction cannot carry a minute of PCM), and fills that descriptor
 * from the file. The file is deleted as soon as the host has read it; a process killed in between
 * would leave one behind, so every new take first prunes the directory. Nothing else is touched.
 */
internal object VoiceTakeFiles {
    /** Subdirectory of the app cache that holds the takes. */
    const val DIRECTORY = "voice"

    const val PREFIX = "take-"
    const val SUFFIX = ".wav"

    /** Where a take called [name] lives; the file is created by the recorder's own thread. */
    fun takeFile(
        directory: File,
        name: String,
    ): File = File(directory, PREFIX + name + SUFFIX)

    /** Deletes takes an earlier, killed process left behind; returns how many files went. */
    fun prune(directory: File): Int {
        val files = directory.listFiles() ?: return 0
        var removed = 0
        for (file in files) {
            if (!file.isFile) continue
            if (!file.name.startsWith(PREFIX) || !file.name.endsWith(SUFFIX)) continue
            if (file.delete()) removed += 1
        }
        return removed
    }
}
