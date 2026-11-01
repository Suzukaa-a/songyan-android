package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** Ported from QuickContentKitTests: recents dedupe, MRU ordering, recovery and budgets. */
class RecentSymbolStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath()

    private fun directory(name: String = "recents"): Path = root.resolve(name)

    private fun writeText(
        path: Path,
        text: String,
    ) {
        Files.write(path, text.toByteArray())
    }

    @Test
    fun invalidUtf8IsReportedAndNeverReadAsReplacementCharacters() {
        val directory = directory()
        Files.createDirectories(directory)
        val store = RecentSymbolStore(directory)
        val damaged = "[\"".toByteArray() + byteArrayOf(0xC3.toByte()) + "\"]".toByteArray()
        Files.write(store.activeFile, damaged)
        val errors = mutableListOf<ContentException>()
        assertEquals(emptyList<String>(), store.read { errors += it })
        assertEquals(listOf(ContentErrorCode.MALFORMED_JSON), errors.map { it.code })
        // Recording still works: the corrupt file is not treated as a value worth keeping.
        assertEquals(listOf("→"), store.record("→").texts)
        assertEquals(listOf("→"), store.read())
    }

    @Test
    fun recordingReportsADamagedListInsteadOfReplacingItSilently() {
        val directory = directory()
        Files.createDirectories(directory)
        val store = RecentSymbolStore(directory)
        Files.write(store.activeFile, "broken".toByteArray())
        val errors = mutableListOf<ContentException>()
        val update = store.record("→") { errors += it }
        // The new use still becomes the list, but the damage is reported as the class documents.
        assertEquals(listOf("→"), update.texts)
        assertEquals(listOf(ContentErrorCode.MALFORMED_JSON), errors.map { it.code })
    }

    @Test
    fun dedupOrderAndPersistence() {
        val directory = directory()
        val store = RecentSymbolStore(directory, 3)
        assertEquals(emptyList<String>(), store.read())
        assertEquals(listOf("→"), store.record("→").texts)
        assertEquals(listOf("㎡", "→"), store.record("㎡").texts)
        // The same text is deduplicated and moved to the front.
        assertEquals(listOf("→", "㎡"), store.record("→").texts)
        store.record("℃")
        val trimmed = store.record("№")
        assertEquals(3, trimmed.texts.size)
        assertEquals(listOf("№", "℃", "→"), trimmed.texts)
        // Blank text is not recorded.
        assertEquals(listOf("№", "℃", "→"), store.record("   ").texts)
        assertNull(trimmed.error)
        // Persistence: a new instance reads the same list back.
        assertEquals(listOf("№", "℃", "→"), RecentSymbolStore(directory).read())
        store.clear()
        assertEquals(emptyList<String>(), store.read())
        assertEquals(emptyList<String>(), RecentSymbolStore(directory).read())
    }

    @Test
    fun clearFailureKeepsCurrentWhenBackupRemovalFails() {
        val directory = directory()
        val store = RecentSymbolStore(directory)
        assertNull(store.save(listOf("keep")))
        val before = Files.readAllBytes(store.activeFile)
        val backup = store.backupFile
        Files.createDirectory(backup)
        writeText(backup.resolve("child"), "locked child")
        TestFiles.setPermissions(backup, "r-xr-x---")
        try {
            assertThrows(ContentException::class.java) { store.clear() }
        } finally {
            TestFiles.setPermissions(backup, "rwxr-x---")
        }
        assertTrue(before.contentEquals(Files.readAllBytes(store.activeFile)))
        assertEquals(listOf("keep"), store.read())
    }

    @Test
    fun clearCommitsEmptyWithoutRetainingRecoverableHistory() {
        val directory = directory()
        val store = RecentSymbolStore(directory)
        assertNull(store.save(listOf("recoverable")))
        assertNull(store.save(listOf("damaged next")))
        writeText(store.activeFile, "broken")
        assertEquals(listOf("recoverable"), store.read())
        store.clear()
        assertEquals(emptyList<String>(), store.read())
        assertEquals("[]", Files.readString(store.activeFile).trim())
        assertFalse(Files.exists(store.backupFile))
    }

    @Test
    fun readReportsCorruptionAndKeepsBackupRecovery() {
        val directory = directory()
        val store = RecentSymbolStore(directory)
        val errors = ArrayList<ContentException>()
        assertEquals(emptyList<String>(), store.read { errors.add(it) })
        assertTrue(errors.isEmpty())
        assertNull(store.save(listOf("old")))
        assertNull(store.save(listOf("new")))
        writeText(store.activeFile, "broken")
        assertEquals(listOf("old"), store.read { errors.add(it) })
        assertEquals(1, errors.size)
        // Recovery still reports the corrupt active file, and the oversized backup too.
        writeText(store.backupFile, " ".repeat(16 * 1024 + 1))
        errors.clear()
        assertEquals(emptyList<String>(), store.read { errors.add(it) })
        assertEquals(2, errors.size)
        assertEquals(ContentErrorCode.OVERSIZE, errors.last().code)
    }

    @Test
    fun corruptFallsBackToBackup() {
        val directory = directory()
        val store = RecentSymbolStore(directory)
        store.record("→")
        store.record("㎡")
        assertEquals(listOf("㎡", "→"), store.read())
        // A corrupt current file falls back to the backup.
        writeText(store.activeFile, "broken")
        assertEquals(listOf("→"), store.read())
        // Backup corrupt too: empty, no crash, no writes.
        writeText(store.backupFile, "broken")
        assertEquals(emptyList<String>(), store.read())
        // Writing works again afterwards.
        assertEquals(listOf("≤"), store.record("≤").texts)
        assertEquals(listOf("≤"), RecentSymbolStore(directory).read())
    }

    @Test
    fun recoveryPreservesLastValidBackup() {
        val directory = directory()
        val store = RecentSymbolStore(directory)
        assertNull(store.record("→").error)
        assertNull(store.record("㎡").error)
        val goodBackup = Files.readAllBytes(store.backupFile)
        writeText(store.activeFile, "broken")
        assertNull(store.record("≤").error)
        assertEquals(listOf("≤", "→"), store.read())
        assertTrue(goodBackup.contentEquals(Files.readAllBytes(store.backupFile)))
        // A second damaged current file can still recover the last known good copy.
        writeText(store.activeFile, "broken again")
        assertEquals(listOf("→"), store.read())
    }

    @Test
    fun oversizeSavePreservesBothCopies() {
        val directory = directory()
        val store = RecentSymbolStore(directory)
        assertNull(store.record("→").error)
        assertNull(store.record("㎡").error)
        val previous = Files.readAllBytes(store.activeFile)
        val previousBackup = Files.readAllBytes(store.backupFile)
        // Combining marks can make even a single grapheme larger than the byte budget.
        val oversized = "a" + "\u0301".repeat(9000)
        assertNotNull(store.record(oversized).error)
        assertNotNull(store.save(List(101) { "→" }))
        assertTrue(previous.contentEquals(Files.readAllBytes(store.activeFile)))
        assertTrue(previousBackup.contentEquals(Files.readAllBytes(store.backupFile)))
        assertEquals(listOf("㎡", "→"), store.read())
    }

    @Test
    fun oversizeActiveFallsBackWithoutChangingFiles() {
        val directory = directory()
        val store = RecentSymbolStore(directory)
        assertNull(store.record("→").error)
        assertNull(store.record("㎡").error)
        val oversized = ByteArray(32 * 1024) { 0x20 }
        Files.write(store.activeFile, oversized)
        assertEquals(listOf("→"), store.read())
        assertTrue(oversized.contentEquals(Files.readAllBytes(store.activeFile)))
    }

    @Test
    fun saveFailureKeepsOldFile() {
        // A regular file where the directory is expected forces the write to fail.
        val blocker = temporary.newFile("blocker").toPath()
        val store = RecentSymbolStore(blocker)
        val result = store.record("→")
        assertNotNull("a write failure must be reported", result.error)
        assertEquals(listOf("→"), result.texts)
    }

    @Test
    fun rejectsNonRegularActiveAndPreservesValidBackupOnSave() {
        val directory = directory()
        Files.createDirectory(directory)
        val backupData = "[\"last valid\"]".toByteArray()
        Files.write(directory.resolve(RecentSymbolStore.BACKUP_FILE_NAME), backupData)
        TestFiles.fifo(directory, RecentSymbolStore.ACTIVE_FILE_NAME, "[\"untrusted\"]".toByteArray())
        val store = RecentSymbolStore(directory)
        val errors = ArrayList<ContentException>()
        assertEquals(listOf("last valid"), store.read { errors.add(it) })
        assertEquals(1, errors.size)
        assertEquals(ContentErrorCode.NOT_A_REGULAR_FILE, errors.first().code)
        assertNull(store.save(listOf("new")))
        assertEquals(listOf("new"), store.read())
        assertTrue(backupData.contentEquals(Files.readAllBytes(store.backupFile)))
    }

    @Test
    fun regularReadsHonorExactBudgetAndOversizePreservesBackup() {
        val directory = directory()
        Files.createDirectory(directory)
        val file = directory.resolve("input")
        val exact = ByteArray(16 * 1024) { 0x20 }
        Files.write(file, exact)
        assertEquals(exact.size, BoundedFile.read(file, exact.size).size)
        Files.write(file, exact + byteArrayOf(0x20))
        assertThrows(ContentException::class.java) { BoundedFile.read(file, exact.size) }
        val backupData = "[\"last valid\"]".toByteArray()
        Files.write(directory.resolve(RecentSymbolStore.BACKUP_FILE_NAME), backupData)
        Files.write(directory.resolve(RecentSymbolStore.ACTIVE_FILE_NAME), exact + byteArrayOf(0x20))
        val store = RecentSymbolStore(directory)
        assertEquals(listOf("last valid"), store.read())
        assertNull(store.save(listOf("new")))
        assertTrue(backupData.contentEquals(Files.readAllBytes(store.backupFile)))
        assertEquals(listOf("new"), store.read())
    }
}
