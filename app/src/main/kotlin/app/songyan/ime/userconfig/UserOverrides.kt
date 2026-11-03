package app.songyan.ime.userconfig

import app.songyan.ime.packages.UserConfigSafety
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.readAtMost
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The user's files that replace a file of the source in use, as on desktop Rime: a file in the
 * user folder with the same relative path wins over the package's own, as a whole.
 *
 * Editing a package file creates such a copy (the package is never touched), so the copy is the
 * only place the user's version lives, and a package update cannot merge into it. Two facts are
 * kept beside the folder, in [AppStorage.overrideMarksFile]: the package file's hash when the copy
 * was made (to tell that the package has a newer version since) and which copies the user has
 * been told about (once each). Everything here reads and writes the disk: never call it on the
 * main thread.
 */
class UserOverrides(
    private val storage: AppStorage,
) {
    /** One file of the user's that shadows a file of the source; [updated] when the package's own changed since the copy. */
    data class Entry(
        val path: String,
        val updated: Boolean,
    )

    private class Marks(
        val bases: Map<String, String>,
        val acknowledged: Set<String>,
    )

    /** The files of the user's folder that shadow a file of [sourceDir], by relative path. */
    fun entries(sourceDir: File?): List<Entry> {
        val marks = readMarks()
        return shadowed(sourceDir).map { path ->
            val base = marks.bases[path]
            val current = sourceDir?.let { packageFile(it, path) }?.let(::sha256)
            Entry(path, updated = base != null && current != null && base != current)
        }
    }

    /** The shadowing files the user has not been told about yet. */
    fun unacknowledged(
        sourceDir: File?,
        paths: List<String> = shadowed(sourceDir),
    ): List<String> {
        val told = readMarks().acknowledged
        return paths.filter { it !in told }
    }

    fun acknowledge(paths: Collection<String>) =
        synchronized(LOCK) {
            val marks = readMarks()
            writeMarks(Marks(marks.bases, marks.acknowledged + paths))
        }

    /** The user's file at [path] (relative to the source), or null when there is none. */
    fun overrideFile(path: String): File? {
        if (!UserConfigFiles.isAllowedPath(path)) return null
        return RimeConfigPaths.inside(storage.userConfigDir, path.split('/'))?.takeIf { it.isFile }
    }

    /**
     * Where a write to the package file [packageFile], known as [path] within its source, goes: the
     * user's copy, which does not have to exist yet. Throws [UserConfigException] when the file
     * cannot be an override (name, size, count).
     */
    fun target(
        packageFile: File,
        path: String,
    ): File {
        // The import rules for a path, and only a file the source has: no new names through here.
        if (!UserConfigFiles.isAllowedPath(path) || !UserConfigSafety.safePath(path) || !packageFile.isFile) {
            throw UserConfigException(path, UserConfigProblem.NAME)
        }
        if (collides(path)) throw UserConfigException(path, UserConfigProblem.NAME)
        val existing = overrideFile(path)
        if (existing == null) {
            if (packageFile.length() > UserConfigFiles.MAX_FILE_BYTES) {
                throw UserConfigException(path, UserConfigProblem.TOO_LARGE)
            }
            if (UserConfigFiles.files(storage.userConfigDir).size >= UserConfigFiles.MAX_FILES) {
                throw UserConfigException(null, UserConfigProblem.TOO_MANY)
            }
        }
        return RimeConfigPaths.inside(storage.userConfigDir, path.split('/'))
            ?: throw UserConfigException(path, UserConfigProblem.NAME)
    }

    /**
     * Makes the user's copy of [packageFile] (as [path]) when there is none, from the package's
     * bytes, and returns it. An existing copy is left as it is.
     */
    fun copyOf(
        packageFile: File,
        path: String,
    ): File {
        val target = target(packageFile, path)
        if (!target.isFile) {
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, ".copy-" + System.nanoTime())
            try {
                packageFile.copyTo(temp, overwrite = true)
                if (!temp.renameTo(target)) throw IOException("cannot create $path")
            } finally {
                temp.delete()
            }
        }
        recordBase(path, packageFile)
        return target
    }

    /** Notes the package file's hash for [path] unless one is already known; call after the user's copy was written. */
    fun recordBase(
        path: String,
        packageFile: File,
    ) = synchronized(LOCK) {
        val marks = readMarks()
        if (path in marks.bases) return@synchronized
        val hash = sha256(packageFile) ?: return@synchronized
        writeMarks(Marks(marks.bases + (path to hash), marks.acknowledged))
    }

    /** Deletes the user's copy at [path], so the package's own file is used again. */
    fun restore(path: String): Boolean {
        val file = overrideFile(path) ?: return false
        if (!file.delete() && file.exists()) return false
        pruneEmptyFolders(file.parentFile)
        forget(path)
        return true
    }

    /** Drops what is known about [path]; the user's copy there is gone or no longer a copy of anything. */
    fun forget(path: String) =
        synchronized(LOCK) {
            val marks = readMarks()
            if (path !in marks.bases && path !in marks.acknowledged) return@synchronized
            writeMarks(Marks(marks.bases - path, marks.acknowledged - path))
        }

    /** Whether the user's folder has a file whose path differs from [path] only in letter case. */
    private fun collides(path: String): Boolean {
        val edit = storage.userConfigDir
        return UserConfigFiles.files(edit).map { UserConfigFiles.relativePath(edit, it) }.any {
            it != path && it.equals(path, ignoreCase = true)
        }
    }

    private fun shadowed(sourceDir: File?): List<String> {
        if (sourceDir == null || !sourceDir.isDirectory) return emptyList()
        val edit = storage.userConfigDir
        return UserConfigFiles
            .files(edit)
            .map { UserConfigFiles.relativePath(edit, it) }
            .filter { packageFile(sourceDir, it) != null }
            .filterNot { path ->
                if (!path.endsWith(".custom.yaml")) return@filterNot false
                val file = overrideFile(path) ?: return@filterNot false
                val bytes =
                    try {
                        file.inputStream().use { it.readAtMost((UserConfigFiles.MAX_FILE_BYTES + 1).toInt()) }
                    } catch (_: IOException) {
                        return@filterNot false
                    }
                if (bytes.size > UserConfigFiles.MAX_FILE_BYTES) return@filterNot false
                val text = UserConfigFiles.decode(bytes) ?: return@filterNot false
                UserConfigFiles.isUnmodifiedCustom(text)
            }
    }

    private fun pruneEmptyFolders(start: File?) {
        var dir = start
        val root = storage.userConfigDir.canonicalFile
        while (dir != null && dir.canonicalFile != root && dir.listFiles().isNullOrEmpty()) {
            if (!dir.delete()) return
            dir = dir.parentFile
        }
    }

    private fun readMarks(): Marks {
        val root =
            runCatching { Json.parseToJsonElement(storage.overrideMarksFile.readText()) }.getOrNull() as? JsonObject
        val bases =
            (root?.get("bases") as? JsonObject)
                ?.mapNotNull { (key, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { key to it } }
                ?.toMap()
                .orEmpty()
        val acknowledged =
            (root?.get("acknowledged") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toSet()
                .orEmpty()
        return Marks(bases, acknowledged)
    }

    /** Only what the user's folder still holds is worth remembering; the rest would resurface for a file made later. */
    private fun writeMarks(marks: Marks) {
        val present =
            UserConfigFiles
                .files(storage.userConfigDir)
                .map { UserConfigFiles.relativePath(storage.userConfigDir, it) }
                .toSet()
        val bases = marks.bases.filterKeys { it in present }
        val acknowledged = marks.acknowledged.filter { it in present }
        val file = storage.overrideMarksFile
        if (bases.isEmpty() && acknowledged.isEmpty()) {
            file.delete()
            return
        }
        val text =
            buildJsonObject {
                put("bases", buildJsonObject { bases.toSortedMap().forEach { (path, hash) -> put(path, hash) } })
                put("acknowledged", JsonArray(acknowledged.sorted().map(::JsonPrimitive)))
            }.toString()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }

    companion object {
        private val LOCK = Any()

        /** The file of a source at [path], or null when it has none (or the path leaves the source). */
        fun packageFile(
            sourceDir: File,
            path: String,
        ): File? =
            RimeConfigPaths
                .inside(sourceDir, path.split('/'))
                ?.takeIf { it.isFile }

        fun sha256(file: File): String? =
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                file.inputStream().use { input ->
                    val buffer = ByteArray(BUFFER_BYTES)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                    }
                }
                digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
            } catch (_: IOException) {
                null
            }

        private const val BUFFER_BYTES = 64 * 1024
    }
}
