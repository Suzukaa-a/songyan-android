package app.songyan.ime.deploy

import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest

/**
 * How a newly imported scheme package relates to the packages already stored.
 *
 * A package's source id is the hash of its files, so a newer version of the same package has a
 * different one. Without this, importing it from a file stored it as a second package that learned
 * separately, and the first one stayed around with the words the user had taught it. As on iOS
 * (`SchemeReimport`), the same main input method means the same package: the new files become a new
 * version of the stored one, under its id, so learned words, a stacked dictionary and the recorded
 * link stay with it.
 */
internal object PackageUpdate {
    /**
     * The stored package [imported] is a new version of, or null for a new package. The one in use
     * wins when earlier versions stored several copies.
     */
    fun replaced(
        imported: SourceManifest,
        stored: List<SourceManifest>,
        activeSourceId: String?,
    ): SourceManifest? {
        val primary = imported.schemes.firstOrNull()?.schemaId ?: return null
        val candidates =
            stored.filter {
                it.kind == SourceKind.SCHEME &&
                    SourceIds.isScheme(it.sourceId) &&
                    it.sourceId != imported.sourceId &&
                    it.schemes.firstOrNull()?.schemaId == primary
            }
        return candidates.firstOrNull { it.sourceId == activeSourceId } ?: candidates.firstOrNull()
    }

    /**
     * Grammar models are distributed apart from the packages that use them (they are hundreds of
     * megabytes), so a user adds one to the package's folder by hand, and the package's next
     * version does not bring it. Every `.gram` file of [previousTree] that [staged] lacks is put into
     * [staged] at the same path. Returns the carried paths, sorted.
     */
    fun carryGrammarModels(
        previousTree: File,
        staged: File,
    ): List<String> {
        if (!previousTree.isDirectory) return emptyList()
        val carried = ArrayList<String>()
        previousTree
            .walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && it.name.endsWith(GRAMMAR_SUFFIX) && !Files.isSymbolicLink(it.toPath()) }
            .forEach { model ->
                val path = model.relativeTo(previousTree).invariantSeparatorsPath
                if (path.startsWith(".")) return@forEach
                val target = File(staged, path)
                if (target.exists()) return@forEach
                target.parentFile?.mkdirs()
                // The old tree is deleted once the new version is published; a link keeps the data
                // without copying a model of hundreds of megabytes.
                try {
                    Files.createLink(target.toPath(), model.toPath())
                } catch (_: IOException) {
                    model.copyTo(target)
                } catch (_: UnsupportedOperationException) {
                    model.copyTo(target)
                }
                carried += path
            }
        return carried.sorted()
    }

    /**
     * The revision of a version that also holds [carried] models of the earlier one: the same new
     * files with the same carried models always give the same revision, so importing that version
     * again finds it up to date.
     */
    fun revisionWithCarried(
        revision: String,
        staged: File,
        carried: List<String>,
    ): String {
        if (carried.isEmpty()) return revision
        val text = revision + "\n" + carried.joinToString("\n") { it + ":" + File(staged, it).length() }
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.take(REVISION_LENGTH)
    }

    private const val GRAMMAR_SUFFIX = ".gram"
    private const val REVISION_LENGTH = 40
}
