package app.songyan.ime.rime

import java.io.File
import java.io.IOException
import java.nio.charset.CodingErrorAction

/**
 * A librime `.userdb.txt` snapshot on disk, as any Rime front-end writes it (`sync_user_data`,
 * `backup_user_dict`): Trime, Weasel, Squirrel, fcitx5-rime, Hamster or Songyan itself.
 */
class RimeSnapshotFile(
    /** The user dictionary it merges into: its `/db_name`, as librime's restore reads it. */
    val dictionary: String,
    val file: File,
    /** Rows librime will read as entries; zero means there is nothing to merge. */
    val entryCount: Int,
)

/**
 * Reads the header of a `.userdb.txt` from another front-end before librime's restore sees it.
 *
 * Unlike [RimeUserDbSnapshot], which checks Songyan's own backups strictly, this accepts what
 * every librime version writes: `/rime_version`, `/user_id`, `/tick` and even `/db_type` may be
 * missing (librime gives the restore's temporary database the userdb type itself). `/db_name` is
 * required, because without it librime's restore would merge the file into its own temporary
 * database; a `/db_type` other than userdb is refused, because librime refuses it too.
 */
object RimeUserDbText {
    /** Large personal dictionaries reach several MiB; this keeps a damaged file from filling the disk. */
    const val MAXIMUM_BYTES: Long = 32L * 1024 * 1024

    const val HEADER = "# Rime user dictionary"

    private const val NO_COMMENT = "# no comment"
    private const val MAXIMUM_METADATA_BYTES = 256
    private val HEADER_BYTES = HEADER.toByteArray(Charsets.UTF_8)
    private val BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())

    /** Whether [head], the first bytes of a file, start like a snapshot (a UTF-8 BOM allowed). */
    fun looksLikeSnapshot(head: ByteArray): Boolean {
        val start = if (head.startsWith(BOM)) BOM.size else 0
        if (head.size - start < HEADER_BYTES.size) return false
        return HEADER_BYTES.indices.all { head[start + it] == HEADER_BYTES[it] }
    }

    /** Length of the UTF-8 BOM at the start of [head], which a copy should drop; 0 when none. */
    fun bomLength(head: ByteArray): Int = if (head.startsWith(BOM)) BOM.size else 0

    /**
     * Reads [file] (without a BOM) and returns what it merges into. Throws
     * [RimeLearningException.Kind.INVALID_SNAPSHOT] for anything librime's restore would not
     * take as a user dictionary.
     */
    fun inspect(file: File): RimeSnapshotFile {
        val length = file.length()
        if (!file.isFile || length <= 0 || length > MAXIMUM_BYTES) throw invalid("snapshot size")
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val metadata = HashMap<String, String>()
        var entries = 0
        try {
            java.io.InputStreamReader(file.inputStream(), decoder).buffered().use { reader ->
                val first = reader.readLine()?.trimEnd() ?: throw invalid("empty snapshot")
                if (first != HEADER) throw invalid("not a Rime user dictionary snapshot")
                var comments = true
                while (true) {
                    // librime trims trailing whitespace, which also takes a Windows line's \r.
                    val line = reader.readLine()?.trimEnd() ?: break
                    if (line.isEmpty()) continue
                    if (comments && line.startsWith("#")) {
                        if (line.startsWith("#@")) {
                            val pair = line.substring(2).split('\t')
                            if (pair.size != 2 ||
                                pair[1].toByteArray(Charsets.UTF_8).size > MAXIMUM_METADATA_BYTES ||
                                metadata.put(pair[0], pair[1]) != null
                            ) {
                                throw invalid("snapshot metadata")
                            }
                        } else if (line == NO_COMMENT) {
                            comments = false
                        }
                        continue
                    }
                    val row = line.split('\t')
                    if (row.size >= 2 && row[0].isNotEmpty() && row[1].isNotEmpty()) entries++
                }
            }
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw invalid("snapshot is not UTF-8")
        } catch (e: IOException) {
            throw invalid("snapshot unreadable")
        }
        val type = metadata["/db_type"]
        if (type != null && type != "userdb") throw invalid("not a user dictionary")
        val name = dictionaryName(metadata["/db_name"] ?: throw invalid("snapshot without a dictionary name"))
        RimeUserDbSnapshot.validateName(name)
        return RimeSnapshotFile(name, file, entries)
    }

    /** librime's `UserDbHelper::GetDbName`: old versions wrote `name.userdb.kct`, the name ends before the last `.userdb`. */
    internal fun dictionaryName(dbName: String): String {
        val suffix = dbName.lastIndexOf(".userdb")
        return if (suffix >= 0) dbName.substring(0, suffix) else dbName
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun invalid(detail: String) = RimeLearningException(RimeLearningException.Kind.INVALID_SNAPSHOT, detail)
}
