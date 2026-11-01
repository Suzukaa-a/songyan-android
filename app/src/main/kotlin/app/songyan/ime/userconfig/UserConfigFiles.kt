package app.songyan.ime.userconfig

import app.songyan.ime.deploy.CompiledReuse
import app.songyan.ime.packages.BoundedYaml
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** Why a file of the user's Rime configuration cannot be used. */
enum class UserConfigProblem {
    /** Not a plain `.yaml` / `.txt` name this folder holds. */
    NAME,

    /** Larger than one file may be, or the folder as a whole is. */
    TOO_LARGE,

    /** More files than the folder may hold. */
    TOO_MANY,

    /** Not UTF-8 text. */
    NOT_TEXT,

    /** A `.yaml` file the YAML reader rejects. */
    INVALID_YAML,
}

/** [file] is the name of the file at fault, when one is. */
class UserConfigException(
    val file: String?,
    val problem: UserConfigProblem,
    detail: String? = null,
) : IOException("user configuration " + (file ?: "folder") + ": " + problem + (detail?.let { " ($it)" } ?: ""))

/**
 * The user's own Rime configuration: the files a desktop Rime user keeps in the user data folder
 * (`*.custom.yaml` patches, `custom_phrase.txt`, their own `.dict.yaml`), edited through the
 * system Files app ([RimeConfigProvider]).
 *
 * The folder is flat and holds plain UTF-8 text only. Deployments never read what the user is
 * editing: applying copies it, checked, into the applied folder, and every deployment layers the
 * applied copy over the source as librime's user data directory, so `xxx.custom.yaml` patches
 * `xxx.schema.yaml` the standard Rime way.
 */
object UserConfigFiles {
    /** Above any hand-written patch or phrase list; large dictionaries belong in a dictionary import. */
    const val MAX_FILE_BYTES: Long = 4L * 1024 * 1024

    /** The folder is in the cloud backup, which drops everything above 25 MB, so it stays small. */
    const val MAX_TOTAL_BYTES: Long = 8L * 1024 * 1024
    const val MAX_FILES: Int = 100

    private val NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_.+-]{0,119}$")
    private val EXTENSIONS = listOf(".yaml", ".txt")

    /** Written by the deployment itself (a dictionary stacked onto a package); a user file cannot take its place. */
    private val RESERVED = setOf("songyan_imported.dict.yaml")
    private const val BUFFER_BYTES = 64 * 1024
    private const val DICTIONARY_SUFFIX = ".dict.yaml"

    fun isAllowedName(name: String): Boolean =
        NAME.matches(name) &&
            !name.contains("..") &&
            EXTENSIONS.any { name.endsWith(it) && name.length > it.length } &&
            name !in RESERVED

    /** The usable files of [dir] by name; anything else there is ignored. */
    fun files(dir: File): List<File> =
        dir
            .listFiles()
            .orEmpty()
            .filter { isAllowedName(it.name) && it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .sortedBy { it.name }

    /** Whether [dir] holds any usable file. */
    fun hasFiles(dir: File): Boolean = files(dir).isNotEmpty()

    /** Whether the files patch or replace the list of input methods. */
    fun changesSchemaList(dir: File): Boolean =
        files(dir).any {
            it.name == "default.custom.yaml" ||
                it.name == "default.yaml"
        }

    /** The problem with [name] holding [bytes], or null when the file can be used. */
    fun check(
        name: String,
        bytes: ByteArray,
    ): UserConfigException? {
        if (!isAllowedName(name)) return UserConfigException(name, UserConfigProblem.NAME)
        if (bytes.size > MAX_FILE_BYTES) return UserConfigException(name, UserConfigProblem.TOO_LARGE)
        val text = decode(bytes) ?: return UserConfigException(name, UserConfigProblem.NOT_TEXT)
        if (name.endsWith(".yaml")) {
            try {
                BoundedYaml.load(yamlPart(name, text.removePrefix("\uFEFF")))
            } catch (e: Exception) {
                return UserConfigException(
                    name,
                    UserConfigProblem.INVALID_YAML,
                    e.message?.lineSequence()?.firstOrNull(),
                )
            }
        }
        return null
    }

    /** A dictionary source is a YAML header, ended by `...`, then tab-separated rows; only the header is YAML. */
    private fun yamlPart(
        name: String,
        text: String,
    ): String {
        if (!name.endsWith(DICTIONARY_SUFFIX)) return text
        val lines = text.lines()
        val end = lines.indexOfFirst { it.trimEnd() == "..." }
        return if (end < 0) text else lines.subList(0, end).joinToString("\n")
    }

    /** Strict UTF-8 without NUL characters, or null. */
    fun decode(bytes: ByteArray): String? {
        val decoder =
            Charsets.UTF_8
                .newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
        val text =
            try {
                decoder.decode(ByteBuffer.wrap(bytes)).toString()
            } catch (_: CharacterCodingException) {
                return null
            }
        return text.takeIf { '\u0000' !in it }
    }

    /**
     * Copies the files of [edit] into the empty directory [target], checking each one; throws
     * [UserConfigException] naming the first file that cannot be used. Files with other names are
     * left out, as librime would not read them.
     */
    fun snapshot(
        edit: File,
        target: File,
    ) {
        target.deleteRecursively()
        target.mkdirs()
        val files = files(edit)
        if (files.size > MAX_FILES) throw UserConfigException(null, UserConfigProblem.TOO_MANY)
        var total = 0L
        for (file in files) {
            if (file.length() > MAX_FILE_BYTES) throw UserConfigException(file.name, UserConfigProblem.TOO_LARGE)
            val bytes = file.readBytes()
            total += bytes.size
            if (total > MAX_TOTAL_BYTES) throw UserConfigException(file.name, UserConfigProblem.TOO_LARGE)
            check(file.name, bytes)?.let { throw it }
            File(target, file.name).writeBytes(bytes)
        }
    }

    /**
     * Copies the files of [dir] into a deployment's work directory and returns the copies. Copies,
     * never links: the deployment rewrites some of them in place (feature patches go into the
     * user's `xxx.custom.yaml`), which must not reach the applied folder.
     */
    fun copyInto(
        dir: File,
        workDir: File,
    ): List<File> {
        workDir.mkdirs()
        return files(dir).map { file ->
            File(workDir, file.name).also {
                Files.copy(file.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
                // Same content, same time: an unchanged patch keeps the compiled files reusable.
                CompiledReuse.stampConfigTime(it)
            }
        }
    }

    /** Identity of the files of [dir], or null when it holds none. */
    fun digest(dir: File): String? {
        val files = files(dir)
        if (files.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        for (file in files) {
            digest.update(file.name.toByteArray(Charsets.UTF_8))
            digest.update(0)
            file.inputStream().use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            digest.update(0)
        }
        return digest.digest().joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    }
}
