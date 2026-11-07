package app.songyan.ime.ime

import android.content.res.AssetManager
import android.os.SystemClock
import android.view.inputmethod.BaseInputConnection
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.packages.SchemePackageImportBuilder
import app.songyan.ime.rime.RimeDeployment
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeEngineBackend
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * The real traditional-Chinese scheme packages the keyboard tests type on.
 *
 * The pinned upstream archives are not part of the repository: the fixture step streams them into this
 * test package's cache (the keyboard test app runs under its own uid and cannot read the app's deploy
 * cache). An entry is merged the way the app imports it, through the production
 * [SchemePackageImportBuilder], deployed by the real librime and then opened through the same prebuilt
 * backend the shipped keyboard uses. A missing archive fails the test; it never turns green without
 * the real data.
 */
internal object TraditionalSchemeFixture {
    /** Instrumentation argument naming the directory the pinned archives were pushed into. */
    const val ARCHIVES_ARGUMENT = "traditionalArchivesDir"

    /** The archives' default directory: this test package's cache, where the fixture step pushes them. */
    private const val CACHE_DIRECTORY = "songyan-test-schemes"

    private const val PRELUDE_ASSET_ROOT = "rime/prelude"
    private const val OPENCC_ASSET_ROOT = "rime/opencc"
    private const val SCHEMA_SUFFIX = ".schema.yaml"

    /**
     * One catalogue entry: the pinned repositories in merge order (the entry package first, the
     * dependencies after) and the input method the entry offers.
     */
    class Entry(
        val id: String,
        val primarySchema: String,
        val schemas: List<String>,
        val packages: List<String>,
    )

    val BOPOMOFO =
        Entry(
            id = "rime-bopomofo",
            primarySchema = "bopomofo_tw",
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
            primarySchema = "cangjie5",
            schemas = listOf("cangjie5"),
            packages = listOf("rime-cangjie", "rime-luna-pinyin", "rime-essay", "rime-stroke"),
        )

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
     * Fails with the fixture instruction when [entry]'s archives were not pushed. The keyboard tests
     * run against the real data or fail; they never skip into a green result without typing.
     */
    fun requireProvisioned(entry: Entry) {
        val missing = missingArchives(entry)
        check(missing.isEmpty()) {
            "${entry.id} needs ${missing.joinToString(", ")} in ${archivesDirectory()}; " +
                "run scripts/fetch-test-schemes.sh --check and push the pinned archives " +
                "into this test package's cache first"
        }
    }

    /** A deployed entry: the merged Rime source and the build librime compiled from it. */
    class Deployment(
        val entry: Entry,
        val source: File,
        val compiled: File,
    ) {
        /**
         * A backend over the deployed package, with a fresh learning directory per test. The primary
         * input method is selected before the keyboard binds, because a fresh engine session has no
         * schema yet and the bundled template the pairing falls back to does not exist here.
         */
        suspend fun backend(name: String): EngineBackend {
            val user = File(source.parentFile, "$name-user-${SystemClock.uptimeMillis()}").also { it.mkdirs() }
            val backend =
                RimeEngineBackend.create(
                    sharedDataDir = source,
                    userDataDir = user,
                    compiledDir = compiled,
                    appName = RimeEngine.DEFAULT_APP_NAME,
                    appVersion = "test",
                )
            check(backend.availableSchemas.any { it.id == entry.primarySchema }) {
                "${entry.id} did not expose ${entry.primarySchema}: ${backend.availableSchemas.map { it.id }}"
            }
            backend.selectSchema(entry.primarySchema)
            return backend
        }
    }

    /**
     * Merges the entry's pinned archives with the production importer and deploys the result with the
     * real engine. [root] is a scratch directory owned by the caller.
     */
    suspend fun deploy(
        entry: Entry,
        root: File,
    ): Deployment {
        val directory = archivesDirectory()
        val archives =
            entry.packages.map { repo ->
                archiveFor(directory, repo) ?: throw IllegalStateException(
                    "no pinned archive for ${entry.id}/$repo in $directory; push the archives first",
                )
            }
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
        return Deployment(entry, prepared.root, deployment.compiledDir)
    }

    /** The single archive whose pinned file name starts with [repo] + "-": the pin changes, the name does not. */
    private fun archiveFor(
        directory: File,
        repo: String,
    ): File? =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.startsWith(repo + "-") && it.name.endsWith(".zip") }
            .singleOrNull()

    /**
     * Adds what the app's installer normally adds beside an imported source: the prelude presets the
     * package does not ship and the bundled OpenCC data. The installer lives in the app module, which a
     * library's test package cannot depend on, so the same bundled files are copied here; a package's
     * own file always wins.
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
            // the prelude's schema_list is only the app's usual fallback.
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

/** Feeds one engine key per character of [keys] through the keyboard's own key actions. */
internal fun FeatureHost.typeCodes(keys: String) {
    for (key in keys) tapAction(LayoutActionKind.CODE, key.toString())
}

/** Taps the candidate cell whose text is [text]; a cell label may carry the engine's comment. */
internal fun FeatureHost.tapCandidate(text: String) {
    val labels = barLabels()
    val label =
        requireNotNull(labels.firstOrNull { it == text } ?: labels.firstOrNull { it.startsWith("$text ") }) {
            "no candidate '$text' among $labels"
        }
    tap(label, CandidateBarView::class.java)
}

/** What a screen reader reads for the key that sends [code]: the key's own face, never the code. */
internal fun FeatureHost.spokenLabel(code: String): String =
    main2 {
        val view = find(KeyboardView::class.java)
        val index = view.keys.indexOfFirst { it.action.type == LayoutActionKind.CODE && it.action.value == code }
        require(index >= 0) { "no key sends '$code'" }
        val node = requireNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(index))
        (node.contentDescription ?: node.text ?: "").toString()
    }

/** The editor's marked range; when it covers the whole field the composition is not committed text. */
internal fun FeatureHost.composingSpan(): Pair<Int, Int> =
    main2 {
        BaseInputConnection.getComposingSpanStart(field.text) to
            BaseInputConnection.getComposingSpanEnd(field.text)
    }

/**
 * Opens one [FeatureHost] over a fresh backend of [deployment], runs [block] and shuts the backend
 * down; the deployment itself stays for the class.
 */
internal fun <T> withTraditionalHost(
    deployment: TraditionalSchemeFixture.Deployment,
    name: String,
    block: (FeatureHost) -> T,
): T {
    val backend = runBlocking { deployment.backend(name) }
    val host = FeatureHost(backend, layout = "qwerty26", schemaID = deployment.entry.primarySchema)
    try {
        return block(host)
    } finally {
        host.close()
        runBlocking { backend.shutdown() }
    }
}
