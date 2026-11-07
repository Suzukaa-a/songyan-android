package app.songyan.ime.deploy

import app.songyan.ime.storage.PrebuiltResourceStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.CRC32

/**
 * Lets a deployment start from the compiled dictionaries of an earlier generation of the same
 * source instead of compiling every table again.
 *
 * librime decides itself whether a compiled file is still valid: a table, reverse lookup or prism
 * carries the checksum of the dictionary files (and, for a prism, of the compiled schema) it was
 * built from, and `DictCompiler` rebuilds any whose checksum differs. Seeding a work directory with
 * the previous files therefore never changes what is compiled, only how much of it is skipped.
 * Files of another librime build are never offered: the checksums do not cover the engine's own
 * table builder, which a patch or a new release may change.
 *
 * A package compiled on another machine ships its build and no dictionary sources, so librime has
 * nothing to checksum a table against and cannot notice that an older one is stale. Such a package
 * is seeded from its own `build` directory instead of from an earlier generation.
 *
 * The seeds are copies, not links: librime overwrites a compiled file in place when it rebuilds
 * it, and a link would let that write reach the published generation the keyboard has mapped.
 */
internal object CompiledReuse {
    /** The compiled files librime names after a dictionary and validates by checksum. */
    private const val PRISM_SUFFIX = ".prism.bin"
    private const val SCHEMA_SUFFIX = ".schema.yaml"
    private val DICTIONARY_SUFFIXES = listOf(".table.bin", ".reverse.bin", PRISM_SUFFIX)
    private const val DICTIONARY_SOURCE = ".dict.yaml"
    private const val CONFIG_SUFFIX = ".yaml"

    /** Where a package compiled elsewhere ships its build. */
    private const val PREBUILT_DIRECTORY = "build"

    /**
     * Copies into [targetBuild] the compiled files the deployment may reuse: the table, reverse
     * lookup and prism of every dictionary whose source sits at the top of [sourceDirs] (where
     * librime looks for it), every prism of [previousBuild] (an input method's prism is named after
     * the input method), and everything the sources ship prepacked under their own `build`
     * directory. A prebuilt file wins over [previousBuild]'s file of the same name, since only it
     * was compiled for the package's current revision. Returns the names of the copied files. A
     * file that cannot be copied is simply compiled again.
     */
    fun seed(
        previousBuild: File,
        sourceDirs: List<File>,
        targetBuild: File,
    ): List<String> {
        val prebuilt = prebuiltFiles(sourceDirs)
        if (!previousBuild.isDirectory && prebuilt.isEmpty()) return emptyList()
        val names = LinkedHashSet<String>()
        for (dictionary in dictionaryNames(sourceDirs)) {
            DICTIONARY_SUFFIXES.forEach { names += dictionary + it }
        }
        previousBuild
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(PRISM_SUFFIX) }
            .sortedBy { it.name }
            .forEach { names += it.name }
        names.addAll(prebuilt.keys)
        val copied = ArrayList<String>()
        for (name in names) {
            val source = prebuilt[name] ?: File(previousBuild, name)
            if (!source.isFile) continue
            val target = File(targetBuild, name)
            try {
                targetBuild.mkdirs()
                Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                copied += name
            } catch (_: IOException) {
                target.delete()
            }
        }
        return copied
    }

    /**
     * The source's own `build` directory when the package ships compiled dictionaries. A package
     * without dictionary sources leaves librime nothing to compile from, so those files are all a
     * deployment of it can start with.
     */
    fun prebuiltBuild(sourceDir: File): File? {
        PrebuiltResourceStore.restore(sourceDir)
        return File(sourceDir, PREBUILT_DIRECTORY).takeIf { build ->
            build.isDirectory && build.listFiles().orEmpty().any { it.isFile && isCompiledName(it.name) }
        }
    }

    /** The compiled files each source ships under its own `build`, with the directory each lies in. */
    private fun prebuiltFiles(sourceDirs: List<File>): Map<String, File> {
        val files = LinkedHashMap<String, File>()
        for (sourceDir in sourceDirs) {
            val build = File(sourceDir, PREBUILT_DIRECTORY).takeIf { it.isDirectory } ?: continue
            build
                .listFiles()
                .orEmpty()
                .filter { it.isFile && isCompiledName(it.name) }
                .sortedBy { it.name }
                .forEach { file -> if (file.name !in files) files[file.name] = file }
        }
        return files
    }

    /** Whether [name] is one of the files librime itself names and validates as compiled data. */
    private fun isCompiledName(name: String): Boolean =
        DICTIONARY_SUFFIXES.any { name.endsWith(it) && name.length > it.length }

    /**
     * Deletes the seeded prisms no compiled schema in [build] mentions, so the prisms of input
     * methods a source no longer has are not carried from generation to generation. Matching any
     * mention, not only a `prism:` or `dictionary:` key, can keep a stray file but never drops one
     * that is used.
     */
    fun dropUnusedPrisms(
        build: File,
        seeded: List<String>,
    ) {
        val prisms = seeded.filter { it.endsWith(PRISM_SUFFIX) }
        if (prisms.isEmpty()) return
        val schemas =
            build
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(SCHEMA_SUFFIX) }
                .map { runCatching { it.readText() }.getOrDefault("") }
        for (name in prisms) {
            val prism = name.removeSuffix(PRISM_SUFFIX)
            if (schemas.none { it.contains(prism) }) File(build, name).delete()
        }
    }

    /** The dictionaries whose sources sit at the top of [dirs], where librime looks for them. */
    fun dictionaryNames(dirs: List<File>): Set<String> =
        dirs
            .flatMap { it.listFiles().orEmpty().asList() }
            .filter { it.isFile && it.name.endsWith(DICTIONARY_SOURCE) }
            .map { it.name.removeSuffix(DICTIONARY_SOURCE) }
            .filter { it.isNotEmpty() }
            .toSortedSet()

    /**
     * Sets the modification time of every configuration file under [tree] from its content.
     *
     * librime records the modification time of each file a compiled schema was built from inside
     * that schema, and a prism is only reused while the compiled schema is byte for byte the same.
     * A source tree extracted again from the same package would otherwise get new times, and with
     * them a rebuild of every prism, the slowest of which takes seconds. Equal content now means
     * equal times; changed content still means a changed time, so librime's own up-to-date check
     * stays correct. Dictionary sources are left alone: librime checks those by content.
     */
    fun stampConfigTimes(tree: File) {
        if (!tree.isDirectory) return
        tree
            .walkTopDown()
            .filter { it.isFile && it.name.endsWith(CONFIG_SUFFIX) && !it.name.endsWith(DICTIONARY_SOURCE) }
            .forEach(::stampConfigTime)
    }

    /** [stampConfigTimes] for one file the deployment writes itself. */
    fun stampConfigTime(file: File) {
        runCatching { file.setLastModified(contentTime(file.readBytes())) }
    }

    /**
     * A time in whole seconds derived from [content]. librime stores the time as a 32-bit second
     * count, so the value stays well inside that range (between 2001 and 2018).
     */
    fun contentTime(content: ByteArray): Long {
        val crc = CRC32().apply { update(content) }.value
        return (CONTENT_TIME_BASE_SECONDS + crc % CONTENT_TIME_SPAN_SECONDS) * 1000L
    }

    private const val CONTENT_TIME_BASE_SECONDS = 1_000_000_000L
    private const val CONTENT_TIME_SPAN_SECONDS = 1L shl 29
}

/**
 * The work directory of a generation still waiting for the layouts of its other input methods,
 * kept so the completing deployment continues from it instead of compiling everything again.
 *
 * The marker names the generation that was published from the directory and the input methods
 * whose layouts it already holds; a directory without a matching marker is never reused.
 */
internal object PendingWork {
    const val MARKER = ".songyan-pending"

    fun write(
        workDir: File,
        generation: String,
        companionSchemas: List<String>,
    ) {
        File(workDir, MARKER).writeText((listOf(generation) + companionSchemas).joinToString("\n") + "\n")
    }

    /** The input methods with layouts, when [workDir] was left by [generation]; otherwise null. */
    fun read(
        workDir: File,
        generation: String,
    ): List<String>? {
        val lines =
            runCatching { File(workDir, MARKER).readLines() }
                .getOrNull()
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() } ?: return null
        if (lines.firstOrNull() != generation) return null
        return lines.drop(1)
    }

    fun clear(workDir: File) {
        File(workDir, MARKER).delete()
    }
}
