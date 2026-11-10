package app.songyan.ime.rime

import android.content.res.AssetManager
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.packages.SchemePackageImportBuilder
import java.io.File

/**
 * The real traditional-Chinese scheme data for on-device replay.
 *
 * The pinned upstream archives are not part of the repository: `scripts/fetch-test-schemes.sh`
 * downloads them into the checkout and the fixture step streams them into this test package's own
 * cache (the test app runs under its own uid and cannot read the app's deploy cache). Each catalogue
 * entry is then merged the way the app imports it, through the production
 * [SchemePackageImportBuilder], and deployed by the real librime.
 */
internal object TraditionalSchemeFixtures {
    /** Instrumentation argument naming the directory the pinned archives were pushed into. */
    const val ARCHIVES_ARGUMENT = "traditionalArchivesDir"

    /** The archives' default directory: this test package's cache, where the fixture step pushes them. */
    private const val CACHE_DIRECTORY = "songyan-test-schemes"

    /** The bundled prelude files and OpenCC data that the app's installer adds beside an imported source. */
    private const val PRELUDE_ASSET_ROOT = "rime/prelude"
    private const val OPENCC_ASSET_ROOT = "rime/opencc"

    private const val SCHEMA_SUFFIX = ".schema.yaml"

    /**
     * One catalogue entry, as the settings screen presents it: the pinned repositories in the order
     * the app merges them (the entry package first, the dependencies after) and the input methods it
     * advertises.
     */
    class Entry(
        val id: String,
        val schemas: List<String>,
        val packages: List<String>,
    ) {
        /** The primary input method: the one the deployed default configuration selects. */
        val primarySchema: String get() = schemas.first()
    }

    val BOPOMOFO =
        Entry(
            id = "rime-bopomofo",
            schemas = listOf("bopomofo_tw", "bopomofo"),
            packages =
                listOf(
                    "rime-bopomofo",
                    "rime-terra-pinyin",
                    "rime-essay",
                    "rime-stroke",
                    "rime-luna-pinyin",
                ),
        )
    val CANGJIE =
        Entry(
            id = "rime-cangjie",
            schemas = listOf("cangjie5"),
            packages = listOf("rime-cangjie", "rime-luna-pinyin", "rime-essay", "rime-stroke"),
        )
    val QUICK =
        Entry(
            id = "rime-quick",
            schemas = listOf("quick5"),
            packages =
                listOf(
                    "rime-quick",
                    "rime-cangjie",
                    "rime-luna-pinyin",
                    "rime-essay",
                    "rime-stroke",
                ),
        )
    val CANTONESE =
        Entry(
            id = "rime-cantonese",
            schemas = listOf("jyut6ping3"),
            packages =
                listOf(
                    "rime-cantonese",
                    "rime-luna-pinyin",
                    "rime-stroke",
                    "rime-cangjie",
                    "rime-loengfan",
                    "rime-emoji-cantonese",
                    "rime-essay",
                ),
        )

    val entries: List<Entry> = listOf(BOPOMOFO, CANGJIE, QUICK, CANTONESE)

    /** The entry whose advertised input methods include [schemaId]. */
    fun entryForSchema(schemaId: String): Entry? = entries.firstOrNull { schemaId in it.schemas }

    /** Where the fixture step pushed the pinned archives. */
    fun archivesDirectory(): File =
        InstrumentationRegistry
            .getArguments()
            .getString(ARCHIVES_ARGUMENT)
            ?.let(::File)
            ?: File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, CACHE_DIRECTORY)

    /** The repositories of [entry] whose pinned archive is not in [archivesDirectory]; empty when ready. */
    fun missingArchives(entry: Entry): List<String> {
        val directory = archivesDirectory()
        return entry.packages.filter { archiveFor(directory, it) == null }
    }

    /**
     * Fails with the fixture instruction when [entry]'s archives were not pushed. A replay test that
     * cannot read the real data fails; it never skips into a green result without replaying.
     */
    fun requireProvisioned(entry: Entry) {
        val missing = missingArchives(entry)
        check(missing.isEmpty()) {
            entry.id +
                " needs " +
                missing.joinToString(", ") +
                " in " +
                archivesDirectory() +
                "; run scripts/fetch-test-schemes.sh --check and push the pinned archives " +
                "into this test package's cache first"
        }
    }

    /** A deployed entry: the merged Rime source and the build librime compiled from it. */
    class Deployed(
        val entry: Entry,
        val source: File,
        val compiled: File,
    )

    /**
     * Merges the entry's pinned archives with the production importer and deploys the result with the
     * real engine. [root] is a scratch directory owned by the caller; it is created if needed.
     */
    suspend fun deploy(
        entry: Entry,
        root: File,
    ): Deployed {
        val archives = entry.packages.map { repo -> archiveFor(archivesDirectory(), repo) ?: missing(entry, repo) }
        root.mkdirs()
        val source = File(root, "source")
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val prepared =
            SchemePackageImportBuilder.prepareArchives(
                archives = archives,
                target = source,
                supplement = { supplement(assets, it) },
                advertisedSchemas = entry.schemas,
            )
        val deployment =
            RimeDeployment.deploy(
                sharedDataDir = prepared.root,
                userDataDir = File(root, "deploy"),
                appName = RimeEngine.DEFAULT_APP_NAME,
                appVersion = "test",
            )
        check(deployment.success) { "${entry.id} deployment failed: ${deployment.lastError}" }
        check(entry.primarySchema in deployment.schemaIds) {
            "${entry.id} deployed ${deployment.schemaIds}, expected ${entry.primarySchema}"
        }
        return Deployed(entry, prepared.root, deployment.compiledDir)
    }

    /**
     * The single archive whose pinned file name starts with [repo] + "-". The digest suffix changes
     * with the pin, the repository name does not.
     */
    private fun archiveFor(
        directory: File,
        repo: String,
    ): File? =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(repo + "-") && it.name.endsWith(".zip") }
            .singleOrNull()

    private fun missing(
        entry: Entry,
        repo: String,
    ): Nothing =
        throw IllegalStateException(
            "no pinned archive for ${entry.id}/$repo in ${archivesDirectory()}; " +
                "push the archives into this test package's cache first",
        )

    /**
     * Adds what the app's installer normally adds beside an imported source: the prelude presets the
     * package does not ship and the bundled OpenCC data. The installer itself lives in the app
     * module, which a library's test package cannot depend on, so the same bundled files are copied
     * here; a package's own file always wins, exactly as in the installer.
     */
    private fun supplement(
        assets: AssetManager,
        source: File,
    ) {
        val schemas =
            source
                .listFiles()
                .orEmpty()
                .filter { it.isFile && it.name.endsWith(SCHEMA_SUFFIX) }
                .map { it.name.removeSuffix(SCHEMA_SUFFIX) }
                .sorted()
        for (name in PRELUDE_FILES) {
            val target = File(source, name)
            if (target.isFile) continue
            val bytes = assetBytes(assets, "${PRELUDE_ASSET_ROOT}/$name") ?: continue
            // The advertised list lands in default.yaml through the production importer afterwards;
            // the prelude's own schema_list is only the app's usual fallback.
            target.writeBytes(if (name == "default.yaml") withSchemaList(bytes, schemas) else bytes)
        }
        copyAssetTree(assets, OPENCC_ASSET_ROOT, File(source, "opencc"), keepExisting = true)
    }

    private val PRELUDE_FILES = listOf("default.yaml", "key_bindings.yaml", "punctuation.yaml", "symbols.yaml")

    private val SCHEMA_LIST_BLOCK = Regex("(?m)^schema_list:\n(?:  - schema: [^\n]+\n)*")

    private fun withSchemaList(
        bytes: ByteArray,
        schemas: List<String>,
    ): ByteArray {
        val text = bytes.toString(Charsets.UTF_8)
        val list =
            if (schemas.isEmpty()) {
                "schema_list: []\n"
            } else {
                "schema_list:\n" + schemas.joinToString("") { "  - schema: ${it}\n" }
            }
        return SCHEMA_LIST_BLOCK.replace(text) { list }.toByteArray(Charsets.UTF_8)
    }

    private fun assetBytes(
        assets: AssetManager,
        path: String,
    ): ByteArray? =
        try {
            assets.open(path).use { it.readBytes() }
        } catch (_: java.io.IOException) {
            null
        }

    private fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
        keepExisting: Boolean = false,
    ) {
        target.mkdirs()
        for (name in assets.list(assetPath).orEmpty()) {
            val childPath = "$assetPath/$name"
            val child = File(target, name)
            if (assets.list(childPath).orEmpty().isNotEmpty()) {
                copyAssetTree(assets, childPath, child, keepExisting)
            } else if (!keepExisting || !child.exists()) {
                assets.open(childPath).use { input -> child.outputStream().use { output -> input.copyTo(output) } }
            }
        }
    }
}
