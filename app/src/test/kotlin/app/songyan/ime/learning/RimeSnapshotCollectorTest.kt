package app.songyan.ime.learning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Finding and staging `.userdb.txt` snapshots in picked files, ZIPs and folders. */
class RimeSnapshotCollectorTest {
    private val root: File = Files.createTempDirectory("collector").toFile()
    private val staging = File(root, "staging")

    private fun snapshot(
        name: String,
        rows: Int = 1,
    ): String =
        "# Rime user dictionary\n#@/db_name\t$name\n#@/db_type\tuserdb\n" +
            (1..rows).joinToString("") { "ni $it \t你$it\tc=$it d=1 t=1\n" }

    private fun picked(
        name: String,
        bytes: ByteArray,
    ) = PickedFile(name) { bytes.inputStream() }

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            for ((name, text) in entries) {
                zip.putNextEntry(ZipEntry(name))
                if (!name.endsWith("/")) zip.write(text.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private class DirectoryNode(
        private val file: File,
    ) : PickedNode {
        override val name: String = file.name
        override val isDirectory: Boolean = file.isDirectory

        override fun children(): List<PickedNode> = file.listFiles().orEmpty().map(::DirectoryNode)

        override fun open(): InputStream = file.inputStream()
    }

    @Test
    fun aSingleFileIsReadByItsContentWhateverItsName() {
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val collected =
            RimeSnapshotCollector.collect(
                listOf(
                    picked("我的词库.txt", bom + snapshot("rime_ice", rows = 3).toByteArray()),
                    picked("notes.txt", "hello".toByteArray()),
                ),
                staging,
                byContent = true,
            )
        val staged = collected.staged.single()
        assertEquals("我的词库.txt", staged.fileName)
        assertEquals("rime_ice", staged.snapshot.dictionary)
        assertEquals(3, staged.snapshot.entryCount)
        // The copy drops the BOM, so librime reads the header line as a header.
        assertTrue(
            staged.snapshot.file
                .readText()
                .startsWith("# Rime user dictionary\n"),
        )
        assertTrue(staged.snapshot.file.startsWith(staging))
        assertEquals(
            listOf("notes.txt" to SnapshotSkipReason.NOT_A_SNAPSHOT),
            collected.skipped.map {
                it.fileName to
                    it.reason
            },
        )
    }

    @Test
    fun aZipOfASyncFolderYieldsEverySnapshotAndIgnoresTheRest() {
        val bytes =
            zip(
                "sync/" to "",
                "sync/installation-a/" to "",
                "sync/installation-a/luna_pinyin.userdb.txt" to snapshot("luna_pinyin", rows = 2),
                "sync/installation-a/installation.yaml" to "installation_id: a\n",
                "sync/installation-b/luna_pinyin.userdb.txt" to snapshot("luna_pinyin"),
                "sync/installation-b/rime_ice.userdb.txt" to snapshot("rime_ice", rows = 0),
                "sync/installation-b/broken.userdb.txt" to "not a snapshot",
                "__MACOSX/sync/._luna_pinyin.userdb.txt" to "resource fork",
                "sync/.hidden.userdb.txt" to snapshot("hidden"),
            )
        val collected = RimeSnapshotCollector.collect(listOf(picked("rime-sync.zip", bytes)), staging, byContent = true)
        assertEquals(listOf("luna_pinyin", "luna_pinyin"), collected.staged.map { it.snapshot.dictionary })
        assertEquals(listOf(2, 1), collected.staged.map { it.snapshot.entryCount })
        assertEquals(
            listOf(
                "rime_ice.userdb.txt" to SnapshotSkipReason.EMPTY,
                "broken.userdb.txt" to SnapshotSkipReason.NOT_A_SNAPSHOT,
            ),
            collected.skipped.map { it.fileName to it.reason },
        )
        // Each staged copy is its own file, so two snapshots of one dictionary both merge.
        assertEquals(
            2,
            collected.staged
                .map { it.snapshot.file }
                .toSet()
                .size,
        )
    }

    @Test
    fun aZipWithoutSnapshotsOrADamagedOneIsReportedByItsName() {
        val empty = zip("readme.md" to "hi")
        val damaged = zip("a.userdb.txt" to snapshot("a")).copyOf(30)
        val collected =
            RimeSnapshotCollector.collect(
                listOf(picked("empty.zip", empty), picked("damaged.zip", damaged)),
                staging,
                byContent = true,
            )
        assertTrue(collected.staged.isEmpty())
        assertEquals(
            listOf("empty.zip", "damaged.zip"),
            collected.skipped.map { it.fileName },
        )
    }

    @Test
    fun aFolderWalkFindsSnapshotsInSubfoldersButNotTooDeep() {
        val sync = File(root, "picked/sync").also { it.mkdirs() }
        File(sync, "device-1").mkdirs()
        File(sync, "device-1/luna_pinyin.userdb.txt").writeText(snapshot("luna_pinyin"))
        File(sync, "device-1/default.custom.yaml").writeText("patch:\n")
        File(sync, "top.userdb.txt").writeText(snapshot("top"))
        File(sync, "a/b/c").mkdirs()
        File(sync, "a/b/c/deep.userdb.txt").writeText(snapshot("deep"))
        File(sync, ".trash").mkdirs()
        File(sync, ".trash/old.userdb.txt").writeText(snapshot("old"))

        val files = RimeSnapshotCollector.filesIn(DirectoryNode(sync))
        assertEquals(listOf("luna_pinyin.userdb.txt", "top.userdb.txt"), files.map { it.name })

        val collected = RimeSnapshotCollector.collect(files, staging, byContent = false)
        assertEquals(listOf("luna_pinyin", "top"), collected.staged.map { it.snapshot.dictionary })
    }

    @Test
    fun aFolderPickIsNotOpenedAsAZip() {
        val collected =
            RimeSnapshotCollector.collect(
                listOf(picked("x.userdb.txt", zip("a.userdb.txt" to snapshot("a")))),
                staging,
                byContent = false,
            )
        assertEquals(SnapshotSkipReason.NOT_A_SNAPSHOT, collected.skipped.single().reason)
    }

    @Test
    fun tooManySnapshotsAreRefusedAsAWhole() {
        val many =
            (0..RimeSnapshotCollector.MAXIMUM_FILES).map {
                picked(
                    "$it.userdb.txt",
                    snapshot("d$it").toByteArray(),
                )
            }
        try {
            RimeSnapshotCollector.collect(many, staging, byContent = true)
            fail("took more than the maximum")
        } catch (_: RimeSnapshotCollector.TooLarge) {
        }
    }

    @Test
    fun skippedFilesDoNotCountTowardTheTotalSizeLimit() {
        // Each is a valid header followed by blank lines: no entries, so it is skipped as empty.
        val head = "# Rime user dictionary\n#@/db_name\tfiller\n#@/db_type\tuserdb\n".toByteArray()
        val size = 30L * 1024 * 1024

        fun filler(): InputStream =
            object : InputStream() {
                private var sent = 0L

                override fun read(): Int =
                    if (sent <
                        size
                    ) {
                        head.getOrElse((sent++).toInt()) { '\n'.code.toByte() }.toInt()
                    } else {
                        -1
                    }
            }
        val empties = (0 until 6).map { PickedFile("e$it.userdb.txt") { filler() } }
        val good = picked("good.userdb.txt", snapshot("good").toByteArray())
        val collected = RimeSnapshotCollector.collect(empties + good, staging, byContent = true)
        assertEquals(listOf("good.userdb.txt"), collected.staged.map { it.fileName })
        assertEquals(6, collected.skipped.size)
    }

    @Test
    fun stagingStartsEmpty() {
        staging.mkdirs()
        File(staging, "left-over.userdb.txt").writeText("old")
        RimeSnapshotCollector.collect(listOf(picked("a", snapshot("a").toByteArray())), staging, byContent = true)
        assertEquals(listOf("incoming-0.userdb.txt"), staging.list()!!.toList())
    }

    @Test
    fun aPackageYieldsItsSnapshotsUnderTheFolderRules() {
        val archive =
            File(root, "package.zip").apply {
                writeBytes(
                    zip(
                        "pkg/default.yaml" to "x",
                        "pkg/rime_ice.userdb.txt" to snapshot("rime_ice", rows = 2),
                        "pkg/sync/phone/double_pinyin.userdb.txt" to snapshot("double_pinyin"),
                        "pkg/.hidden/other.userdb.txt" to snapshot("other"),
                        "pkg/._rime_ice.userdb.txt" to snapshot("other"),
                        "__MACOSX/pkg/mac.userdb.txt" to snapshot("mac"),
                        "pkg/a/b/c/deep.userdb.txt" to snapshot("deep"),
                        "pkg/broken.userdb.txt" to "not a snapshot",
                        "pkg/empty.userdb.txt" to snapshot("empty", rows = 0),
                    ),
                )
            }

        val collected = RimeSnapshotCollector.collectFromPackage(archive, staging)

        assertEquals(
            listOf("double_pinyin", "rime_ice"),
            collected.staged.map { it.snapshot.dictionary }.sorted(),
        )
        assertEquals(
            setOf(SnapshotSkipReason.NOT_A_SNAPSHOT, SnapshotSkipReason.EMPTY),
            collected.skipped.map { it.reason }.toSet(),
        )
        assertTrue(collected.staged.all { it.snapshot.file.startsWith(staging) })
    }

    @Test
    fun aPackageWithoutSnapshotsOrAnUnreadableOneStagesNothing() {
        val plain = File(root, "plain.zip").apply { writeBytes(zip("pkg/default.yaml" to "x")) }
        val broken = File(root, "broken.zip").apply { writeBytes(byteArrayOf(0x50, 0x4B, 3, 4, 1, 2)) }

        for (archive in listOf(plain, broken)) {
            val collected = RimeSnapshotCollector.collectFromPackage(archive, staging)
            assertTrue(collected.staged.isEmpty())
        }
    }
}
