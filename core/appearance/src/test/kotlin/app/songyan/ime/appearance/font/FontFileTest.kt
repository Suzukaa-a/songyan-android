package app.songyan.ime.appearance.font

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File

class FontFileTest {
    @get:Rule
    val folder = TemporaryFolder()

    private class NameRecord(
        val platform: Int,
        val language: Int,
        val id: Int,
        val text: String,
    )

    private fun nameTable(records: List<NameRecord>): ByteArray {
        val strings = ByteArrayOutputStream()
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeShort(0)
        data.writeShort(records.size)
        data.writeShort(6 + 12 * records.size)
        for (r in records) {
            val bytes = r.text.toByteArray(if (r.platform == 1) Charsets.ISO_8859_1 else Charsets.UTF_16BE)
            data.writeShort(r.platform)
            data.writeShort(if (r.platform == 1) 0 else 1)
            data.writeShort(r.language)
            data.writeShort(r.id)
            data.writeShort(bytes.size)
            data.writeShort(strings.size())
            strings.write(bytes)
        }
        out.write(strings.toByteArray())
        return out.toByteArray()
    }

    /** A font with only a name table: enough for the reader, which never touches glyphs. */
    private fun sfnt(
        tag: Int,
        records: List<NameRecord>,
        base: Int = 0,
    ): ByteArray {
        val table = nameTable(records)
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeInt(tag)
        data.writeShort(1)
        data.writeShort(16)
        data.writeShort(0)
        data.writeShort(0)
        data.writeInt(0x6E616D65)
        data.writeInt(0)
        data.writeInt(base + 12 + 16 + 0)
        data.writeInt(table.size)
        out.write(table)
        return out.toByteArray()
    }

    private fun write(
        name: String,
        bytes: ByteArray,
    ): File = File(folder.root, name).also { it.writeBytes(bytes) }

    private val english = NameRecord(3, 0x409, 1, "Example Sans")

    @Test
    fun readsTheWindowsFamilyName() {
        val file = write("a.ttf", sfnt(0x00010000, listOf(english)))
        assertEquals("Example Sans", FontFile.read(file)?.familyName)
    }

    @Test
    fun prefersTheTypographicFamilyOverTheLegacyOne() {
        val file =
            write(
                "a.otf",
                sfnt(0x4F54544F, listOf(english, NameRecord(3, 0x409, 16, "Example Sans Display"))),
            )
        assertEquals("Example Sans Display", FontFile.read(file)?.familyName)
    }

    @Test
    fun prefersEnglishOverAnotherLanguage() {
        val file = write("a.ttf", sfnt(0x00010000, listOf(NameRecord(3, 0x804, 1, "示例黑体"), english)))
        assertEquals("Example Sans", FontFile.read(file)?.familyName)
    }

    @Test
    fun fallsBackToTheMacintoshName() {
        val file = write("a.ttf", sfnt(0x00010000, listOf(NameRecord(1, 0, 1, "Mac Name"))))
        assertEquals("Mac Name", FontFile.read(file)?.familyName)
    }

    @Test
    fun readsTheFirstFontOfACollection() {
        val inner = sfnt(0x00010000, listOf(english), base = 16)
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeInt(0x74746366)
        data.writeInt(0x00010000)
        data.writeInt(1)
        data.writeInt(16)
        out.write(inner)
        assertEquals("Example Sans", FontFile.read(write("a.ttc", out.toByteArray()))?.familyName)
    }

    @Test
    fun rejectsFilesThatAreNotFonts() {
        assertNull(FontFile.read(write("a.ttf", "plain text, not a font".toByteArray())))
        assertNull(FontFile.read(write("b.ttf", ByteArray(0))))
        assertNull(FontFile.read(File(folder.root, "missing.ttf")))
    }

    @Test
    fun rejectsATruncatedFont() {
        val full = sfnt(0x00010000, listOf(english))
        assertNull(FontFile.read(write("a.ttf", full.copyOf(full.size - 8))))
        assertNull(FontFile.read(write("b.ttf", full.copyOf(10))))
    }

    @Test
    fun rejectsAFontWithoutANameTable() {
        val out = ByteArrayOutputStream()
        val data = DataOutputStream(out)
        data.writeInt(0x00010000)
        data.writeShort(1)
        data.writeShort(16)
        data.writeShort(0)
        data.writeShort(0)
        data.writeInt(0x636D6170)
        data.writeInt(0)
        data.writeInt(28)
        data.writeInt(4)
        data.writeInt(0)
        assertNull(FontFile.read(write("a.ttf", out.toByteArray())))
    }
}
