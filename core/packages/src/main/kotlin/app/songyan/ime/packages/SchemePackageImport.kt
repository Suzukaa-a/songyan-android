package app.songyan.ime.packages

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipFile

/**
 * One rejected scheme package import.
 *
 * [reason] is the stable contract the app layer maps to user-facing copy; [message] is
 * developer-facing context (the offending entry or cap) and is never shown verbatim. [paths] lists
 * the files a [Reason.MISSING_DEPENDENCY] needs.
 */
class SchemePackageImportError(
    val reason: Reason,
    message: String,
    val paths: List<String> = emptyList(),
) : Exception(message) {
    /** Why an import was rejected. Values are stable API for the app module. */
    enum class Reason {
        /** The file is not a ZIP archive, or the archive is truncated or corrupt. */
        INVALID_ZIP,

        /** Entry names are not UTF-8 (typically a GBK archive written by an old Windows zip tool). */
        UNSUPPORTED_NAME_ENCODING,

        /** The archive, one file or the expanded package exceeds its byte cap. */
        TOO_LARGE,

        /** The archive holds more entries than the cap. */
        TOO_MANY_ENTRIES,

        /** An entry path is absolute, escapes the package or cannot exist on a file system. */
        UNSAFE_PATH,

        /** Two entries name the same file, case-insensitively, so staging would lose one. */
        DUPLICATE_PATH,

        /** The package carries a learning database (`*.userdb`); only `*.userdb.txt` snapshots are tolerated, and they are never source files. */
        USER_DATA,

        /** The package has no root-level `*.schema.yaml`. */
        MISSING_SCHEMA,

        /** A listed scheme is not readable YAML or declares a `schema_id` unlike its file name. */
        INVALID_SCHEMA,

        /** A configuration file is not UTF-8 text. */
        INVALID_ENCODING,

        /** A scheme needs files (dictionaries, includes, Lua, OpenCC data) the package does not ship. */
        MISSING_DEPENDENCY,

        /** A grammar model (`*.gram`) has an unusable path or size. */
        INVALID_MODEL,

        /** A `*.custom.yaml` patches one of the app's own built-in input methods. */
        BUILTIN_CUSTOMIZATION,

        /** Two archives of one package fill the same runtime path with different bytes. */
        CONFLICTING_FILE,
    }
}

/** One input method the package enables, with the name its schema declares. */
data class ImportedScheme(
    val schemaId: String,
    val name: String,
)

/**
 * A checked scheme package, extracted to [root] on disk and ready to be deployed as one Rime
 * source.
 *
 * [schemes] are the input methods the package's own `default.yaml` enables, in its order; the
 * first is the primary one. [packageId] is the package's learning identity: the hash of the
 * package's own files, so re-importing the same package restores its learning data while a
 * different package (or another version of it) learns separately, as on iOS. [revision] covers
 * every file of the staged source, including the app-supplied presets.
 */
data class PreparedSchemeImport(
    val root: File,
    val schemes: List<ImportedScheme>,
    val displayName: String,
    val packageId: String,
    val revision: String,
    /** Package files that were not staged because Rime never reads them (images, scripts, docs). */
    val skippedFiles: List<String>,
) {
    val schemaIds: List<String> get() = schemes.map { it.schemaId }
    val primarySchemaId: String get() = schemes.first().schemaId
}

/**
 * Import caps; tests lower them to exercise the boundaries without allocating huge inputs.
 * [archiveBytes] bounds the compressed size of all archives of one package together: they are
 * downloaded and merged as one import, so several archives each under the cap must still not add up
 * past it.
 */
class SchemePackageLimits(
    val archiveBytes: Long = RecipeResourceBudget.ARCHIVE_FILE.toLong(),
    val entryCount: Int = SchemePackageImportBuilder.MAX_ENTRY_COUNT,
    val sourceFileBytes: Long = RecipeResourceBudget.SOURCE_FILE.toLong(),
    val sourceTotalBytes: Long = RecipeResourceBudget.SOURCE_TOTAL.toLong(),
    val modelFileBytes: Long = RecipeResourceBudget.MODEL_FILE.toLong(),
    val modelTotalBytes: Long = RecipeResourceBudget.MODEL_TOTAL.toLong(),
)

/**
 * Turns one user scheme ZIP into a self-contained Rime source on disk.
 *
 * Ported from the iOS `prepareSchemeArchive`/`prepareSchemeImport` pair. The archive is untrusted
 * input: entries are streamed to disk under the iOS caps (never buffered whole), every path is
 * checked before it is created, and every failure is a [SchemePackageImportError]. A GitHub-style
 * single root directory is unwrapped and Finder metadata is skipped, so a repository downloaded as
 * a ZIP imports as-is. The package keeps its own `default.yaml` and configuration; the app only
 * supplements what Rime needs and the package lacks (prelude presets, OpenCC data) through the
 * `supplement` callback, which never overwrites a package file.
 */
object SchemePackageImportBuilder {
    /** Prefix of every imported scheme package's source id (and learning namespace). */
    const val SOURCE_PREFIX: String = "scheme-"

    /** Largest accepted number of archive entries (files and directories), as on iOS. */
    const val MAX_ENTRY_COUNT: Int = 2000

    /** Longest accepted UTF-8 length of one path component (the Linux file-name limit). */
    private const val MAX_COMPONENT_BYTES = 255
    private const val MAX_PATH_BYTES = 1024
    private const val MAX_DEPTH = 32
    private const val MAX_DISPLAY_NAME_CODE_POINTS = 64
    private const val SCHEMA_SUFFIX = ".schema.yaml"
    private const val CUSTOM_SUFFIX = ".custom.yaml"
    private const val DEFAULT_CONFIG = "default.yaml"
    private const val BUFFER_BYTES = 64 * 1024
    private const val MAX_DICTIONARY_HEADER_BYTES = 4 * 1024 * 1024
    private const val IDENTITY_HEX = 16
    private const val REVISION_HEX = 40
    private val SCHEMA_ID = Regex("[A-Za-z0-9_.-]+")

    /** The `schema_list` block the app's prelude supplement writes into a generated default.yaml. */
    private val SCHEMA_LIST_BLOCK = Regex("(?m)^schema_list:\n(?:  - schema: [^\n]+\n)*")

    /**
     * Files Rime or the app may read from a source: configuration, dictionaries, Lua, OpenCC data,
     * grammar and prediction models, text tables and notices. Everything else in a repository
     * (images, CI workflows, installer scripts) is skipped rather than staged.
     */
    private val STAGED_EXTENSIONS =
        setOf("yaml", "yml", "txt", "json", "lua", "ocd2", "ocd", "gram", "db", "md", "csv", "tsv", "dict", "schema")

    /** Bundled input methods a package must not try to patch: they are not part of its source. */
    private val BUILTIN_CONFIG = Regex("""^(?:songyan_[A-Za-z0-9_]+|keyboard_[0-9a-f]+_[0-9]+)$""")

    /**
     * Checks [archive], extracts it into [target] (created, must not exist yet), lets [supplement]
     * add the app's shared files, then checks the result the way the engine will read it.
     */
    fun prepare(
        archive: File,
        target: File,
        supplement: (File) -> Unit = {},
        limits: SchemePackageLimits = SchemePackageLimits(),
        cancelled: () -> Boolean = { false },
    ): PreparedSchemeImport =
        prepareArchives(
            archives = listOf(archive),
            target = target,
            supplement = supplement,
            limits = limits,
            cancelled = cancelled,
        )

    /**
     * Turns the pinned upstream archives of one catalogue entry into a single Rime source. The first
     * archive is the entry package: its files keep their package-relative paths, so its
     * default.yaml stays authoritative. Later archives contribute the files the entry depends on at
     * those same paths, because librime resolves a dependency by name at the source root.
     *
     * [advertisedSchemas] names the input methods the entry offers. A package such as rime-bopomofo
     * ships several root schemas (its own and its reverse-lookup tables) and no default.yaml; without
     * the declaration every one of them would be offered as an input method. The package's own
     * default.yaml and default.custom.yaml stay authoritative; when they select anything but the
     * advertised list the import is refused rather than silently overriding the catalogue.
     */
    fun prepareArchives(
        archives: List<File>,
        target: File,
        supplement: (File) -> Unit = {},
        limits: SchemePackageLimits = SchemePackageLimits(),
        advertisedSchemas: List<String> = emptyList(),
        cancelled: () -> Boolean = { false },
    ): PreparedSchemeImport {
        if (archives.isEmpty()) fail(SchemePackageImportError.Reason.INVALID_ZIP, "no archive")
        val extracted = extractArchives(archives, target, limits, cancelled)
        for (id in advertisedSchemas) {
            if (!SCHEMA_ID.matches(id) || !File(target, id + SCHEMA_SUFFIX).isFile) {
                fail(SchemePackageImportError.Reason.MISSING_SCHEMA, "$id$SCHEMA_SUFFIX is advertised but not shipped")
            }
        }
        // A package that ships its own default.yaml decides its input methods itself.
        val ownConfiguration = File(target, DEFAULT_CONFIG).isFile
        supplement(target)
        if (advertisedSchemas.isNotEmpty() && !ownConfiguration) {
            writeAdvertisedSchemas(target, advertisedSchemas)
        }
        val prepared = inspect(target, extracted)
        if (advertisedSchemas.isNotEmpty() && prepared.schemaIds != advertisedSchemas) {
            // An existing default.yaml or default.custom.yaml may patch the list the generation just
            // wrote. Deleting the author's patch would change the package; refusing keeps it visible.
            fail(
                SchemePackageImportError.Reason.INVALID_SCHEMA,
                "the package configuration enables " + prepared.schemaIds +
                    " but the entry advertises " + advertisedSchemas,
            )
        }
        return prepared
    }

    private class Extracted(
        val identity: String,
        val skipped: List<String>,
    )

    private class Entry(
        val zip: ZipEntry,
        val path: String,
    )

    /** What fills one package-relative path: the archive that provided it and the digest of its bytes. */
    private class Claim(
        val label: String,
        val digest: String,
    )

    /** Where documentation a later archive also ships is kept without losing either copy. */
    private const val ATTRIBUTION_ROOT = "source-repositories"

    /**
     * Name prefix of the scratch directory entries are compared in. It is created beside the package
     * tree, never inside it: a package may legitimately ship a top-level `.merge/` directory, and
     * cleaning the scratch must not swallow the package's own files.
     */
    private const val MERGE_SCRATCH_PREFIX = ".merge-"

    /**
     * Extracts [archives] into [target] as one package. The entry archive's files keep their
     * package-relative paths; a later archive's file lands at the same path unless that would lose
     * one of the copies — identical bytes are one file, documentation moves under that archive's
     * attribution path, and a runtime file two archives fill differently is refused.
     */
    private fun extractArchives(
        archives: List<File>,
        target: File,
        limits: SchemePackageLimits,
        cancelled: () -> Boolean,
    ): Extracted {
        val canonicalTarget = target.canonicalFile
        if (!canonicalTarget.mkdirs() && !canonicalTarget.isDirectory) {
            throw IOException("cannot create $target")
        }
        val claims = HashMap<String, Claim>()
        val identities = sortedMapOf<String, String>()
        val skipped = LinkedHashSet<String>()
        val totals = longArrayOf(0L, 0L)
        val buffer = ByteArray(BUFFER_BYTES)
        val scratch = createScratch(canonicalTarget)
        try {
            var archiveBytes = 0L
            for ((index, archive) in archives.withIndex()) {
                if (cancelled()) throw InterruptedException("import cancelled")
                archiveBytes = validateArchive(archive, limits, archiveBytes)
                openZip(archive).use { zip ->
                    val entries = listEntries(zip, limits)
                    // One enclosing directory (a GitHub "Download ZIP") is the package root;
                    // classification uses the package-relative path while subdirectories are kept.
                    val wrapper = wrapperOf(entries)
                    val label = archiveLabel(archive, wrapper, index)
                    for (entry in entries) {
                        if (cancelled()) throw InterruptedException("import cancelled")
                        val logical =
                            if (wrapper != null) entry.path.removePrefix(wrapper).removePrefix("/") else entry.path
                        if (entry.zip.isDirectory || logical.isEmpty()) continue
                        if (logical.endsWith(".gram")) validateModelPath(logical)
                        if (!isStaged(logical)) {
                            skipped += logical
                            continue
                        }
                        if (index > 0 && logical.equals(DEFAULT_CONFIG, ignoreCase = true)) {
                            // The entry archive owns the package's configuration; a dependency's
                            // default.yaml lists that upstream's input methods, not this package's.
                            continue
                        }
                        val model = RecipeResourceBudget.isModel(logical)
                        val fileCap = if (model) limits.modelFileBytes else limits.sourceFileBytes
                        if (entry.zip.size > fileCap) {
                            fail(
                                SchemePackageImportError.Reason.TOO_LARGE,
                                logical + " exceeds " + fileCap + " bytes",
                            )
                        }
                        val staged = File(scratch, "$index/$logical")
                        val digest =
                            writeEntry(
                                zip = zip,
                                entry = entry.zip,
                                logical = logical,
                                destination = staged,
                                fileCap = fileCap,
                                model = model,
                                limits = limits,
                                totals = totals,
                                buffer = buffer,
                                cancelled = cancelled,
                            )
                        val owner = claims[logical.lowercase()]
                        val claimedPath =
                            when {
                                owner == null -> logical
                                owner.digest == digest -> null
                                SchemeSourceCheck.isRuntimeSourceFile(logical) ->
                                    throw SchemePackageImportError(
                                        SchemePackageImportError.Reason.CONFLICTING_FILE,
                                        "$logical differs between " + owner.label + " and " + label,
                                        listOf(logical),
                                    )

                                else -> attributionPath(logical, label, claims)
                            }
                        if (claimedPath == null) {
                            staged.delete()
                            continue
                        }
                        val destination = File(canonicalTarget, claimedPath)
                        if (!destination.canonicalPath.startsWith(canonicalTarget.path + File.separator)) {
                            fail(SchemePackageImportError.Reason.UNSAFE_PATH, claimedPath)
                        }
                        destination.parentFile?.mkdirs()
                        if (!staged.renameTo(destination)) {
                            staged.copyTo(destination, overwrite = true)
                            staged.delete()
                        }
                        claims[claimedPath.lowercase()] = Claim(label, digest)
                        identities[claimedPath] = digest
                    }
                }
            }
        } finally {
            scratch.deleteRecursively()
        }
        val identity = identities.entries.joinToString("\n") { it.key + it.value }
        return Extracted(
            identity = sha256Hex(identity.toByteArray(StandardCharsets.UTF_8)).take(IDENTITY_HEX),
            skipped = skipped.toList(),
        )
    }

    /**
     * Creates the scratch directory entries are compared in, beside the package tree. [target] has
     * just been created, so its parent exists; the directory is removed in [extractArchives]'s
     * finally block like any other temporary.
     */
    private fun createScratch(target: File): File {
        val parent = requireNotNull(target.parentFile) { "package target has no parent: $target" }
        return Files.createTempDirectory(parent.toPath(), MERGE_SCRATCH_PREFIX).toFile()
    }

    /**
     * Checks an archive before it is opened: present, within the archive budget, and a ZIP. Returns
     * the compressed bytes of the archives checked so far: the cap covers all of them together, not
     * each one on its own.
     */
    private fun validateArchive(
        archive: File,
        limits: SchemePackageLimits,
        alreadyBytes: Long,
    ): Long {
        if (!archive.isFile) fail(SchemePackageImportError.Reason.INVALID_ZIP, "missing archive")
        val length = archive.length()
        if (length > limits.archiveBytes) {
            fail(SchemePackageImportError.Reason.TOO_LARGE, "archive is " + length + " bytes")
        }
        val total = alreadyBytes + length
        if (total > limits.archiveBytes) {
            fail(SchemePackageImportError.Reason.TOO_LARGE, "archives total " + total + " bytes")
        }
        if (!hasZipMagic(archive)) fail(SchemePackageImportError.Reason.INVALID_ZIP, "not a ZIP archive")
        return total
    }

    private fun openZip(archive: File): ZipFile =
        try {
            ZipFile(archive, StandardCharsets.UTF_8)
        } catch (error: ZipException) {
            // The JDK refuses a central directory whose names are not UTF-8 while opening. If the
            // same archive reads as GBK, the user needs to re-zip it, not find another package.
            if (opensAsGbk(archive)) {
                fail(SchemePackageImportError.Reason.UNSUPPORTED_NAME_ENCODING, error.message ?: "non-UTF-8 names")
            }
            fail(SchemePackageImportError.Reason.INVALID_ZIP, error.message ?: "corrupt archive")
        } catch (error: IllegalArgumentException) {
            fail(SchemePackageImportError.Reason.UNSUPPORTED_NAME_ENCODING, error.message ?: "malformed entry name")
        } catch (error: IOException) {
            fail(SchemePackageImportError.Reason.INVALID_ZIP, error.message ?: "unreadable archive")
        }

    /** The single enclosing directory an archive wraps its files in, or null. */
    private fun wrapperOf(entries: List<Entry>): String? {
        val roots = entries.map { it.path.substringBefore('/') }.toSet()
        return roots.singleOrNull()?.takeIf { root ->
            entries.all { it.zip.isDirectory || it.path.contains('/') } && root.isNotEmpty()
        }
    }

    /** A safe single path segment naming the archive a documentation file came from. */
    private fun archiveLabel(
        archive: File,
        wrapper: String?,
        index: Int,
    ): String {
        val raw = wrapper ?: archive.name.substringBeforeLast('.')
        val cleaned =
            raw
                .map { character -> if (character.isLetterOrDigit() || character in "-_.") character else '-' }
                .joinToString("")
                .trim('-', '.')
        return cleaned.ifEmpty { "archive-" + (index + 1) }
    }

    /** Where one archive's copy of a shared documentation file goes, without either copy winning. */
    private fun attributionPath(
        logical: String,
        label: String,
        claims: Map<String, Claim>,
    ): String {
        var candidate = "$ATTRIBUTION_ROOT/$label/$logical"
        var attempt = 2
        while (claims.containsKey(candidate.lowercase())) {
            candidate = "$ATTRIBUTION_ROOT/$label-$attempt/$logical"
            attempt++
        }
        return candidate
    }

    /**
     * Streams one archive entry into [destination], hashing it on the way. Actual bytes count,
     * never the declared size: a lying header must not let an entry expand past its budget.
     */
    private fun writeEntry(
        zip: ZipFile,
        entry: ZipEntry,
        logical: String,
        destination: File,
        fileCap: Long,
        model: Boolean,
        limits: SchemePackageLimits,
        totals: LongArray,
        buffer: ByteArray,
        cancelled: () -> Boolean,
    ): String {
        destination.parentFile?.mkdirs()
        val digest = MessageDigest.getInstance("SHA-256")
        var written = 0L
        try {
            zip.getInputStream(entry).use { input ->
                FileOutputStream(destination).use { output ->
                    while (true) {
                        if (cancelled()) throw InterruptedException("import cancelled")
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        written += read
                        if (written > fileCap) {
                            fail(
                                SchemePackageImportError.Reason.TOO_LARGE,
                                logical + " exceeds " + fileCap + " bytes",
                            )
                        }
                        totals[if (model) 1 else 0] += read
                        if (totals[0] > limits.sourceTotalBytes || totals[1] > limits.modelTotalBytes) {
                            fail(
                                SchemePackageImportError.Reason.TOO_LARGE,
                                "expanded package exceeds its total budget",
                            )
                        }
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }
        } catch (error: ZipException) {
            fail(
                SchemePackageImportError.Reason.INVALID_ZIP,
                logical + ": " + (error.message ?: "corrupt entry"),
            )
        }
        if (model && written == 0L) fail(SchemePackageImportError.Reason.INVALID_MODEL, "$logical is empty")
        return hex(digest.digest())
    }

    /**
     * Writes the input methods a catalogue entry offers into its configuration, when the package
     * ships none: the app's prelude supplement would otherwise list every root schema the merged
     * archives contain, offering reverse-lookup tables and dependency schemas as input methods.
     */
    private fun writeAdvertisedSchemas(
        target: File,
        advertised: List<String>,
    ) {
        val file = File(target, DEFAULT_CONFIG)
        val text = if (file.isFile) file.readText() else ""
        val block = "schema_list:\n" + advertised.joinToString("") { "  - schema: $it\n" }
        val replaced = SCHEMA_LIST_BLOCK.replace(text) { block }
        val written =
            when {
                replaced != text -> replaced
                text.isBlank() -> "config_version: \"1\"\n" + block
                else -> fail(SchemePackageImportError.Reason.INVALID_SCHEMA, "$DEFAULT_CONFIG has no schema_list")
            }
        file.writeText(written)
    }

    /** Validates every entry name before anything touches the disk. */
    private fun listEntries(
        zip: ZipFile,
        limits: SchemePackageLimits,
    ): List<Entry> {
        val result = ArrayList<Entry>()
        val seen = HashSet<String>()
        try {
            val enumeration = zip.entries()
            while (enumeration.hasMoreElements()) {
                val entry = enumeration.nextElement()
                val raw = entry.name ?: fail(SchemePackageImportError.Reason.INVALID_ZIP, "entry without a name")
                val path = raw.removeSuffix("/")
                if (path.isEmpty() || SchemeSourceDependencies.isFinderMetadata(path)) continue
                validatePath(path)
                // Learned-word snapshots are not source files; the app stages them from the archive
                // on its own and offers to merge them.
                if (!entry.isDirectory && isLearningSnapshot(path)) continue
                if (path.split('/').any { it.endsWith(".userdb") || it.contains(".userdb.") }) {
                    fail(SchemePackageImportError.Reason.USER_DATA, path)
                }
                if (!seen.add(path.lowercase())) fail(SchemePackageImportError.Reason.DUPLICATE_PATH, path)
                if (seen.size > limits.entryCount) {
                    fail(
                        SchemePackageImportError.Reason.TOO_MANY_ENTRIES,
                        "more than " + limits.entryCount + " entries",
                    )
                }
                result += Entry(entry, path)
            }
        } catch (error: IllegalArgumentException) {
            // ZipFile decodes names lazily and reports non-UTF-8 bytes as a malformed argument.
            fail(SchemePackageImportError.Reason.UNSUPPORTED_NAME_ENCODING, error.message ?: "malformed entry name")
        }
        return result
    }

    /** A librime `*.userdb.txt` text snapshot: the file itself, with no `.userdb` directory above it. */
    internal fun isLearningSnapshot(path: String): Boolean {
        val parts = path.split('/')
        return parts.last().lowercase().endsWith(".userdb.txt") &&
            parts.dropLast(1).none { it.endsWith(".userdb") || it.contains(".userdb.") }
    }

    /**
     * The iOS path rule (any Unicode name, spaces included) plus what an Android file system can
     * hold: bounded component and path lengths and a bounded depth.
     */
    internal fun validatePath(path: String) {
        val bytes = path.toByteArray(StandardCharsets.UTF_8).size
        val components = path.split('/')
        val unsafe =
            !SchemeSourceCheck.safe(path) ||
                bytes > MAX_PATH_BYTES ||
                components.size > MAX_DEPTH ||
                components.any { it.toByteArray(StandardCharsets.UTF_8).size > MAX_COMPONENT_BYTES }
        if (unsafe) fail(SchemePackageImportError.Reason.UNSAFE_PATH, path)
    }

    private fun isStaged(path: String): Boolean {
        val components = path.split('/')
        if (components.first() == ".git") return false
        val name = components.last()
        if (components.first() == "build") {
            // Only native dictionaries and spelling indexes can seed a prebuilt deployment.
            return components.size == 2 &&
                listOf(".table.bin", ".reverse.bin", ".prism.bin").any {
                    name.endsWith(it) && name.length > it.length
                }
        }
        if (SchemeSourceCheck.isNotice(name)) return true
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        val extension = name.substring(dot + 1).lowercase()
        if (extension == "db") return Prediction.isDatabasePath(path)
        return extension in STAGED_EXTENSIONS
    }

    /** iOS `GrammarModelPath.isValid`: a `.gram` file with a real stem under plain components. */
    private fun validateModelPath(path: String) {
        val name = path.substringAfterLast('/')
        if (!path.endsWith(".gram") || name.length <= ".gram".length) {
            fail(SchemePackageImportError.Reason.INVALID_MODEL, path)
        }
    }

    private fun inspect(
        root: File,
        extracted: Extracted,
    ): PreparedSchemeImport {
        val files = sortedMapOf<String, File>()
        collectFiles(root, "", files)
        val dependencies = SchemeSourceDependencies(files.keys) { path -> files[path]?.let(::readConfig) }
        val rootSchemas = files.keys.filter { !it.contains('/') && it.endsWith(SCHEMA_SUFFIX) }
        if (rootSchemas.isEmpty()) {
            fail(SchemePackageImportError.Reason.MISSING_SCHEMA, "no $SCHEMA_SUFFIX file at the package root")
        }
        val listed =
            try {
                dependencies.listedSchemaIds()
            } catch (error: SchemeSourceDependencies.Invalid) {
                fail(invalidReason(error), error.message ?: error.path)
            }
        // The package's own default.yaml decides what the keyboard offers: auxiliary schemes such as
        // a reverse-lookup table must stay loadable but are not input methods of their own.
        val selectable =
            listed?.filter { (it + SCHEMA_SUFFIX) in files }?.takeIf { it.isNotEmpty() }
                ?: rootSchemas.map { it.removeSuffix(SCHEMA_SUFFIX) }.sorted()
        for (path in files.keys) {
            if (!path.contains('/') && path.endsWith(CUSTOM_SUFFIX)) {
                val target = path.removeSuffix(CUSTOM_SUFFIX)
                if (BUILTIN_CONFIG.matches(target) && (target + SCHEMA_SUFFIX) !in files) {
                    fail(SchemePackageImportError.Reason.BUILTIN_CUSTOMIZATION, path)
                }
            }
        }
        val schemes =
            selectable.map { id ->
                if (!SCHEMA_ID.matches(
                        id,
                    )
                ) {
                    fail(SchemePackageImportError.Reason.INVALID_SCHEMA, "$id is not a usable id")
                }
                val path = id + SCHEMA_SUFFIX
                val text =
                    decodeUtf8(files.getValue(path).readBytes())
                        ?: fail(SchemePackageImportError.Reason.INVALID_ENCODING, "$path is not UTF-8")
                val schema =
                    try {
                        BoundedYaml.load(text).asStringMap()
                    } catch (error: Exception) {
                        fail(
                            SchemePackageImportError.Reason.INVALID_SCHEMA,
                            path + ": " + (error.message ?: "YAML error"),
                        )
                    } ?: fail(SchemePackageImportError.Reason.INVALID_SCHEMA, "$path is not a YAML mapping")
                val declared = schemaField(schema, "schema_id")
                if (declared != id) {
                    fail(
                        SchemePackageImportError.Reason.INVALID_SCHEMA,
                        path + " declares schema_id " + (declared ?: "<none>"),
                    )
                }
                ImportedScheme(id, displayName(schemaField(schema, "name"), id))
            }
        val missing =
            try {
                dependencies.missing()
            } catch (error: SchemeSourceDependencies.Invalid) {
                fail(invalidReason(error), error.message ?: error.path)
            }
        if (missing.isNotEmpty()) {
            throw SchemePackageImportError(
                SchemePackageImportError.Reason.MISSING_DEPENDENCY,
                "missing: " + missing.joinToString(", "),
                missing,
            )
        }
        val records = files.entries.joinToString("") { (path, file) -> path + file.sha256() }
        return PreparedSchemeImport(
            root = root,
            schemes = schemes,
            displayName = schemes.first().name,
            packageId = SOURCE_PREFIX + extracted.identity,
            revision = sha256Hex(records.toByteArray(StandardCharsets.UTF_8)).take(REVISION_HEX),
            skippedFiles = extracted.skipped,
        )
    }

    private fun invalidReason(error: SchemeSourceDependencies.Invalid): SchemePackageImportError.Reason =
        when {
            error.encoding -> SchemePackageImportError.Reason.INVALID_ENCODING
            error.path.endsWith(SCHEMA_SUFFIX) -> SchemePackageImportError.Reason.INVALID_SCHEMA
            error.message?.startsWith("unsafe dependency path") == true -> SchemePackageImportError.Reason.UNSAFE_PATH
            else -> SchemePackageImportError.Reason.INVALID_SCHEMA
        }

    private fun collectFiles(
        directory: File,
        prefix: String,
        into: MutableMap<String, File>,
    ) {
        for (child in directory.listFiles().orEmpty()) {
            val path = prefix + child.name
            if (child.isDirectory) collectFiles(child, "$path/", into) else into[path] = child
        }
    }

    /** Hashes a staged file in chunks; model files can be hundreds of megabytes. */
    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(BUFFER_BYTES)
        inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return hex(digest.digest())
    }

    /**
     * A config file's bytes for the dependency scan. Only the YAML header of a dictionary is read:
     * the rows after `...` are tabular data and can be a hundred megabytes.
     */
    private fun readConfig(file: File): ByteArray {
        if (!file.name.endsWith(SchemeSourceDependencies.DICTIONARY_SUFFIX)) return file.readBytes()
        val header = ByteArrayOutputStream()
        file.inputStream().buffered().use { input ->
            val line = ByteArrayOutputStream()
            while (header.size() < MAX_DICTIONARY_HEADER_BYTES) {
                val next = input.read()
                if (next < 0) break
                line.write(next)
                if (next == '\n'.code) {
                    val text = line.toString(StandardCharsets.UTF_8.name()).trim()
                    header.write(line.toByteArray())
                    line.reset()
                    if (text == "..." || text.startsWith("... ") || text.startsWith("...#")) return header.toByteArray()
                }
            }
            header.write(line.toByteArray())
        }
        return header.toByteArray()
    }

    private fun opensAsGbk(archive: File): Boolean =
        try {
            ZipFile(archive, Charset.forName("GBK")).use { zip -> zip.entries().hasMoreElements() }
        } catch (_: Exception) {
            false
        }

    private fun hasZipMagic(file: File): Boolean =
        file.inputStream().use { input: InputStream ->
            val header = ByteArray(2)
            input.read(header) == 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
        }

    /** The scheme's own mapping first, then the top level, so both Rime spellings are accepted. */
    private fun schemaField(
        root: Map<String, Any?>,
        key: String,
    ): String? = (root["schema"].asStringMap()?.get(key) as? String) ?: (root[key] as? String)

    /** The scheme's own name, unless it is blank, oversized or carries control characters. */
    private fun displayName(
        raw: String?,
        fallback: String,
    ): String {
        val name = raw?.trim() ?: return fallback
        if (name.isEmpty() || name.codePointCount(0, name.length) > MAX_DISPLAY_NAME_CODE_POINTS) return fallback
        if (name.any { Character.isISOControl(it) }) return fallback
        return name
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private fun fail(
        reason: SchemePackageImportError.Reason,
        detail: String,
    ): Nothing = throw SchemePackageImportError(reason, detail)
}
