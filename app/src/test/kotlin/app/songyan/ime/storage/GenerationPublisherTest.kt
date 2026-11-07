package app.songyan.ime.storage

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

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

    /** A stored scheme source: its configuration plus the compiled bins the package shipped. */
    private fun prebuiltSource(
        root: File,
        tableBytes: Int = 4096,
        reverseBytes: Int = 2048,
        prismBytes: Int = 1024,
    ): File {
        root.mkdirs()
        File(root, "default.yaml").writeText("config_version: \"1\"\n")
        File(root, "songyan_quanpin.schema.yaml").writeText("schema:\n  schema_id: songyan_quanpin\n")
        File(root, "opencc").mkdirs()
        File(root, "opencc/s2t.json").writeText("{}")
        File(root, "user/songyan.userdb").mkdirs()
        File(root, "user/songyan.userdb/CURRENT").writeText("1\n")
        val build = File(root, BUILD).also { it.mkdirs() }
        File(build, "default.yaml").writeText("build_info:\n  rime_version: 1.17.0\n")
        File(build, TABLE_BIN).writeBytes(binary(tableBytes, 1))
        File(build, REVERSE_BIN).writeBytes(binary(reverseBytes, 2))
        File(build, PRISM_BIN).writeBytes(binary(prismBytes, 3))
        return root
    }

    /**
     * The deployment's writable work build: a byte copy of the source's prebuilt bins plus what
     * librime compiled into it. No file here may be linked into a generation.
     */
    private fun workBuild(
        source: File,
        root: File,
    ): File {
        root.mkdirs()
        val build = File(root, BUILD)
        File(source, BUILD).copyRecursively(build, overwrite = true)
        File(build, COMPILED_SCHEMA).writeText("schema:\n  schema_id: songyan_quanpin\n  version: \"1\"\n")
        return build
    }

    private fun binary(
        size: Int,
        salt: Int,
    ): ByteArray = ByteArray(size) { ((it * 31 + salt) and 0xFF).toByte() }

    private fun sameFile(
        a: File,
        b: File,
    ): Boolean = Files.isSameFile(a.toPath(), b.toPath())

    /** Bytes under [root], counting a file that several paths link to only once. */
    private fun uniqueBytes(root: File): Long {
        val seen = HashSet<Any>()
        var total = 0L
        root
            .walkTopDown()
            .filter { it.isFile }
            .forEach { file ->
                val attributes = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
                val key: Any = attributes.fileKey() ?: file.absolutePath
                if (seen.add(key)) total += file.length()
            }
        return total
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
    fun anOptionChangeDropsTheOlderBuildOfTheSameInputAndKeepsTheOneToSwitchBackTo() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        val publisher = GenerationPublisher(storage)
        val builtin = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_quanpin", "1")
        Thread.sleep(5)
        val installed = publisher.publish(source, File(source, "build"), "scheme-kagiroi", "kagiroi", "a")
        Thread.sleep(5)
        val changed = publisher.publish(source, File(source, "build"), "scheme-kagiroi", "kagiroi", "b")

        assertEquals(changed, publisher.readActive())
        assertFalse(
            "the build before the option change is superseded",
            storage.generationDir(installed.generation).exists(),
        )
        assertTrue(
            "the input method used before the package stays ready to switch back to",
            storage.generationDir(builtin.generation).isDirectory,
        )
    }

    @Test
    fun prunesOnlyUnreferencedGenerations() {
        val storage = newStorage()
        val source = sourceTree(Files.createTempDirectory("source").toFile())
        val publisher = GenerationPublisher(storage)
        val previous = publisher.publish(source, File(source, "build"), "songyan-builtin", "songyan_flypy", "1")
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
        assertFalse(
            "an older build of the input method in use is not kept as the one to switch back to",
            storage.generationDir(second.generation).exists(),
        )

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

    @Test
    fun prebuiltTablesWithIdenticalBytesBecomeLinksToTheSource() {
        val storage = newStorage()
        val source = prebuiltSource(storage.sourceDir(SOURCE_ID))
        val work = workBuild(source, Files.createTempDirectory("work").toFile())
        // The deployment rewrote the compiled schema but left the prebuilt tables as they were, and
        // the source ships a table this deployment did not keep.
        File(work, TABLE_BIN).setLastModified(1_000_000_000_000L)
        File(source, "$BUILD/dropped.prism.bin").writeBytes(binary(512, 4))
        val publisher = GenerationPublisher(storage)

        val record =
            publisher.publish(
                resourcesDir = source,
                compiledDir = work,
                sourceId = SOURCE_ID,
                schemaId = SCHEMA_ID,
                revision = "1",
            )

        val generation = storage.generationDir(record.generation)
        val generationBuild = File(generation, BUILD)
        for (name in listOf(TABLE_BIN, REVERSE_BIN, PRISM_BIN)) {
            val sourceTable = File(source, "$BUILD/$name")
            val published = File(generationBuild, name)
            assertTrue(
                "$name is byte for byte the source's prebuilt table, so its bytes must be shared",
                sameFile(sourceTable, published),
            )
            assertFalse(
                "$name must not link the writable work build",
                sameFile(File(work, name), published),
            )
            assertEquals(sourceTable.length(), published.length())
        }
        assertFalse(
            "a prebuilt bin the deployment did not keep must not be resurrected",
            File(generationBuild, "dropped.prism.bin").exists(),
        )
        // Only prebuilt bins are shared: compiled configuration and the source's own configuration
        // are published as copies, as is learning data that arrived in the source tree.
        assertFalse(
            "the deployment's compiled default.yaml is configuration, not a shared bin",
            sameFile(File(source, "$BUILD/default.yaml"), File(generationBuild, "default.yaml")),
        )
        assertFalse(
            "the source's configuration is copied",
            sameFile(File(source, "default.yaml"), File(generation, "resources/default.yaml")),
        )
        val sourceUserdb = File(source, "user/songyan.userdb/CURRENT")
        val publishedUserdb = File(generation, "resources/user/songyan.userdb/CURRENT")
        assertEquals("1\n", publishedUserdb.readText())
        assertFalse("learning data is copied, never shared", sameFile(sourceUserdb, publishedUserdb))
        assertEquals(
            File(work, COMPILED_SCHEMA).readText(),
            File(generationBuild, COMPILED_SCHEMA).readText(),
        )
        assertFalse(
            "the compiled schema is not a prebuilt bin",
            sameFile(File(work, COMPILED_SCHEMA), File(generationBuild, COMPILED_SCHEMA)),
        )
        assertTrue(publisher.isComplete(record))
        assertTrue("the source keeps its prebuilt table", File(source, "$BUILD/$TABLE_BIN").isFile)
    }

    @Test
    fun aTableTheDeploymentRecompiledKeepsItsOwnBytes() {
        val storage = newStorage()
        val source = prebuiltSource(storage.sourceDir(SOURCE_ID))
        val work = workBuild(source, Files.createTempDirectory("work").toFile())
        val prebuilt = File(source, "$BUILD/$TABLE_BIN").readBytes()
        // Same byte count as the source's table, different content: only the content decides.
        val recompiled = binary(prebuilt.size, 9)
        File(work, TABLE_BIN).writeBytes(recompiled)
        val publisher = GenerationPublisher(storage)

        val record = publisher.publish(source, work, SOURCE_ID, SCHEMA_ID, "1")

        val generationBuild = File(storage.generationDir(record.generation), BUILD)
        val published = File(generationBuild, TABLE_BIN)
        assertArrayEquals(
            "a recompiled table keeps the deployment's own bytes",
            recompiled,
            published.readBytes(),
        )
        assertFalse(
            "a recompiled table is not the source's prebuilt table",
            sameFile(File(source, "$BUILD/$TABLE_BIN"), published),
        )
        assertFalse(
            "a recompiled table is not linked to the work build either",
            sameFile(File(work, TABLE_BIN), published),
        )
        assertArrayEquals(
            "the source's prebuilt table is only read",
            prebuilt,
            File(source, "$BUILD/$TABLE_BIN").readBytes(),
        )
        assertTrue(
            "a table the deployment left alone is still shared",
            sameFile(File(source, "$BUILD/$REVERSE_BIN"), File(generationBuild, REVERSE_BIN)),
        )
        assertTrue(publisher.isComplete(record))
    }

    @Test
    fun twoGenerationsShareTheSourceInodeWithoutOneChangingTheOther() {
        val storage = newStorage()
        val source = prebuiltSource(storage.sourceDir(SOURCE_ID))
        val prebuiltTable = File(source, "$BUILD/$TABLE_BIN").readBytes()
        val publisher = GenerationPublisher(storage)
        val firstWork = workBuild(source, Files.createTempDirectory("work-first").toFile())
        val first = publisher.publish(source, firstWork, SOURCE_ID, SCHEMA_ID, "1")
        // The keyboard is still running the first while the second publishes.
        GenerationLease(storage).hold(listOf(first.generation))
        val firstBuild = File(storage.generationDir(first.generation), BUILD)

        val secondWork = workBuild(source, Files.createTempDirectory("work-second").toFile())
        val recompiled = binary(prebuiltTable.size, 9)
        File(secondWork, TABLE_BIN).writeBytes(recompiled)
        val second = publisher.publish(source, secondWork, SOURCE_ID, SCHEMA_ID, "2")
        val secondBuild = File(storage.generationDir(second.generation), BUILD)

        for (name in listOf(REVERSE_BIN, PRISM_BIN)) {
            assertTrue(
                "$name must be shared with the source",
                sameFile(File(source, "$BUILD/$name"), File(firstBuild, name)),
            )
            assertTrue(
                "two publications of the same prebuilt table must share one inode for $name",
                sameFile(File(firstBuild, name), File(secondBuild, name)),
            )
        }
        assertEquals(second, publisher.readActive())
        assertArrayEquals(
            "the newest generation keeps its own recompiled table",
            recompiled,
            File(secondBuild, TABLE_BIN).readBytes(),
        )
        assertArrayEquals(
            "a new generation must not change the old one",
            prebuiltTable,
            File(firstBuild, TABLE_BIN).readBytes(),
        )

        // Whatever a deployment still writes after publishing must not reach a published generation.
        File(firstWork, TABLE_BIN).writeBytes(binary(prebuiltTable.size, 11))
        File(secondWork, TABLE_BIN).writeBytes(binary(prebuiltTable.size, 12))
        File(firstWork, REVERSE_BIN).writeBytes(binary(2048, 13))
        assertArrayEquals(
            "the old generation keeps its bytes",
            prebuiltTable,
            File(firstBuild, TABLE_BIN).readBytes(),
        )
        assertArrayEquals(
            "the new generation keeps its own bytes",
            recompiled,
            File(secondBuild, TABLE_BIN).readBytes(),
        )
        assertArrayEquals(
            "a shared bin's bytes are the source's",
            File(source, "$BUILD/$REVERSE_BIN").readBytes(),
            File(firstBuild, REVERSE_BIN).readBytes(),
        )
        assertTrue(publisher.isComplete(first))
        assertTrue(publisher.isComplete(second))
        assertEquals("both publications are kept", 2, publisher.publishedRecords().size)
    }

    @Test
    fun twoPublicationsOfPrebuiltTablesStayWithinOneCompiledBuild() {
        val storage = newStorage()
        val source =
            prebuiltSource(
                storage.sourceDir(SOURCE_ID),
                tableBytes = 1 shl 20,
                reverseBytes = 1 shl 19,
                prismBytes = 1 shl 18,
            )
        val work = workBuild(source, Files.createTempDirectory("work").toFile())
        val publisher = GenerationPublisher(storage)

        val first = publisher.publish(source, work, SOURCE_ID, SCHEMA_ID, "1")
        // The keyboard is still running the first while the second publishes.
        GenerationLease(storage).hold(listOf(first.generation))
        publisher.publish(source, work, SOURCE_ID, SCHEMA_ID, "2")

        assertEquals("both publications are kept", 2, publisher.publishedRecords().size)
        val compiledBytes = work.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val unique = uniqueBytes(storage.rimeRoot)
        assertTrue("the compiled build has no bytes", compiledBytes > 0L)
        assertTrue(
            "two publications keep $unique bytes of rime/ data for a $compiledBytes-byte compiled " +
                "build; the prebuilt tables must be shared with the source",
            unique * 10 <= compiledBytes * 13,
        )
    }

    private companion object {
        const val SOURCE_ID = "scheme-kagiroi"
        const val SCHEMA_ID = "songyan_quanpin"
        const val BUILD = "build"
        const val TABLE_BIN = "songyan_quanpin.table.bin"
        const val REVERSE_BIN = "songyan_quanpin.reverse.bin"
        const val PRISM_BIN = "songyan_quanpin.prism.bin"
        const val COMPILED_SCHEMA = "songyan_quanpin.schema.yaml"
    }
}
