package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class DictionaryImportFilesTest {
    @Test
    fun onlyDictYamlFilesAreDictionaries() {
        assertTrue(DictionaryImportFiles.isDictionaryName("words.dict.yaml"))
        assertTrue(DictionaryImportFiles.isDictionaryName("dicts/words.dict.yaml"))
        assertFalse(DictionaryImportFiles.isDictionaryName("words.yaml"))
        assertFalse(DictionaryImportFiles.isDictionaryName("words.dict.yml"))
        assertFalse(DictionaryImportFiles.isDictionaryName("words.dict.yaml.bak"))
    }

    @Test
    fun safeNamesAreKeptAndUnsafeOnesFallBack() {
        assertEquals("words.dict.yaml", DictionaryImportFiles.stagingName("words.dict.yaml"))
        assertEquals("words.dict.yaml", DictionaryImportFiles.stagingName("content://x/words.dict.yaml"))
        assertEquals("imported.dict.yaml", DictionaryImportFiles.stagingName("我的词库.dict.yaml"))
        // The directory part never reaches a path we build: only the base name is used.
        assertEquals("words.dict.yaml", DictionaryImportFiles.stagingName("../words.dict.yaml"))
        assertEquals("imported.dict.yaml", DictionaryImportFiles.stagingName("wo rds.dict.yaml"))
        assertEquals("imported.dict.yaml", DictionaryImportFiles.stagingName(""))
        assertEquals("imported.dict.yaml", DictionaryImportFiles.stagingName(null))
    }

    @Test
    fun safeNameRulesMatchTheParser() {
        assertTrue(DictionaryImportFiles.isSafeDictionaryName("words.dict.yaml"))
        assertTrue(DictionaryImportFiles.isSafeDictionaryName("a/b-1_2.dict.yaml"))
        assertFalse(DictionaryImportFiles.isSafeDictionaryName("/words.dict.yaml"))
        assertFalse(DictionaryImportFiles.isSafeDictionaryName("a/../words.dict.yaml"))
        assertFalse(DictionaryImportFiles.isSafeDictionaryName("a//words.dict.yaml"))
        assertFalse(DictionaryImportFiles.isSafeDictionaryName("words.txt"))
        val longName = "a".repeat(240) + ".dict.yaml"
        assertFalse(DictionaryImportFiles.isSafeDictionaryName(longName))
    }

    @Test
    fun copiesUpToTheLimitAndRefusesMore() {
        val payload = ByteArray(1234) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        assertEquals(1234L, DictionaryImportFiles.copyCapped(ByteArrayInputStream(payload), output, 1234))
        assertEquals(payload.toList(), output.toByteArray().toList())

        val failure =
            runCatching {
                DictionaryImportFiles.copyCapped(ByteArrayInputStream(payload), ByteArrayOutputStream(), 1233)
            }.exceptionOrNull()
        assertEquals(DictionaryImportProblemKind.FILE_TOO_LARGE, (failure as DictionaryImportProblemException).kind)
    }

    @Test
    fun fallbackNamesStayUniqueAcrossSeveralPickedFiles() {
        val used = HashSet<String>()
        assertEquals("imported.dict.yaml", DictionaryImportFiles.uniqueStagingName("词库一.dict.yaml", used))
        assertEquals("imported-2.dict.yaml", DictionaryImportFiles.uniqueStagingName("词库二.dict.yaml", used))
        assertEquals("base.dict.yaml", DictionaryImportFiles.uniqueStagingName("base.dict.yaml", used))
        assertEquals("BASE-2.dict.yaml", DictionaryImportFiles.uniqueStagingName("BASE.dict.yaml", used))
    }
}
