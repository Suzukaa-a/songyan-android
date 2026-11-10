package app.songyan.ime.storage

import app.songyan.ime.deploy.CompiledReuse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class PrebuiltResourceStoreTest {
    private val files = Files.createTempDirectory("compiled-store").toFile()
    private val storage = AppStorage(files).also { it.ensureBaseDirectories() }
    private val publisher = GenerationPublisher(storage)

    private fun source(
        revision: String,
        value: String,
    ): File {
        val source = storage.sourceDir("scheme-kagiroi").also { it.mkdirs() }
        File(source, "default.yaml").writeText("schema_list:\n  - schema: kagiroi\n")
        File(source, "kagiroi.schema.yaml").writeText("schema:\n  schema_id: kagiroi\n")
        File(source, "build").mkdirs()
        File(source, "build/kagiroi.table.bin").writeText(value)
        SourceCatalog.write(
            source,
            SourceManifest(
                "scheme-kagiroi",
                SourceKind.SCHEME,
                "日本語",
                listOf(SourceScheme("kagiroi", "日本語")),
                revision,
            ),
        )
        return source
    }

    private fun publish(
        source: File,
        revision: String,
        deployed: String? = null,
    ): PublicationRecord {
        val work = Files.createTempDirectory(files.toPath(), "work").toFile()
        File(work, "default.yaml").writeText("compiled: true\n")
        File(work, "kagiroi.schema.yaml").writeText("schema:\n  schema_id: kagiroi\n")
        if (deployed == null) {
            File(source, "build/kagiroi.table.bin").copyTo(File(work, "kagiroi.table.bin"))
        } else {
            File(work, "kagiroi.table.bin").writeText(deployed)
        }
        return publisher.publish(source, work, "scheme-kagiroi", "kagiroi", revision)
    }

    private fun table(record: PublicationRecord): File =
        File(storage.generationDir(record.generation), "build/kagiroi.table.bin")

    @Test
    fun identicalRebuiltTablesShareThePreviousImmutableOutput() {
        val source = source("one", "original")
        val first = publish(source, "one", "rebuilt")
        // The keyboard is still running the earlier publication.
        GenerationLease(storage).hold(listOf(first.generation))
        val second = publish(source, "two", "rebuilt")
        assertEquals("original", File(source, "build/kagiroi.table.bin").readText())
        assertTrue(Files.isSameFile(table(first).toPath(), table(second).toPath()))
    }

    @Test
    fun aSourceUpdateDoesNotChangeThePreviousPublication() {
        val old = source("one", "old table")
        val first = publish(old, "one")
        // The keyboard is still running the earlier publication.
        GenerationLease(storage).hold(listOf(first.generation))
        Files.move(old.toPath(), File(files, "retired-source").toPath())
        val replacement = source("two", "new table")
        val second = publish(replacement, "two")
        assertEquals("old table", table(first).readText())
        assertEquals("new table", table(second).readText())
        assertTrue(publisher.isComplete(first))
        assertTrue(publisher.isComplete(second))
    }

    @Test
    fun aTransferredSourceCanRecoverReferencesFromItsExistingManifest() {
        val source = source("one", "original")
        val publication = publish(source, "one")
        val input = File(source, "build/kagiroi.table.bin")
        assertTrue(Files.isSymbolicLink(input.toPath()))
        Files.delete(input.toPath())
        // Device transfer keeps source inputs and their pool; generated publications are excluded.
        Files.move(storage.versionsDir.toPath(), File(files, "untransferred-generations").toPath())
        publisher.prune(publication)
        val build = CompiledReuse.prebuiltBuild(source)
        assertEquals("original", File(build, "kagiroi.table.bin").readText())
    }

    @Test
    fun pruningOldGenerationsReclaimsTheirUnreferencedInputVersion() {
        val old = source("one", "old")
        val first = publish(old, "one")
        val oldBytes = table(first).canonicalFile
        Files.move(old.toPath(), File(files, "retired-source").toPath())
        val current = source("two", "new")
        val second = publish(current, "two")
        // The keyboard is still running the earlier publication.
        GenerationLease(storage).hold(listOf(second.generation))
        val third = publish(current, "three")
        assertFalse(table(first).exists())
        assertFalse(oldBytes.exists())
        assertEquals("new", table(second).readText())
        assertEquals("new", table(third).readText())
        assertTrue(publisher.isComplete(third))
    }
}
