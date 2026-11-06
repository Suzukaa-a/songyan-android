package app.songyan.ime.rime

import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The locally compiled kagiroi package the Japanese on-device tests replay.
 *
 * The package is built on a machine that can run librime (nothing is compiled on the device) and
 * pushed as a ZIP into this test package's own cache; the instrumentation argument names it. The
 * archive carries the compiled build at its root and librime's compiled tables under `build/`.
 * This helper streams both out of the archive, puts the small compiled YAML where a prebuilt engine
 * looks for it, and hands the real engine the result.
 *
 * A missing argument or archive fails the test: a replay that cannot read the real package must
 * never turn green without replaying. Nothing is written outside this test package's cache.
 */
internal object JapaneseSchemeFixture {
    /** Instrumentation argument: absolute path of the pushed ZIP, inside this test package's cache. */
    const val ARG_FIXTURE = "kagiroiFixture"

    /** The input method the Japanese playback fixture replays. */
    const val SCHEMA = "kagiroi"

    /** Scratch root of the Japanese tests inside this test package's cache. */
    private const val CACHE_DIRECTORY = "japanese-playback"

    private const val BUILD_ENTRY = "build/"
    private const val DEFAULT_CONFIG = "default.yaml"
    private const val SCHEMA_SUFFIX = ".schema.yaml"
    private const val YAML_SUFFIX = ".yaml"
    private const val BIN_SUFFIX = ".bin"
    private const val BUFFER_BYTES = 1024 * 1024
    private const val HEADER_BYTES = 4096

    /** The extracted package: the read-only shared tree, the prebuilt build, and the test scratch. */
    class Prepared internal constructor(
        val root: File,
        val shared: File,
        val compiled: File,
        val files: Int,
        val expandedBytes: Long,
        val compiledTables: Int,
    ) {
        /** A user directory under the test scratch, kept for as long as the caller needs it. */
        fun userDir(name: String): File = File(root, name).also { it.mkdirs() }
    }

    /**
     * Extracts the pushed package and returns the directories the prebuilt engine opens.
     *
     * A missing fixture, an archive that is not a local librime compile, or compiled output from a
     * different librime fails here, before an engine is opened.
     */
    fun prepare(): Prepared {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val cache = instrumentation.targetContext.cacheDir
        val archive = requireFixture(cache)
        val root = File(cache, CACHE_DIRECTORY)
        if (root.exists() && !root.deleteRecursively()) {
            throw AssertionError("cannot clear the previous Japanese playback scratch: $root")
        }
        val shared = File(root, "shared")
        val compiled = File(root, "compiled")
        try {
            val extracted = extract(archive, shared, compiled)
            placeCompiledConfig(shared, compiled)
            validate(shared, compiled)
            println(
                "FIXTURE\t" + archive.name + "\tfiles=" + extracted.files +
                    "\texpandedBytes=" + extracted.bytes + "\tcompiledTables=" + extracted.bins,
            )
            return Prepared(root, shared, compiled, extracted.files, extracted.bytes, extracted.bins)
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    /** Opens the real engine on the prepared prebuilt build; no deployment runs in this mode. */
    suspend fun openEngine(
        prepared: Prepared,
        user: File,
    ): RimeEngine {
        if (!user.isDirectory && !user.mkdirs()) {
            throw AssertionError("cannot create the test user directory: $user")
        }
        return RimeEngine.createPrebuilt(
            sharedDataDir = prepared.shared,
            userDataDir = user,
            appName = RimeEngine.DEFAULT_APP_NAME,
            appVersion = "test",
            compiledDir = prepared.compiled,
        )
    }

    /** Selects kagiroi or fails naming the input methods the package does offer. */
    suspend fun selectKagiroi(
        engine: RimeEngine,
        schemaId: String = SCHEMA,
    ) {
        if (engine.selectSchema(schemaId)) return
        throw AssertionError(
            "the prebuilt kagiroi package cannot select '" + schemaId + "'; it offers " +
                engine.schemas().map { it.id },
        )
    }

    /** Removes this test's own scratch; the pushed ZIP belongs to the caller and is left alone. */
    fun cleanup(prepared: Prepared) {
        prepared.root.deleteRecursively()
    }

    private class Extracted(
        val files: Int,
        val bytes: Long,
        val bins: Int,
    )

    private fun requireFixture(cache: File): File {
        val raw =
            InstrumentationRegistry
                .getArguments()
                .getString(ARG_FIXTURE)
                ?: throw AssertionError(
                    "no kagiroi package was handed to this test; pass -e " + ARG_FIXTURE +
                        " <absolute path of the ZIP pushed into " + cache.path + ">. The test fails " +
                        "instead of skipping without replaying the real data",
                )
        val file = File(raw)
        if (!file.exists()) {
            throw AssertionError(
                "the kagiroi package does not exist: " + raw + "; push the locally compiled ZIP " +
                    "into " + cache.path + " first",
            )
        }
        val cacheRoot = canonical(cache)
        val resolved = canonical(file)
        if (!resolved.path.startsWith(cacheRoot.path + File.separator)) {
            throw AssertionError(
                "the kagiroi package must live inside this test package's cache (" + cache.path +
                    ") so the test reads the pushed artifact and nothing else: " + resolved,
            )
        }
        if (!file.isFile) throw AssertionError("the kagiroi package is not a file: $file")
        if (!hasZipMagic(file)) throw AssertionError("the kagiroi package is not a ZIP archive: $file")
        return file
    }

    private fun extract(
        archive: File,
        shared: File,
        compiled: File,
    ): Extracted {
        var files = 0
        var bytes = 0L
        var bins = 0
        ZipFile(archive).use { zip ->
            val entries =
                zip
                    .entries()
                    .asSequence()
                    .filter { !it.isDirectory }
                    .toList()
            if (entries.isEmpty()) throw AssertionError("the kagiroi package carries no files: $archive")
            val prefix = wrapperPrefix(entries.map { it.name })
            for (entry in entries) {
                val name = entry.name.removePrefix(prefix).removePrefix("/")
                if (name.isEmpty()) continue
                val relative = safeEntry(name)
                val inBuild = relative.startsWith(BUILD_ENTRY)
                val target =
                    if (inBuild) {
                        File(compiled, relative.removePrefix(BUILD_ENTRY))
                    } else {
                        File(shared, relative)
                    }
                val parent = target.parentFile
                if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
                    throw AssertionError("cannot create the kagiroi package directory: $parent")
                }
                // The compiled tables weigh about 174 MB; stream them and never hold one in the heap.
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, BUFFER_BYTES) }
                }
                files++
                bytes += target.length()
                if (inBuild && relative.endsWith(BIN_SUFFIX)) bins++
            }
        }
        return Extracted(files, bytes, bins)
    }

    /**
     * Copies the package's small compiled YAML into the prebuilt build directory, where librime's
     * deployed config resolver looks for `default` and the schemas. The tables stay where the
     * archive put them, so no large file is copied or parsed here.
     */
    private fun placeCompiledConfig(
        shared: File,
        compiled: File,
    ) {
        val configs = shared.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(YAML_SUFFIX) }
        if (configs.isEmpty()) {
            throw AssertionError("the kagiroi package has no compiled YAML at its root: $shared")
        }
        for (config in configs) {
            val target = File(compiled, config.name)
            if (target.isFile) continue
            config.inputStream().use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output, BUFFER_BYTES) }
            }
        }
    }

    private fun validate(
        shared: File,
        compiled: File,
    ) {
        if (!File(shared, DEFAULT_CONFIG).isFile) {
            throw AssertionError("the kagiroi package has no shared $DEFAULT_CONFIG: $shared")
        }
        if (!File(compiled, DEFAULT_CONFIG).isFile) {
            throw AssertionError(
                "the kagiroi package has no compiled $DEFAULT_CONFIG in its prebuilt build: $compiled",
            )
        }
        val schemas = compiled.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SCHEMA_SUFFIX) }
        if (schemas.isEmpty()) {
            throw AssertionError(
                "the kagiroi package has no compiled *$SCHEMA_SUFFIX in $compiled; a prebuilt " +
                    "engine cannot activate any input method from it",
            )
        }
        for (schema in schemas) {
            val header = readHeader(schema)
            if (!header.contains("__build_info:") || !header.contains(RimeEngine.EXPECTED_RIME_VERSION)) {
                throw AssertionError(
                    "the kagiroi package is not a prebuilt deployment compiled by librime " +
                        RimeEngine.EXPECTED_RIME_VERSION + " (" + schema.name + "); this test never " +
                        "compiles the kagiroi source on the device, so repackage the local compile",
                )
            }
        }
        val tables = compiled.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(BIN_SUFFIX) }
        if (tables.isEmpty()) {
            throw AssertionError(
                "the kagiroi package has no prebuilt build/*$BIN_SUFFIX in $compiled; without the " +
                    "locally compiled tables this test would have to compile on the device, which it " +
                    "never does",
            )
        }
        if (!File(shared, "lua").isDirectory) {
            throw AssertionError("the kagiroi package has no lua/ modules in $shared")
        }
        if (!File(shared, "opencc").isDirectory) {
            throw AssertionError("the kagiroi package has no opencc/ data in $shared")
        }
    }

    private fun readHeader(file: File): String =
        file.inputStream().use { stream ->
            val buffer = ByteArray(HEADER_BYTES)
            val read = stream.read(buffer)
            if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
        }

    /** The single enclosing directory a GitHub-style ZIP wraps everything in, or an empty string. */
    private fun wrapperPrefix(names: List<String>): String {
        val roots = names.map { it.substringBefore('/') }.toSet()
        val root = roots.singleOrNull() ?: return ""
        return if (names.all { it.contains('/') }) "$root/" else ""
    }

    private fun safeEntry(name: String): String {
        val normalized = name.replace('\\', '/')
        val unsafe =
            normalized.startsWith("/") || normalized.split('/').any { it.isEmpty() || it == ".." }
        if (unsafe) throw AssertionError("the kagiroi package has an unsafe entry: $name")
        return normalized
    }

    private fun canonical(file: File): File = runCatching { file.canonicalFile }.getOrDefault(file)

    private fun hasZipMagic(file: File): Boolean =
        try {
            file.inputStream().use { input ->
                val magic = ByteArray(2)
                input.read(magic) == 2 &&
                    magic[0] == 'P'.code.toByte() &&
                    magic[1] == 'K'.code.toByte()
            }
        } catch (_: IOException) {
            false
        }
}
