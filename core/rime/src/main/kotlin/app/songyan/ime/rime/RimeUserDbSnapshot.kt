package app.songyan.ime.rime

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.text.Normalizer
import java.util.Locale

/** One librime `.userdb.txt` text snapshot of a user dictionary, named by its dictionary. */
class RimeLearningSnapshot(
    val name: String,
    val data: ByteArray,
)

/** The identity facts of a snapshot; the rows themselves are left to librime. */
data class RimeUserDbDocument(
    val name: String,
    val rimeVersion: String,
    val userId: String,
    val entryCount: Int,
)

/** Why the engine layer refused a learning backup or restore. */
class RimeLearningException(
    val kind: Kind,
    detail: String,
) : Exception(detail) {
    enum class Kind {
        /** A snapshot is malformed or outside the format's bounds; nothing was written. */
        INVALID_SNAPSHOT,

        /** A snapshot belongs to a dictionary the current input methods do not declare, or another librime. */
        IDENTITY_MISMATCH,

        /** The engine cannot do learning backup at all (no levers, unreadable schema config). */
        UNSUPPORTED,

        /** Exporting the learning data failed. */
        BACKUP_FAILED,

        /** A native merge write was attempted and did not finish; the user directory may be partly merged. */
        MERGE_UNVERIFIED,
    }
}

/**
 * The `.userdb.txt` checks of the iOS `RimeLearningFormat`, applied before any snapshot reaches
 * librime: header, unique metadata, `db_type` userdb, `db_name` equal to the dictionary name, and
 * the same bounds as the archive.
 */
object RimeUserDbSnapshot {
    const val MAXIMUM_COUNT = 32
    const val MAXIMUM_BYTES = 4 * 1024 * 1024
    const val MAXIMUM_TOTAL_BYTES = 8 * 1024 * 1024
    private const val HEADER = "# Rime user dictionary"
    private const val MAXIMUM_METADATA_BYTES = 256

    fun validateName(name: String) {
        val control =
            name.codePoints().anyMatch {
                Character.isISOControl(it) ||
                    Character.getType(it) == Character.FORMAT.toInt()
            }
        if (name.isEmpty() ||
            name.toByteArray(Charsets.UTF_8).size > 256 ||
            name.startsWith(".") ||
            '/' in name ||
            '\\' in name ||
            ".userdb" in name ||
            control ||
            name != name.trim { it.isWhitespace() }
        ) {
            throw invalid("dictionary name refused")
        }
    }

    fun parseBatch(snapshots: List<RimeLearningSnapshot>): List<RimeUserDbDocument> {
        val keys = snapshots.map { Normalizer.normalize(it.name, Normalizer.Form.NFC).lowercase(Locale.ROOT) }
        if (snapshots.isEmpty() ||
            snapshots.size > MAXIMUM_COUNT ||
            snapshots.sumOf { it.data.size.toLong() } > MAXIMUM_TOTAL_BYTES ||
            keys.toSet().size != keys.size
        ) {
            throw invalid("snapshot batch outside its bounds")
        }
        return snapshots.map(::parse)
    }

    fun parse(snapshot: RimeLearningSnapshot): RimeUserDbDocument {
        validateName(snapshot.name)
        if (snapshot.data.isEmpty() || snapshot.data.size > MAXIMUM_BYTES) throw invalid("snapshot size")
        val text = utf8(snapshot.data) ?: throw invalid("snapshot is not UTF-8")
        val lines = text.split('\n')
        if (lines.first() != HEADER) throw invalid("not a Rime user dictionary snapshot")
        val metadata = HashMap<String, String>()
        var entries = 0
        for (line in lines.drop(1)) {
            if (line.startsWith("#@")) {
                val pair = line.substring(2).split('\t', limit = 2)
                if (pair.size != 2 ||
                    pair[1].isEmpty() ||
                    pair[1].toByteArray(Charsets.UTF_8).size > MAXIMUM_METADATA_BYTES ||
                    metadata.put(pair[0], pair[1]) != null
                ) {
                    throw invalid("snapshot metadata")
                }
            } else if (line.isNotEmpty() && !line.startsWith("#")) {
                entries++
            }
        }
        val name = metadata["/db_name"]
        val version = metadata["/rime_version"]
        val userId = metadata["/user_id"]
        if (metadata["/db_type"] != "userdb" || name != snapshot.name || version == null || userId == null) {
            throw RimeLearningException(RimeLearningException.Kind.IDENTITY_MISMATCH, "snapshot identity")
        }
        return RimeUserDbDocument(name, version, userId, entries)
    }

    private fun utf8(bytes: ByteArray): String? =
        try {
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }

    private fun invalid(detail: String) = RimeLearningException(RimeLearningException.Kind.INVALID_SNAPSHOT, detail)
}
