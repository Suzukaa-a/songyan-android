package app.songyan.ime.content

import androidx.documentfile.provider.DocumentFile
import app.songyan.ime.packages.SchemePackageImportBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One entry of a picked folder; abstracted so the zipping rules run in JVM tests without a document provider. */
internal interface FolderNode {
    val name: String?
    val isDirectory: Boolean
    val isFile: Boolean

    fun children(): List<FolderNode>

    /** Null when the provider cannot open the document. */
    fun open(): InputStream?
}

internal class DocumentNode(
    private val document: DocumentFile,
    private val open: (DocumentFile) -> InputStream?,
) : FolderNode {
    override val name: String? get() = document.name
    override val isDirectory: Boolean get() = document.isDirectory
    override val isFile: Boolean get() = document.isFile

    override fun children(): List<FolderNode> = document.listFiles().map { DocumentNode(it, open) }

    override fun open(): InputStream? = open(document)
}

/**
 * Writes a picked scheme folder as a ZIP, so a folder goes through the same checks and limits as an
 * archive (the deploy side only ever reads `package.zip`). Hidden entries (a `.git` directory) are
 * left out; the entry count and total bytes are bounded while writing.
 */
internal object FolderPackage {
    fun zip(
        root: FolderNode,
        target: File,
        byteLimit: Long,
        entryLimit: Int = SchemePackageImportBuilder.MAX_ENTRY_COUNT,
    ) {
        target.parentFile?.mkdirs()
        var budget = byteLimit
        var entries = 0
        ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
            fun walk(
                directory: FolderNode,
                prefix: String,
            ) {
                for (child in directory.children()) {
                    val name = child.name ?: continue
                    if (name.startsWith(".")) continue
                    if (child.isDirectory) {
                        walk(child, "$prefix$name/")
                        continue
                    }
                    if (!child.isFile) continue
                    if (++entries > entryLimit) {
                        throw SchemeImportProblemException(SchemeImportProblemKind.TOO_MANY_FILES)
                    }
                    val input = child.open() ?: throw SchemeImportProblemException(SchemeImportProblemKind.READ_FAILED)
                    zip.putNextEntry(ZipEntry(prefix + name))
                    input.use {
                        try {
                            budget -= DictionaryImportFiles.copyCapped(it, zip, budget)
                        } catch (e: DictionaryImportProblemException) {
                            throw SchemeImportProblemException(SchemeImportProblemKind.FILE_TOO_LARGE, e)
                        }
                    }
                    zip.closeEntry()
                }
            }
            walk(root, "")
        }
    }
}
