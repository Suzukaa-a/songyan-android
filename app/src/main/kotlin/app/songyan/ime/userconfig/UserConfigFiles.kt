package app.songyan.ime.userconfig

import app.songyan.ime.deploy.CompiledReuse
import app.songyan.ime.packages.BoundedYaml
import app.songyan.ime.packages.UserConfigSafety
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
    /** Not a `.yaml` / `.txt` / `.lua` path this folder holds. */
    NAME,

    /** Larger than one file may be, or the folder as a whole is. */
    TOO_LARGE,

    /** More files than the folder may hold. */
    TOO_MANY,

    /** Not UTF-8 text. */
    NOT_TEXT,

    /** A `.yaml` file the YAML reader rejects. */
    INVALID_YAML,

    /** A `.yaml` file that names a file outside the data folders (an include, a dictionary, a database). */
    UNSAFE_REFERENCE,

    /** A script the user has not agreed to use (see [UserScripts]); [UserConfigException.file] names it. */
    UNCONFIRMED_SCRIPT,
}

/** [file] is the name of the file at fault, when one is; [line] the 1-based line the YAML reader stopped at, when it says. */
class UserConfigException(
    val file: String?,
    val problem: UserConfigProblem,
    detail: String? = null,
    val line: Int? = null,
) : IOException("user configuration " + (file ?: "folder") + ": " + problem + (detail?.let { " ($it)" } ?: ""))

/**
 * The user's own Rime configuration: the files a desktop Rime user keeps in the user data folder
 * (`*.custom.yaml` patches, `custom_phrase.txt`, their own `.dict.yaml`), edited through the
 * system Files app ([RimeConfigProvider]).
 *
 * The folder mirrors the source's own layout (`lua/foo.lua` overrides the source's `lua/foo.lua`)
 * and holds plain UTF-8 text only. Deployments never read what the user is
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
    private val EXTENSIONS = listOf(".yaml", ".txt", ".lua")

    /** Folders below the root; a source's own layout is one or two deep. */
    private const val MAX_DEPTH = 4

    /** librime's compiled output folder in the user data directory: files there would be taken for build products. */
    private const val BUILD_DIR = "build"
    private const val RIME_LUA = "rime.lua"
    private const val LUA_DIR = "lua"

    /** Written by the deployment itself (a dictionary stacked onto a package); a user file cannot take its place. */
    private val RESERVED = setOf("songyan_imported.dict.yaml")
    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_DETAIL = 80
    private const val DICTIONARY_SUFFIX = ".dict.yaml"

    fun isAllowedName(name: String): Boolean =
        NAME.matches(name) &&
            !name.contains("..") &&
            EXTENSIONS.any { name.endsWith(it) && name.length > it.length } &&
            name !in RESERVED

    /** A name a new file may be made with: the Files app creates `.yaml` / `.txt` here, never a script. */
    fun isCreatableName(name: String): Boolean = isAllowedName(name) && !name.endsWith(".lua")

    private fun isAllowedFolder(name: String): Boolean = NAME.matches(name) && !name.contains("..") && name != BUILD_DIR

    /**
     * Whether [path] (`/`-separated, relative to the folder) is a file this folder holds. A script
     * (`.lua`) belongs where Rime looks for one: `rime.lua`, or below `lua/`.
     */
    fun isAllowedPath(path: String): Boolean {
        val parts = path.split('/')
        if (parts.size > MAX_DEPTH + 1) return false
        if (!isAllowedName(parts.last())) return false
        if (parts.last().endsWith(".lua") && path != RIME_LUA && !(parts.size > 1 && parts.first() == LUA_DIR)) {
            return false
        }
        return parts.dropLast(1).all(::isAllowedFolder)
    }

    /** Whether [path] is a script of the user's own: `rime.lua` or a `.lua` file below `lua/`. */
    fun isScriptPath(path: String): Boolean = path.endsWith(".lua") && isAllowedPath(path)

    /** Whether the folder at [path] is `lua/` or inside it: where the user may keep new scripts. */
    fun isScriptFolder(path: String): Boolean = path == LUA_DIR || path.startsWith("$LUA_DIR/")

    /** Whether [path] names a folder this folder may hold. */
    fun isAllowedFolderPath(path: String): Boolean {
        val parts = path.split('/')
        return parts.size <= MAX_DEPTH && parts.all(::isAllowedFolder)
    }

    /** The `/`-separated path of [file] below [dir]. */
    fun relativePath(
        dir: File,
        file: File,
    ): String {
        val plain = file.relativeTo(dir).path
        // A file resolved through links (the app's data folder has two paths on Android) is relative to the real folder.
        val path = if (plain.startsWith("..")) file.canonicalFile.relativeTo(dir.canonicalFile).path else plain
        return path.replace(File.separatorChar, '/')
    }

    /** The usable files of [dir] and its folders, sorted by path; anything else there is ignored. */
    fun files(dir: File): List<File> {
        val found = ArrayList<File>()
        collect(dir, "", 0, found)
        return found.sortedBy { relativePath(dir, it) }
    }

    private fun collect(
        dir: File,
        prefix: String,
        depth: Int,
        into: MutableList<File>,
    ) {
        for (child in dir.listFiles().orEmpty()) {
            if (Files.isSymbolicLink(child.toPath())) continue
            if (child.isFile) {
                if (isAllowedPath(prefix + child.name)) into += child
            } else if (child.isDirectory && depth < MAX_DEPTH && isAllowedFolder(child.name)) {
                collect(child, prefix + child.name + "/", depth + 1, into)
            }
        }
    }

    /** Whether [dir] holds any usable file. */
    fun hasFiles(dir: File): Boolean = files(dir).isNotEmpty()

    /** Whether the files patch or replace the list of input methods. */
    fun changesSchemaList(dir: File): Boolean =
        files(dir).any {
            val path = relativePath(dir, it)
            path == "default.custom.yaml" || path == "default.yaml"
        }

    /** The problem with the file at [name] (a relative path) holding [bytes], or null when it can be used. */
    fun check(
        name: String,
        bytes: ByteArray,
    ): UserConfigException? {
        if (!isAllowedPath(name)) return UserConfigException(name, UserConfigProblem.NAME)
        if (bytes.size > MAX_FILE_BYTES) return UserConfigException(name, UserConfigProblem.TOO_LARGE)
        val text = decode(bytes) ?: return UserConfigException(name, UserConfigProblem.NOT_TEXT)
        if (name.endsWith(".yaml")) {
            val yaml = yamlPart(name, text.removePrefix("\uFEFF"))
            // The parser and librime's own both recurse on nesting: refuse it before either runs.
            if (UserConfigSafety.tooDeep(yaml)) {
                return UserConfigException(name, UserConfigProblem.INVALID_YAML, "nested too deeply")
            }
            val parsed =
                try {
                    BoundedYaml.loadAsText(yaml)
                } catch (e: Exception) {
                    return UserConfigException(
                        name,
                        UserConfigProblem.INVALID_YAML,
                        e.message?.lineSequence()?.firstOrNull(),
                        BoundedYaml.errorLine(e),
                    )
                } catch (_: StackOverflowError) {
                    return UserConfigException(name, UserConfigProblem.INVALID_YAML, "nested too deeply")
                }
            UserConfigSafety.unsafeReference(parsed)?.let {
                return UserConfigException(name, UserConfigProblem.UNSAFE_REFERENCE, it.take(MAX_DETAIL))
            }
        }
        return null
    }

    /** A dictionary source is a YAML header, ended by `...`, then tab-separated rows; only the header is YAML. */
    fun yamlPart(
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
     * [UserConfigException] naming (by relative path) the first file that cannot be used. Files with other names are
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
            val name = relativePath(edit, file)
            if (file.length() > MAX_FILE_BYTES) throw UserConfigException(name, UserConfigProblem.TOO_LARGE)
            val bytes = file.readBytes()
            total += bytes.size
            if (total > MAX_TOTAL_BYTES) throw UserConfigException(name, UserConfigProblem.TOO_LARGE)
            val path = relativePath(edit, file)
            check(path, bytes)?.let { throw it }
            File(target, path).also { it.parentFile?.mkdirs() }.writeBytes(bytes)
        }
    }

    /**
     * The problem with the files of [dir] (the applied copy) as the checks of applying see them, or
     * null: a copy a backup restored, or an older version wrote, is not trusted because it is there.
     */
    fun verify(dir: File): UserConfigException? {
        val files = files(dir)
        if (files.size > MAX_FILES) return UserConfigException(null, UserConfigProblem.TOO_MANY)
        var total = 0L
        for (file in files) {
            val name = relativePath(dir, file)
            if (file.length() > MAX_FILE_BYTES) return UserConfigException(name, UserConfigProblem.TOO_LARGE)
            total += file.length()
            if (total > MAX_TOTAL_BYTES) return UserConfigException(name, UserConfigProblem.TOO_LARGE)
            check(name, file.readBytes())?.let { return it }
        }
        return null
    }

    /**
     * Copies the files of [dir] into a deployment's work directory and returns the copies. Copies,
     * never links: the deployment rewrites some of them in place (feature patches go into the
     * user's `xxx.custom.yaml`), which must not reach the applied folder.
     */
    fun copyInto(
        dir: File,
        workDir: File,
        exclude: Set<String> = emptySet(),
    ): List<File> {
        workDir.mkdirs()
        return files(dir).filter { relativePath(dir, it) !in exclude }.map { file ->
            File(workDir, relativePath(dir, file)).also {
                it.parentFile?.mkdirs()
                Files.copy(file.toPath(), it.toPath(), StandardCopyOption.REPLACE_EXISTING)
                // Same content, same time: an unchanged patch keeps the compiled files reusable.
                CompiledReuse.stampConfigTime(it)
            }
        }
    }

    /** Identity of the files of [dir], or null when it holds none. */
    fun digest(
        dir: File,
        exclude: Set<String> = emptySet(),
    ): String? {
        val files = files(dir).filter { relativePath(dir, it) !in exclude }
        if (files.isEmpty()) return null
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        for (file in files) {
            digest.update(relativePath(dir, file).toByteArray(Charsets.UTF_8))
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
