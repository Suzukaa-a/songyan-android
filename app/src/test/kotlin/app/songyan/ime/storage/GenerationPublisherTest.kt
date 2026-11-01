package app.songyan.ime.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class GenerationPublisherTest {
    private fun newStorage(): AppStorage {
        val files = Files.createTempDirectory("app-files").toFile()
        return AppStorage(files).also { it.ensureBaseDirectories() }
    }

    private fun sourceTree(root: File): File {
        root.mkdirs()
        File(root, "default.yaml").writeText("config_version: \"1\"\n")
        File(root, "songyan_quanpin.schema.yaml").writeText("schema:\n")
        File(root, "build").mkdirs()
        File(root, "build/default.yaml").writeText("build_info:\n  rime_version: 1.17.0\n")
        File(root, "opencc").mkdirs()
        File(root, "opencc/s2t.json").writeText("{}")
        return root
    }

    @Test
    fun publishCopiesResourcesAndBuildAndActivates() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        val publisher = GenerationPublisher(storage)

        val record =
            publisher.publish(
                resourcesDir = source,
                compiledDir = File(source, "build"),
                sourceId = "songyan-builtin",
                schemaId = "songyan_quanpin",
                revision = "0.1.0/1",
            )

        assertEquals(record, publisher.readActive())
        val generation = storage.generationDir(record.generation)
        assertTrue(File(generation, "resources/default.yaml").isFile)
        assertTrue(File(generation, "resources/opencc/s2t.json").isFile)
        assertFalse(File(generation, "resources/build").exists())
        assertTrue(File(generation, "build/default.yaml").isFile)
    }

    @Test
    fun extraResourcesReplaceSourceFilesByRelativePathAndKeepTheirFolders() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        File(source, "lua").mkdirs()
        File(source, "lua/date.lua").writeText("return 'package'\n")
        File(source, "lua/other.lua").writeText("return 'other'\n")
        val own = Files.createTempDirectory("own").toFile()
        File(own, "lua").mkdirs()
        File(own, "lua/date.lua").writeText("return 'mine'\n")
        File(own, "opencc.txt").writeText("extra\n")

        val record =
            GenerationPublisher(storage).publish(
                resourcesDir = source,
                compiledDir = File(source, "build"),
                sourceId = "songyan-builtin",
                schemaId = "songyan_quanpin",
                revision = "0.1.0/1",
                extraResources =
                    mapOf(
                        "lua/date.lua" to File(own, "lua/date.lua"),
                        "opencc.txt" to File(own, "opencc.txt"),
                    ),
            )

        val resources = File(storage.generationDir(record.generation), "resources")
        assertEquals("return 'mine'\n", File(resources, "lua/date.lua").readText())
        assertEquals("return 'other'\n", File(resources, "lua/other.lua").readText())
        assertEquals("extra\n", File(resources, "opencc.txt").readText())
        assertTrue(GenerationPublisher(storage).isComplete(record))
    }

    @Test
    fun readActiveReturnsNullWhenMissingOrCorrupt() {
        val storage = newStorage()
        val publisher = GenerationPublisher(storage)
        assertNull(publisher.readActive())

        storage.activeFile.writeText("not json")
        assertNull(publisher.readActive())
    }

    @Test
    fun publishTwiceReplacesActiveAndPrunesOldGenerations() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        val publisher = GenerationPublisher(storage)

        val first = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "1")
        Thread.sleep(5)
        val second = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_flypy", "2")

        assertEquals(second, publisher.readActive())
        Thread.sleep(5)
        val third = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "3")

        assertEquals(third, publisher.readActive())
        assertTrue(storage.generationDir(third.generation).isDirectory)
        // The newest previous generation is kept, anything older is pruned.
        assertTrue(storage.generationDir(second.generation).isDirectory)
        assertFalse(storage.generationDir(first.generation).exists())
    }

    @Test
    fun prunesOnlyUnreferencedGenerations() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        val publisher = GenerationPublisher(storage)
        val previous = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "1")
        Thread.sleep(5)
        val active = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "2")
        val stale = File(storage.versionsDir, "0000000000001-songyan-builtin-abcd")
        stale.mkdirs()
        File(stale, "build").mkdirs()

        publisher.prune()

        assertEquals(active, publisher.readActive())
        assertTrue(storage.generationDir(active.generation).isDirectory)
        assertTrue(storage.generationDir(previous.generation).isDirectory)
        assertFalse(stale.exists())
    }

    @Test
    fun completenessRequiresBothResourcesAndCompiledTables() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        val publisher = GenerationPublisher(storage)
        val record = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "1")
        val generationDir = storage.generationDir(record.generation)

        assertTrue("a published generation is complete", publisher.isComplete(record))
        assertEquals("and it is the usable publication", record, publisher.usablePublication())

        // An interrupted publish can leave either half behind; the record alone must not be
        // trusted, otherwise every engine start fails on the same data forever.
        val compiled = File(generationDir, "build")
        assertTrue(compiled.deleteRecursively())
        assertFalse("missing compiled tables", publisher.isComplete(record))
        assertNull(publisher.usablePublication())

        assertTrue(compiled.mkdirs())
        assertFalse("an empty build directory is not a deployment", publisher.isComplete(record))

        File(compiled, "build_info.yaml").writeText("rime_version: 1.17.0\n")
        assertFalse("a file the manifest lists is still missing", publisher.isComplete(record))
        File(compiled, "default.yaml").writeText("build_info:\n  rime_version: 1.17.0\n")
        assertTrue(publisher.isComplete(record))

        // A nested file cut short (storage full) is caught by its recorded size.
        File(generationDir, "resources/opencc/s2t.json").writeText("")
        assertFalse("a truncated nested file", publisher.isComplete(record))
    }

    @Test
    fun ordersGenerationsBySequenceAndKeepsLeasedOnes() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        val publisher = GenerationPublisher(storage)
        val first = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "1")
        // The keyboard is still running on the first generation.
        GenerationLease(storage).hold(listOf(first.generation))
        val second = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "2")
        val third = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "3")

        assertTrue(first.sequence < second.sequence && second.sequence < third.sequence)
        assertTrue("a leased generation is never pruned", storage.generationDir(first.generation).isDirectory)
        assertTrue(storage.generationDir(second.generation).isDirectory)

        GenerationLease(storage).hold(listOf(third.generation))
        publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "4")
        assertFalse(storage.generationDir(first.generation).exists())
        assertFalse(storage.generationDir(second.generation).exists())
    }

    @Test
    fun anInterruptedPublishIsCleanedUpByTheNextOne() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        val leftover = File(storage.versionsDir, ".tmp-123-songyan-builtin-abcd").also { it.mkdirs() }
        File(leftover, "resources").mkdirs()

        GenerationPublisher(storage).publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "1")

        assertFalse(leftover.exists())
    }

    @Test
    fun aStoredGenerationCanBeMadeActiveAgain() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        val publisher = GenerationPublisher(storage)
        val stamp = BuildStamp("1", "1.17.0", "2")
        val dictionary =
            publisher.publish(
                source,
                File(source, "build"),
                "user-pinyin",
                "songyan_quanpin",
                "rev",
                listOf("songyan_quanpin", "songyan_flypy"),
                stamp,
            )
        publisher.publish(source, File(source, "build"), "scheme-0123456789abcdef", "demo", "r2", stamp = stamp)

        val again = publisher.reactivate(dictionary.generation)!!

        assertEquals(dictionary.generation, again.generation)
        assertEquals(listOf("songyan_quanpin", "songyan_flypy"), again.schemaIds)
        assertEquals(stamp, again.stamp)
        assertTrue(again.sequence > dictionary.sequence)
        assertEquals(again, publisher.readActive())
        assertTrue(again.generation in publisher.publishedRecords().map { it.generation })
        assertNull(publisher.reactivate("missing"))
    }
}
