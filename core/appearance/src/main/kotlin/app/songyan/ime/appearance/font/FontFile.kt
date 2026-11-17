package app.songyan.ime.appearance.font

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile

/** What a font file says about itself; the family name is what settings shows. */
data class FontFileInfo(
    val familyName: String,
)

/**
 * Reads the family name of a TTF, OTF or TTC file. Only the table directory and the `name` table are
 * read, so a CJK font of tens of megabytes is never loaded to be listed. A TTC reports its first font.
 * Anything that is not a well-formed font with a usable name is `null`: such a file is neither
 * imported nor drawn with.
 */
object FontFile {
    /** Larger files are refused: a font this big would not load on a phone. */
    const val MAX_BYTES: Long = 256L * 1024 * 1024

    private const val TAG_TTCF = 0x74746366
    private const val TAG_OTTO = 0x4F54544F
    private const val TAG_TRUE = 0x74727565
    private const val TAG_TYP1 = 0x74797031
    private const val VERSION_1 = 0x00010000
    private const val TAG_NAME = 0x6E616D65
    private const val MAX_TABLES = 512
    private const val MAX_NAME_TABLE = 4 * 1024 * 1024
    private const val NAME_FAMILY = 1
    private const val NAME_TYPOGRAPHIC_FAMILY = 16

    fun read(file: File): FontFileInfo? {
        if (!file.isFile) return null
        val length = file.length()
        if (length <= 0 || length > MAX_BYTES) return null
        return try {
            RandomAccessFile(file, "r").use { read(it, length) }
        } catch (_: IOException) {
            null
        }
    }

    private fun read(
        input: RandomAccessFile,
        length: Long,
    ): FontFileInfo? {
        var directory = 0L
        var tag = readInt(input, 0) ?: return null
        if (tag == TAG_TTCF) {
            val count = readInt(input, 8) ?: return null
            if (count < 1) return null
            directory = (readInt(input, 12) ?: return null).toLong() and 0xFFFFFFFFL
            tag = readInt(input, directory) ?: return null
        }
        if (tag != VERSION_1 && tag != TAG_OTTO && tag != TAG_TRUE && tag != TAG_TYP1) return null
        val tables = readShort(input, directory + 4) ?: return null
        if (tables < 1 || tables > MAX_TABLES) return null
        var nameOffset = -1L
        var nameLength = 0L
        for (i in 0 until tables) {
            val record = directory + 12 + 16L * i
            if (readInt(input, record) != TAG_NAME) continue
            nameOffset = (readInt(input, record + 8) ?: return null).toLong() and 0xFFFFFFFFL
            nameLength = (readInt(input, record + 12) ?: return null).toLong() and 0xFFFFFFFFL
            break
        }
        if (nameOffset < 0 || nameLength < 6 || nameLength > MAX_NAME_TABLE) return null
        if (nameOffset + nameLength > length) return null
        val table = ByteArray(nameLength.toInt())
        input.seek(nameOffset)
        input.readFully(table)
        return familyFrom(table)?.let(::FontFileInfo)
    }

    private fun familyFrom(table: ByteArray): String? {
        val count = u16(table, 2)
        val stringsAt = u16(table, 4)
        var best: String? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until count) {
            val at = 6 + 12 * i
            if (at + 12 > table.size) break
            val platform = u16(table, at)
            val language = u16(table, at + 4)
            val id = u16(table, at + 6)
            if (id != NAME_FAMILY && id != NAME_TYPOGRAPHIC_FAMILY) continue
            val size = u16(table, at + 8)
            val start = stringsAt + u16(table, at + 10)
            if (start + size > table.size) continue
            val text =
                when (platform) {
                    0, 3 -> String(table, start, size, Charsets.UTF_16BE)
                    1 -> String(table, start, size, Charsets.ISO_8859_1)
                    else -> continue
                }.trim()
            if (text.isEmpty() || text.any { it.isISOControl() }) continue
            // Prefer the typographic family, then Windows English, then whatever is there.
            val rank =
                (if (id == NAME_TYPOGRAPHIC_FAMILY) 0 else 4) + (if (platform == 3 && language == 0x409) 0 else 1)
            if (rank < bestRank) {
                bestRank = rank
                best = text
            }
        }
        return best
    }

    private fun u16(
        bytes: ByteArray,
        at: Int,
    ): Int = ((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)

    private fun readInt(
        input: RandomAccessFile,
        at: Long,
    ): Int? {
        if (at + 4 > input.length()) return null
        input.seek(at)
        return input.readInt()
    }

    private fun readShort(
        input: RandomAccessFile,
        at: Long,
    ): Int? {
        if (at + 2 > input.length()) return null
        input.seek(at)
        return input.readUnsignedShort()
    }
}
