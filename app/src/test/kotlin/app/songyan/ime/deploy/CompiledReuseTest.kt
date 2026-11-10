package app.songyan.ime.deploy

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class CompiledReuseTest {
    private val root = Files.createTempDirectory("reuse").toFile()
    private val previous = File(root, "previous").also { it.mkdirs() }
    private val source = File(root, "source").also { it.mkdirs() }
    private val work = File(root, "work").also { it.mkdirs() }
    private val build = File(work, "build")

    private fun write(
        dir: File,
        name: String,
        text: String = name,
    ) = File(dir, name).also { it.writeText(text) }

    @Test
    fun aCompiledOnlyPackageSeedsItsTablesWithoutTextDictionaries() {
        val packaged = File(source, "build").also { it.mkdirs() }
        val names = setOf("words.table.bin", "words.reverse.bin", "japanese.prism.bin")
        names.forEach { write(packaged, it) }
        write(packaged, "japanese.schema.yaml")

        val copied = CompiledReuse.seed(packaged, listOf(source), build)

        assertEquals(names, copied.toSet())
        assertEquals(names, build.list()!!.toSet())
        File(build, "words.table.bin").writeText("rebuilt")
        assertEquals("words.table.bin", File(packaged, "words.table.bin").readText())
    }

    @Test
    fun theDictionariesOfTheSourceAndEveryPrismAreSeeded() {
        write(source, "rime_ice.dict.yaml")
        write(work, "stacked.dict.yaml")
        write(File(source, "cn_dicts").also { it.mkdirs() }, "base.dict.yaml")
        for (name in listOf(
            "rime_ice.table.bin",
            "rime_ice.reverse.bin",
            "rime_ice.prism.bin",
            "stacked.table.bin",
            "double_pinyin.prism.bin",
            "gone.table.bin",
            "rime_ice.schema.yaml",
            "keyboard-adapters.json",
        )) {
            write(previous, name)
        }

        val copied = CompiledReuse.seed(previous, listOf(source, work), build)

        assertEquals(
            listOf(
                "rime_ice.table.bin",
                "rime_ice.reverse.bin",
                "rime_ice.prism.bin",
                "stacked.table.bin",
                "double_pinyin.prism.bin",
            ),
            copied,
        )
        assertEquals(copied.toSet(), build.list()!!.toSet())
        assertFalse("configs are always compiled again", File(build, "rime_ice.schema.yaml").exists())
    }

    /** librime rewrites a compiled file in place when it rebuilds it; that must never reach the published one. */
    @Test
    fun seedsAreCopiesNotLinks() {
        write(source, "rime_ice.dict.yaml")
        val published = write(previous, "rime_ice.table.bin", "published")

        CompiledReuse.seed(previous, listOf(source), build)
        File(build, "rime_ice.table.bin").writeText("rebuilt")

        assertEquals("published", published.readText())
    }

    @Test
    fun withoutAnEarlierBuildNothingIsSeeded() {
        write(source, "rime_ice.dict.yaml")
        assertEquals(emptyList<String>(), CompiledReuse.seed(File(root, "missing"), listOf(source), build))
        assertFalse(build.exists())
    }

    @Test
    fun seededPrismsNoCompiledSchemaMentionsAreDropped() {
        build.mkdirs()
        write(build, "rime_ice.schema.yaml", "translator:\n  dictionary: rime_ice\nmelt_eng:\n  dictionary: melt_eng\n")
        val seeded =
            listOf("rime_ice.prism.bin", "melt_eng.prism.bin", "removed_method.prism.bin", "rime_ice.table.bin")
        seeded.forEach { write(build, it) }
        write(build, "fresh.prism.bin")

        CompiledReuse.dropUnusedPrisms(build, seeded)

        assertTrue(File(build, "rime_ice.prism.bin").exists())
        assertTrue(File(build, "melt_eng.prism.bin").exists())
        assertFalse(File(build, "removed_method.prism.bin").exists())
        assertTrue("tables are kept", File(build, "rime_ice.table.bin").exists())
        assertTrue("only seeded files are considered", File(build, "fresh.prism.bin").exists())
    }

    @Test
    fun configTimesFollowTheirContent() {
        val a = write(source, "a.schema.yaml", "same")
        val b = write(File(source, "sub").also { it.mkdirs() }, "b.custom.yaml", "same")
        val c = write(source, "c.yaml", "other")
        val dictionary = write(source, "d.dict.yaml", "same")
        val untouched = dictionary.lastModified()

        CompiledReuse.stampConfigTimes(source)

        assertEquals(a.lastModified(), b.lastModified())
        assertNotEquals(a.lastModified(), c.lastModified())
        assertEquals("dictionary sources keep their time", untouched, dictionary.lastModified())
        // librime keeps the time as a 32-bit count of seconds.
        assertTrue(a.lastModified() / 1000 in 1_000_000_000L until Int.MAX_VALUE.toLong())
        assertEquals(CompiledReuse.contentTime("same".toByteArray()), a.lastModified())
    }

    @Test
    fun aPendingMarkerBelongsToOneGeneration() {
        PendingWork.write(work, "g1", listOf("rime_ice"))
        assertEquals(listOf("rime_ice"), PendingWork.read(work, "g1"))
        assertNull(PendingWork.read(work, "g2"))
        PendingWork.clear(work)
        assertNull(PendingWork.read(work, "g1"))
        assertArrayEquals(emptyArray<String>(), work.list { _, name -> name == PendingWork.MARKER })
    }
}
