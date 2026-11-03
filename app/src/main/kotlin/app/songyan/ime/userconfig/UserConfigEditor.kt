package app.songyan.ime.userconfig

import app.songyan.ime.settings.state.RimeEditTarget
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.readAtMost
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The in-app editor's side of the user's folder: the `xxx.custom.yaml` of one input method (or
 * `default.custom.yaml` for all of them), made when it is missing and saved with the same checks and
 * limits as every other file there. A file that does not pass is refused, so the editor never
 * leaves a broken file for the next "apply changes" to trip over. Reads and writes the disk: not for
 * the main thread.
 */
class UserConfigEditor(
    private val storage: AppStorage,
    private val overrides: UserOverrides = UserOverrides(storage),
) {
    /** The file for [key]: an input method's id, or [RimeEditTarget.GLOBAL_KEY]; null when no such file name is allowed. */
    fun fileName(key: String): String? {
        RimeEditTarget.scriptName(key)?.let { return "lua/$it.lua".takeIf(UserConfigFiles::isAllowedPath) }
        return (if (key == RimeEditTarget.GLOBAL_KEY) "default.custom.yaml" else "$key.custom.yaml")
            .takeIf(UserConfigFiles::isAllowedName)
    }

    /** Custom patches stay in memory until saved so reading settings does not pin source defaults. */
    fun load(
        name: String,
        sourceDir: File?,
        template: () -> String,
    ): String {
        val existing = overrides.overrideFile(name)
        if (existing != null) {
            val loaded = text(existing)
            return if (name.endsWith(".custom.yaml")) {
                UserConfigFiles.editableCustomText(loaded).takeIf { it.isNotBlank() } ?: template()
            } else {
                loaded
            }
        }
        val own = sourceDir?.let { UserOverrides.packageFile(it, name) }
        if (name.endsWith(".custom.yaml")) {
            val editable = own?.let(::text)?.let(UserConfigFiles::editableCustomText)
            return editable?.takeIf { it.isNotBlank() } ?: template()
        }
        if (own != null) return text(overrides.copyOf(own, name))
        return text(create(name, template()))
    }

    /** Saves [text] as the file [name]; null when saved, else why it was refused. */
    fun save(
        name: String,
        text: String,
    ): UserConfigException? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        UserConfigFiles.check(name, bytes)?.let { return it }
        val dir = storage.userConfigDir.also { it.mkdirs() }
        val target = File(dir, name)
        val others = UserConfigFiles.files(dir).filter { UserConfigFiles.relativePath(dir, it) != name }
        if (others.size >= UserConfigFiles.MAX_FILES) return UserConfigException(null, UserConfigProblem.TOO_MANY)
        if (others.sumOf { it.length() } + bytes.size > UserConfigFiles.MAX_TOTAL_BYTES) {
            return UserConfigException(name, UserConfigProblem.TOO_LARGE)
        }
        write(target, bytes)
        return null
    }

    private fun create(
        name: String,
        text: String,
    ): File {
        val dir = storage.userConfigDir.also { it.mkdirs() }
        if (UserConfigFiles.files(dir).size >= UserConfigFiles.MAX_FILES) {
            throw UserConfigException(null, UserConfigProblem.TOO_MANY)
        }
        return File(dir, name).also { write(it, text.toByteArray(Charsets.UTF_8)) }
    }

    /** A temporary file, then a rename: a reader never sees half of it. */
    private fun write(
        target: File,
        bytes: ByteArray,
    ) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".write-" + System.nanoTime())
        try {
            temp.writeBytes(bytes)
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            throw IOException("cannot write " + target.name, e)
        } finally {
            temp.delete()
        }
    }

    private fun text(file: File): String {
        val bytes = file.inputStream().use { it.readAtMost((UserConfigFiles.MAX_FILE_BYTES + 1).toInt()) }
        if (bytes.size >
            UserConfigFiles.MAX_FILE_BYTES
        ) {
            throw UserConfigException(file.name, UserConfigProblem.TOO_LARGE)
        }
        return UserConfigFiles.decode(bytes)?.removePrefix("\uFEFF")
            ?: throw UserConfigException(file.name, UserConfigProblem.NOT_TEXT)
    }
}
