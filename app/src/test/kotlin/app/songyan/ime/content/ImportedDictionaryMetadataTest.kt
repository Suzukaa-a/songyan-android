package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ImportedDictionaryMetadataTest {
    private fun sourceDir(): File = Files.createTempDirectory("source").toFile()

    @Test
    fun readsTheGeneratedMetadataFile() {
        val dir = sourceDir()
        File(dir, "user-dictionary.json").writeText(
            "{\n" +
                "  \"dictionaryFile\": \"songyan_imported.dict.yaml\",\n" +
                "  \"dictionaryName\": \"songyan_imported\",\n" +
                "  \"displayName\": \"sample\",\n" +
                "  \"entryCount\": 1234,\n" +
                "  \"revision\": \"abc\",\n" +
                "  \"schemaId\": \"songyan_quanpin\",\n" +
                "  \"sha256\": \"abc\",\n" +
                "  \"sourceId\": \"user-pinyin\"\n" +
                "}\n",
        )

        val metadata = ImportedDictionaryMetadataReader.read(dir)

        assertEquals("sample", metadata?.displayName)
        assertEquals(1234, metadata?.entryCount)
    }

    @Test
    fun missingOrBrokenMetadataReadsAsNothing() {
        val dir = sourceDir()
        assertNull(ImportedDictionaryMetadataReader.read(dir))

        File(dir, "user-dictionary.json").writeText("{\"displayName\": \"\"}")
        assertNull(ImportedDictionaryMetadataReader.read(dir))

        File(dir, "user-dictionary.json").writeText("not json")
        assertNull(ImportedDictionaryMetadataReader.read(dir))
    }
}
