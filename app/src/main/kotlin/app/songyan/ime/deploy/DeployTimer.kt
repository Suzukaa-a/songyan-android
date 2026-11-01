package app.songyan.ime.deploy

import android.os.SystemClock
import android.util.Log

/**
 * Wall-clock time of the phases of one deployment, logged as one line when it ends, so the cost of
 * a slow deployment can be read from any device's log without a profiler. Holds no input content.
 */
internal class DeployTimer(
    private val what: String,
) {
    private val started = SystemClock.elapsedRealtime()
    private val phases = LinkedHashMap<String, Long>()

    inline fun <T> phase(
        name: String,
        block: () -> T,
    ): T {
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            add(name, SystemClock.elapsedRealtime() - start)
        }
    }

    fun add(
        name: String,
        millis: Long,
    ) {
        phases[name] = (phases[name] ?: 0L) + millis
    }

    fun log() {
        val total = SystemClock.elapsedRealtime() - started
        Log.i(TAG, what + " " + total + " ms: " + phases.entries.joinToString(", ") { it.key + " " + it.value })
    }

    companion object {
        const val TAG = "SongyanDeployTime"
    }
}
