package app.songyan.ime.deploy

import java.io.File
import java.io.IOException

/**
 * Counts the runs of automatic deploy work that never finished, across process deaths.
 *
 * Opening the keyboard or the settings, and the deploy job, start the work a generation still lacks
 * (its remaining layouts, a missing built-in build). When that work takes the :deploy process down
 * (the low-memory killer, a native crash) nothing writes its end, and the next start would run it
 * again, forever. A run of the same work that has started [limit] times without ending is refused
 * instead, so it fails once, visibly, rather than looping.
 *
 * The file holds the one piece of work in flight (the worker runs one at a time). Any other work
 * replaces it, and a new generation gives the same mode a new key, so a refusal never outlives the
 * state that caused it.
 */
internal class DeployAttempts(
    private val file: File,
    private val limit: Int = LIMIT,
) {
    /**
     * Records that [key] starts; false when it already started [limit] times without ending, and
     * must not run again.
     */
    fun begin(key: String): Boolean {
        val (current, count) = read() ?: (null to 0)
        val started = if (current == key) count + 1 else 1
        if (started > limit) return false
        write(key, started)
        return true
    }

    /** [key] ended, whatever its outcome: the process lived to say so. */
    fun end(key: String) {
        if (read()?.first == key) file.delete()
    }

    /** Whether [key] has used up its starts, so starting it again would only be refused. */
    fun exhausted(key: String): Boolean = read()?.let { it.first == key && it.second >= limit } == true

    private fun read(): Pair<String, Int>? =
        try {
            if (!file.isFile || file.length() > MAX_BYTES) {
                null
            } else {
                val lines = file.readText().split('\n')
                lines.getOrNull(1)?.toIntOrNull()?.let { lines[0] to it }
            }
        } catch (_: IOException) {
            null
        }

    private fun write(
        key: String,
        started: Int,
    ) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(key + "\n" + started)
        if (!temp.renameTo(file)) {
            temp.copyTo(file, overwrite = true)
            temp.delete()
        }
    }

    companion object {
        const val FILE_NAME = "deploy-attempts"
        const val LIMIT = 3
        private const val MAX_BYTES = 4096L

        /**
         * The key of [request] against the generation in use, or null for work the user asked for
         * (an import, a switch, a retry): it is never repeated without them.
         */
        fun keyOf(
            request: DeployRequest,
            activeGeneration: String?,
        ): String? = keyOf(request.mode, request.force, activeGeneration)

        fun keyOf(
            mode: DeployMode,
            force: Boolean,
            activeGeneration: String?,
        ): String? =
            when (mode) {
                DeployMode.BUILTIN, DeployMode.COMPLETE_COMPANIONS ->
                    mode.wireName + "|" + force + "|" + activeGeneration.orEmpty()
                else -> null
            }
    }
}
