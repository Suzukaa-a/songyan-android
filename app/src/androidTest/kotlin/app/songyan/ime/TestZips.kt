package app.songyan.ime

import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds the archives the device tests import, from path to UTF-8 text. */
internal object TestZips {
    fun write(
        target: File,
        files: Map<String, String>,
    ): File {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return target
    }
}
