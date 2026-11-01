package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** Ported from QuickContentKitTests: the catalog store keeps the old file on a failed import. */
class SymbolCatalogStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val root: Path get() = temporary.root.toPath()

    private val firstCatalog =
        "{\"version\":1,\"categories\":[{\"id\":\"c\",\"name\":\"测试\",\"order\":1}]," +
            "\"items\":[{\"id\":\"a\",\"text\":\"→\",\"name\":\"右箭头\",\"aliases\":[\"jiantou\"]," +
            "\"category\":\"c\"}]}"

    @Test
    fun importFailureKeepsOldCatalog() {
        val store = SymbolCatalogStore(root.resolve("store"))
        store.importCatalog(firstCatalog.toByteArray())
        assertEquals(
            "→",
            store
                .loadActive()
                .items
                .first()
                .text,
        )
        assertTrue(store.hasCustomCatalog)
        // A corrupt or unknown-field import throws and keeps the old catalog.
        assertThrows(ContentException::class.java) { store.importCatalog("not json".toByteArray()) }
        assertThrows(ContentException::class.java) {
            store.importCatalog(
                (
                    "{\"version\":1,\"items\":[{\"id\":\"a\",\"text\":\"?\",\"name\":\"x\"," +
                        "\"aliases\":[],\"category\":\"c\"}],\"oops\":0}"
                ).toByteArray(),
            )
        }
        assertEquals(1, store.loadActive().items.size)
        assertEquals(
            "→",
            store
                .loadActive()
                .items
                .first()
                .text,
        )
        // An export can be re-imported.
        store.importCatalog(store.exportActive())
        // Deleting the custom catalog brings the bundled default back.
        store.removeCustomCatalog()
        assertFalse(store.hasCustomCatalog)
        assertTrue(store.loadActive().items.size >= 80)
    }

    @Test
    fun catalogFileImportBudgetPreservesActiveOnOversize() {
        val source = root.resolve("input.json")
        val store = SymbolCatalogStore(root.resolve("store"))
        val json =
            (
                "{\"version\":1,\"items\":[{\"id\":\"x\",\"text\":\"→\",\"name\":\"箭头\"," +
                    "\"aliases\":[],\"category\":\"arrows\"}]}"
            ).toByteArray()
        val exact = json + ByteArray(SymbolCatalog.MAX_JSON_BYTES - json.size) { 0x20 }
        Files.write(source, exact)
        assertEquals(
            "→",
            store
                .importCatalogFile(source)
                .items
                .first()
                .text,
        )
        val active = store.activeFile
        val before = Files.readAllBytes(active)
        Files.write(source, exact + byteArrayOf(0x20))
        val error = assertThrows(ContentException::class.java) { store.importCatalogFile(source) }
        assertEquals(ContentErrorCode.OVERSIZE, error.code)
        assertTrue(before.contentEquals(Files.readAllBytes(active)))
        assertEquals(
            "→",
            store
                .loadActive()
                .items
                .first()
                .text,
        )
    }

    @Test
    fun catalogRejectsFifoAndDirectoryImportsWithoutChangingActive() {
        val store = SymbolCatalogStore(root.resolve("store"))
        store.importCatalog(firstCatalog.toByteArray())
        val before = Files.readAllBytes(store.activeFile)
        val pipe = TestFiles.fifo(root, "pipe.json", firstCatalog.toByteArray())
        for (source in listOf(pipe, root)) {
            assertThrows(ContentException::class.java) { store.importCatalogFile(source) }
            assertTrue(before.contentEquals(Files.readAllBytes(store.activeFile)))
            assertEquals(
                "→",
                store
                    .loadActive()
                    .items
                    .first()
                    .text,
            )
        }
    }

    @Test
    fun corruptCatalogExportReportsFailureInsteadOfFallback() {
        val directory = temporary.newFolder("custom").toPath()
        val active = directory.resolve(SymbolCatalogStore.ACTIVE_FILE_NAME)
        val damaged = "broken".toByteArray()
        Files.write(active, damaged)
        val store = SymbolCatalogStore(directory)
        assertTrue(store.loadActive().items.size >= 80)
        val error = assertThrows(ContentException::class.java) { store.exportActive() }
        assertEquals(ContentErrorCode.MALFORMED_JSON, error.code)
        assertTrue(damaged.contentEquals(Files.readAllBytes(active)))
    }

    @Test
    fun corruptCustomFallsBackToBundled() {
        val directory = temporary.newFolder("custom").toPath()
        Files.write(directory.resolve(SymbolCatalogStore.ACTIVE_FILE_NAME), "broken".toByteArray())
        val reported = ArrayList<ContentException>()
        val catalog = SymbolCatalogStore(directory).loadActive { reported.add(it) }
        assertEquals(1, reported.size)
        assertTrue(catalog.items.size >= 80)
    }
}
