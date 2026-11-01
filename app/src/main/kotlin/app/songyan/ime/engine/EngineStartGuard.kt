package app.songyan.ime.engine

import android.util.Log
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceIds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Breaks a crash loop at engine start.
 *
 * The input method and settings share one process, so a native crash while the engine opens (a
 * corrupt userdb, a broken Lua script or grammar model in an imported scheme) would kill both on
 * every bind. Before each start a marker naming the generation is written durably; a start that
 * returns (or throws) clears it. A marker still present at the next start means that start never
 * finished. One unfinished start may be an ordinary process kill (memory pressure, a vendor's
 * background killer, a slow device taking long to start), so only the third start after two
 * unfinished ones in a row acts; where the system reports why the process last died (Android 11+,
 * [previousExit]) a death that was not a crash does not count at all:
 *
 * 1. the generation's learning data is moved aside into `rime/quarantine/` (never deleted);
 * 2. a generation of an imported source is not started again: the built-in source is asked for;
 *    the built-in generation is retried once on fresh learning data;
 * 3. a built-in generation that keeps failing after that is left alone ([Decision.Blocked]) until
 *    a new generation is published (the keyboard's retry rebuilds one).
 *
 * Every quarantine is recorded next to what it moved, for the settings diagnostics.
 * All methods do disk IO; call them off the main thread, serialized by the runtime.
 */
class EngineStartGuard(
    private val storage: AppStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Why this app's process last died after the given time; see [PreviousExit]. */
    private val previousExit: (sinceEpochMs: Long) -> PreviousExit = { PreviousExit.UNKNOWN },
) {
    /** What the system says about the last death of the process that left a marker behind. */
    enum class PreviousExit {
        CRASH,

        /** Killed or stopped (low memory, the user, the system); says nothing about the engine. */
        NOT_A_CRASH,

        /** Not reported (before Android 11, or no record): counted like a crash, but only from two. */
        UNKNOWN,
    }

    sealed interface Decision {
        /** Start the engine; [started] or [finished] must follow. */
        data object Start : Decision

        /** Do not start this generation; the built-in source must be made active instead. */
        data object SwitchToBuiltin : Decision

        /** Do not start this generation; nothing automatic is left to try. */
        data object Blocked : Decision
    }

    private val markerFile: File get() = File(storage.rimeRoot, MARKER_NAME)

    /** Decides whether [record]'s generation may start, recording the attempt durably when it may. */
    fun beforeStart(record: PublicationRecord): Decision {
        val found = readMarker()?.takeIf { it.generation == record.generation }
        if (found == null) {
            writeMarker(Marker(record.generation, unfinished = 1, quarantined = false))
            return Decision.Start
        }
        if (found.quarantined && record.sourceId != SourceIds.BUILTIN) return Decision.SwitchToBuiltin
        // The last unfinished start ended in a death the system knows was no crash: forget it.
        val marker =
            if (found.unfinished > 0 && previousExit(found.writtenAt) == PreviousExit.NOT_A_CRASH) {
                found.copy(unfinished = found.unfinished - 1)
            } else {
                found
            }
        if (marker.unfinished < UNFINISHED_LIMIT) {
            writeMarker(marker.copy(unfinished = marker.unfinished + 1))
            return Decision.Start
        }
        if (marker.quarantined) return Decision.Blocked
        val switching = record.sourceId != SourceIds.BUILTIN
        quarantine(record, switching)
        return if (switching) {
            writeMarker(Marker(record.generation, unfinished = 0, quarantined = true))
            Decision.SwitchToBuiltin
        } else {
            writeMarker(Marker(record.generation, unfinished = 1, quarantined = true))
            Decision.Start
        }
    }

    /** The engine is up: whatever crashed before is behind it. */
    fun started() {
        clear()
    }

    /** The start threw instead of crashing: the process survived, so it does not count. */
    fun finished() {
        val marker = readMarker() ?: return
        if (marker.quarantined) {
            writeMarker(marker.copy(unfinished = 0))
        } else {
            clear()
        }
    }

    /** Recorded quarantines, newest first. */
    fun quarantines(): List<QuarantineRecord> = Companion.quarantines(storage)

    private fun quarantine(
        record: PublicationRecord,
        switching: Boolean,
    ) {
        val time = clock()
        val namespace = LearningData.namespace(record.sourceId)
        val target = uniqueDirectory(File(storage.rimeRoot, QUARANTINE_DIR), time.toString())
        target.mkdirs()
        val learning = LearningData.directory(storage, record.sourceId)
        val moved =
            learning.exists() &&
                try {
                    Files.move(
                        learning.toPath(),
                        File(target, "user-$namespace").toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    true
                } catch (e: IOException) {
                    Log.e(TAG, "cannot move the learning data aside", e)
                    false
                }
        val entry =
            QuarantineRecord(
                timeEpochMs = time,
                generation = record.generation,
                sourceId = record.sourceId,
                movedLearningData = moved,
                switchedToBuiltin = switching,
            )
        writeDurably(File(target, RECORD_NAME), entry.toJson().toByteArray())
        syncDirectory(target.parentFile!!)
        Log.e(TAG, "engine start did not finish twice; quarantined into ${target.name}")
    }

    private fun uniqueDirectory(
        parent: File,
        base: String,
    ): File {
        var candidate = File(parent, base)
        var suffix = 1
        while (candidate.exists()) candidate = File(parent, "$base-${suffix++}")
        return candidate
    }

    private data class Marker(
        val generation: String,
        val unfinished: Int,
        val quarantined: Boolean,
        val writtenAt: Long = 0,
    )

    private fun readMarker(): Marker? =
        try {
            val json = Json.parseToJsonElement(markerFile.readText()).jsonObject
            Marker(
                generation = (json["generation"] as? JsonPrimitive)?.contentOrNull ?: return null,
                unfinished = (json["unfinished"] as? JsonPrimitive)?.intOrNull ?: 0,
                quarantined = (json["quarantined"] as? JsonPrimitive)?.booleanOrNull ?: false,
                writtenAt = (json["writtenAt"] as? JsonPrimitive)?.longOrNull ?: 0,
            )
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            // A marker torn by a crash is no evidence either way; the next write replaces it.
            null
        }

    private fun writeMarker(marker: Marker) {
        val json =
            buildJsonObject {
                put("generation", marker.generation)
                put("unfinished", marker.unfinished)
                put("quarantined", marker.quarantined)
                put("writtenAt", clock())
            }
        val temp = File(storage.rimeRoot, "$MARKER_NAME.tmp")
        writeDurably(temp, json.toString().toByteArray())
        Files.move(
            temp.toPath(),
            markerFile.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
        // The marker must be on disk before the engine opens: the crash it guards against does not
        // give the page cache a chance to flush.
        syncDirectory(storage.rimeRoot)
    }

    /**
     * Not synced: a deletion lost to a power cut leaves one unfinished start behind, which alone
     * never acts, and every engine start would otherwise wait for a directory sync.
     */
    private fun clear() {
        markerFile.delete()
    }

    companion object {
        private const val TAG = "EngineStartGuard"
        private const val MARKER_NAME = "engine-start.json"
        private const val RECORD_NAME = "quarantine.json"

        /** Directory under `rime/` holding what a quarantine moved aside. */
        const val QUARANTINE_DIR = "quarantine"

        /** Unfinished starts in a row that are put down to ordinary process kills. */
        const val UNFINISHED_LIMIT = 2

        /** Recorded quarantines in [storage], newest first; readable from any process. */
        fun quarantines(storage: AppStorage): List<QuarantineRecord> =
            File(storage.rimeRoot, QUARANTINE_DIR)
                .listFiles()
                .orEmpty()
                .mapNotNull { dir ->
                    runCatching { QuarantineRecord.fromJson(File(dir, RECORD_NAME).readText()) }.getOrNull()
                }.sortedByDescending { it.timeEpochMs }

        private fun writeDurably(
            file: File,
            data: ByteArray,
        ) {
            FileOutputStream(file).use { out ->
                out.write(data)
                out.flush()
                out.fd.sync()
            }
        }

        private fun syncDirectory(dir: File) {
            try {
                FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: IOException) {
                // Some file systems refuse to open a directory for sync; the file itself is synced.
            }
        }
    }
}

/** One quarantine: what was moved aside and whether the keyboard switched to the built-in source. */
data class QuarantineRecord(
    val timeEpochMs: Long,
    val generation: String,
    val sourceId: String,
    val movedLearningData: Boolean,
    val switchedToBuiltin: Boolean,
) {
    fun toJson(): String =
        buildJsonObject {
            put("time", timeEpochMs)
            put("generation", generation)
            put("sourceId", sourceId)
            put("movedLearningData", movedLearningData)
            put("switchedToBuiltin", switchedToBuiltin)
        }.toString()

    companion object {
        fun fromJson(text: String): QuarantineRecord? {
            val json: JsonObject = Json.parseToJsonElement(text).jsonObject

            fun string(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull

            fun flag(key: String) = (json[key] as? JsonPrimitive)?.booleanOrNull ?: false
            return QuarantineRecord(
                timeEpochMs = (json["time"] as? JsonPrimitive)?.longOrNull ?: return null,
                generation = string("generation") ?: return null,
                sourceId = string("sourceId") ?: return null,
                movedLearningData = flag("movedLearningData"),
                switchedToBuiltin = flag("switchedToBuiltin"),
            )
        }
    }
}
