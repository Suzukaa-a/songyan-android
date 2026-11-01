package app.songyan.ime.content

import app.songyan.ime.deploy.DeployFixtures
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployPipeline
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.storage.SourceCatalog
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.zip.ZipFile

/** A picked folder becomes the same `package.zip` a picked archive is (P4-2). */
class FolderPackageTest {
    private class FileNode(
        private val file: File,
    ) : FolderNode {
        override val name: String get() = file.name
        override val isDirectory: Boolean get() = file.isDirectory
        override val isFile: Boolean get() = file.isFile

        override fun children(): List<FolderNode> =
            file
                .listFiles()
                .orEmpty()
                .sortedBy { it.name }
                .map(::FileNode)

        override fun open(): InputStream = file.inputStream()
    }

    private val folder: File = Files.createTempDirectory("folder").toFile()
    private val target = File(Files.createTempDirectory("zip").toFile(), "package.zip")

    private fun write(files: Map<String, String>) {
        for ((path, text) in files) File(folder, path).also { it.parentFile.mkdirs() }.writeText(text)
    }

    private fun entries(): Set<String> =
        ZipFile(target).use { zip ->
            zip
                .entries()
                .asSequence()
                .map { it.name }
                .toSet()
        }

    @Test
    fun theFilesKeepTheirPathsAndHiddenEntriesStayOut() {
        write(
            mapOf(
                "default.yaml" to "a",
                "lua/date.lua" to "b",
                ".git/config" to "c",
                ".DS_Store" to "d",
            ),
        )

        FolderPackage.zip(FileNode(folder), target, byteLimit = 1000)

        assertEquals(setOf("default.yaml", "lua/date.lua"), entries())
    }

    @Test
    fun aFolderWithTooManyFilesIsRefused() {
        write((1..5).associate { "f$it.yaml" to "x" })

        val error =
            assertThrows(SchemeImportProblemException::class.java) {
                FolderPackage.zip(FileNode(folder), target, byteLimit = 1000, entryLimit = 4)
            }

        assertEquals(SchemeImportProblemKind.TOO_MANY_FILES, error.kind)
    }

    @Test
    fun aFolderAboveTheSizeCapIsRefused() {
        write(mapOf("big.yaml" to "0123456789"))

        val error =
            assertThrows(SchemeImportProblemException::class.java) {
                FolderPackage.zip(FileNode(folder), target, byteLimit = 5)
            }

        assertEquals(SchemeImportProblemKind.FILE_TOO_LARGE, error.kind)
    }

    @Test
    fun aDocumentThatCannotBeOpenedIsAReadFailure() {
        val unreadable =
            object : FolderNode {
                override val name: String = "root"
                override val isDirectory: Boolean = true
                override val isFile: Boolean = false

                override fun children(): List<FolderNode> =
                    listOf(
                        object : FolderNode {
                            override val name: String = "a.yaml"
                            override val isDirectory: Boolean = false
                            override val isFile: Boolean = true

                            override fun children(): List<FolderNode> = emptyList()

                            override fun open(): InputStream? = null
                        },
                    )

                override fun open(): InputStream? = null
            }

        val error =
            assertThrows(SchemeImportProblemException::class.java) {
                FolderPackage.zip(unreadable, target, byteLimit = 100)
            }

        assertEquals(SchemeImportProblemKind.READ_FAILED, error.kind)
    }

    @Test
    fun aFolderWithAPackageInstallsLikeItsArchive() =
        runTest {
            // The picker may hand over the folder that holds the repository: one wrapper directory is unwrapped.
            write(DeployFixtures.stackablePackageFiles())
            FolderPackage.zip(FileNode(folder), target, byteLimit = 1_000_000)
            val storage = DeployFixtures.storage()
            val pipeline = DeployFixtures.pipeline(storage, DeployFixtures.FakeRunner())
            target.copyTo(File(storage.incomingDir("r1"), DeployPipeline.PACKAGE_FILE).also { it.parentFile.mkdirs() })

            val status = pipeline.run(DeployRequest("r1", DeployMode.IMPORT_SCHEME))

            assertEquals(status.problem, RequestState.SUCCEEDED, status.state)
            val manifest = SourceCatalog(storage).read(status.sourceId!!)!!
            assertEquals(listOf("rime_ice", "double_pinyin"), manifest.schemaIds)
            assertNull(manifest.origin)
            assertTrue(File(storage.sourceDir(manifest.sourceId), "cn_dicts/base.dict.yaml").isFile)
        }
}
