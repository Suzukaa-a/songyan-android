package app.songyan.ime.deploy

import app.songyan.ime.rime.FakeAssetTree
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.BuildStamp
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Shared fixtures for the deploy-side JVM tests: bundled assets, a fake engine, package archives. */
internal object DeployFixtures {
    val STAMP = BuildStamp(app = "1.0/1/100", engine = "1.17.0", data = "2")

    fun storage(): AppStorage =
        AppStorage(Files.createTempDirectory("files").toFile()).also { it.ensureBaseDirectories() }

    /** The shape of the bundled `rime/` assets: built-in package, prelude, OpenCC. */
    fun assets(quanpinMarker: String = ""): FakeAssetTree =
        FakeAssetTree().apply {
            put(
                "builtin/default.yaml",
                "config_version: \"1\"\nmenu:\n  page_size: 5\n" +
                    "schema_list:\n  - schema: songyan_quanpin\n  - schema: songyan_flypy\n",
            )
            put("builtin/manifest.yaml", "formatVersion: 1\nid: songyan-builtin\nkind: schema\n")
            put("builtin/songyan_basic.dict.yaml", "name: songyan_basic\n...\n你\tni\t1\n")
            put(
                "builtin/songyan_quanpin.schema.yaml",
                "schema:\n  schema_id: songyan_quanpin\n" +
                    "translator:\n  dictionary: songyan_basic\n  user_dict: songyan_shared\n" +
                    quanpinMarker,
            )
            put(
                "builtin/songyan_flypy.schema.yaml",
                "schema:\n  schema_id: songyan_flypy\n" +
                    "translator:\n  dictionary: songyan_basic\n  user_dict: songyan_shared\n",
            )
            put("prelude/default.yaml", "config_version: \"1\"\nschema_list:\n  - schema: placeholder\n")
            put("prelude/key_bindings.yaml", "key_bindings:\n")
            put("prelude/punctuation.yaml", "punctuation:\n")
            put("prelude/symbols.yaml", "symbols:\n")
            put("opencc/s2t.json", "{\"bundled\": true}\n")
            put("opencc/STCharacters.ocd2", "bundled")
        }

    /**
     * Pretends to be librime: compiles every root `*.schema.yaml` of the source into the build
     * directory, so a generation can be traced back to the source it was built from.
     */
    class FakeRunner : RimeDeployRunner {
        var calls = 0
        var succeed = true

        /** Fails a deploy whose work directory (user data) this accepts, as librime would on a broken patch. */
        var failIf: ((File) -> Boolean)? = null

        /** What librime's schema list holds after the deploy; every source schema when null. */
        var schemaList: List<String>? = null

        /** Extra compiled files (name to text) every deploy leaves in its build directory. */
        val compiledYaml = mutableMapOf<String, String>()
        val deployedSources = mutableListOf<File>()
        val workDirExisted = mutableListOf<Boolean>()

        /** The compiled files each deploy found in its build directory when it started. */
        val buildAtStart = mutableListOf<Set<String>>()

        /**
         * What the work directory held when each deploy started (the app's overlay), path to text;
         * without the compiled files copied from an earlier generation and their list.
         */
        val overlays = mutableListOf<Map<String, String>>()

        override suspend fun deployFull(
            sharedDataDir: File,
            userDataDir: File,
            appName: String,
            appVersion: String,
            afterDeploy: (suspend (DeployedEngine) -> Unit)?,
        ): DeployOutcome {
            calls++
            deployedSources += sharedDataDir
            workDirExisted += userDataDir.exists()
            buildAtStart += File(userDataDir, "build").list().orEmpty().toSet()
            overlays +=
                userDataDir
                    .walkTopDown()
                    .filter { it.isFile }
                    .map { it.relativeTo(userDataDir).invariantSeparatorsPath to it }
                    .filter { (path, _) -> !path.startsWith("build/") && !path.startsWith(".songyan-") }
                    .associate { (path, file) -> path to file.readText() }
            val build = File(userDataDir, "build").also { it.mkdirs() }
            if (!succeed ||
                failIf?.invoke(userDataDir) == true
            ) {
                return DeployOutcome(false, build, "-", emptyList(), "boom")
            }
            val schemas =
                sharedDataDir
                    .listFiles()
                    .orEmpty()
                    .filter { it.name.endsWith(".schema.yaml") }
                    .sortedBy { it.name }
            for (schema in schemas) {
                File(build, schema.name.replace(".schema.yaml", ".prism.bin")).writeText(schema.readText())
            }
            // Stands in for the compiled schemas, which name the prisms they use.
            File(build, "fake-compiled.schema.yaml").writeText(schemas.joinToString("\n") { it.name })
            for (dictionary in sharedDataDir.listFiles().orEmpty().filter { it.name.endsWith(".dict.yaml") }) {
                File(build, dictionary.name.replace(".dict.yaml", ".table.bin")).writeText(dictionary.readText())
            }
            File(build, "default.yaml").writeText("__build_info:\n  rime_version: 1.17.0\n")
            for ((name, text) in compiledYaml) File(build, name).writeText(text)
            afterDeploy?.invoke(
                object : DeployedEngine {
                    override val compiledDir = build

                    override suspend fun sourceProjection(schemaId: String): ByteArray? = null

                    override suspend fun schemaValue(
                        schemaId: String,
                        key: String,
                    ): String? = null

                    override suspend fun deploySchema(file: File): Boolean = false

                    override suspend fun composesKeys(
                        schemaId: String,
                        keys: String,
                    ): Boolean = true
                },
            )
            return DeployOutcome(
                true,
                build,
                "1.17.0",
                schemaList ?: schemas.map { it.name.removeSuffix(".schema.yaml") },
                null,
            )
        }
    }

    fun coordinator(
        storage: AppStorage,
        runner: RimeDeployRunner,
        assets: FakeAssetTree = assets(),
        stamp: BuildStamp = STAMP,
    ) = DeploymentCoordinator(storage, assets, runner, "rime.songyan", stamp)

    fun pipeline(
        storage: AppStorage,
        runner: RimeDeployRunner,
        assets: FakeAssetTree = assets(),
        stamp: BuildStamp = STAMP,
    ) = DeployPipeline(storage, coordinator(storage, runner, assets, stamp), SourceBuilder(assets))

    fun dictionary(
        name: String,
        rows: String,
        imports: List<String> = emptyList(),
    ): String {
        val tables = if (imports.isEmpty()) "" else "import_tables:\n" + imports.joinToString("") { "  - $it\n" }
        return "---\nname: $name\nversion: \"1\"\nsort: by_weight\n" + tables + "...\n" + rows
    }

    /** Writes picked dictionary files where the settings process would put them for [requestId]. */
    fun pickDictionaries(
        storage: AppStorage,
        requestId: String,
        files: Map<String, String>,
    ) {
        val dir = File(storage.incomingDir(requestId), DeployPipeline.FILES).also { it.mkdirs() }
        for ((name, text) in files) File(dir, name).writeText(text)
    }

    /** A rime-ice-like package as GitHub's "Download ZIP" delivers it: one wrapper directory. */
    fun schemePackageFiles(
        schemaId: String = "rime_ice",
        name: String = "雾凇拼音",
    ): Map<String, String> =
        linkedMapOf(
            "$schemaId-main/default.yaml" to "schema_list:\n  - schema: $schemaId\n  - schema: double_pinyin\n",
            "$schemaId-main/$schemaId.schema.yaml" to
                "schema:\n  schema_id: $schemaId\n  name: $name\ntranslator:\n  dictionary: $schemaId\n" +
                "engine:\n  translators:\n    - lua_translator@*date_translator\n",
            "$schemaId-main/double_pinyin.schema.yaml" to
                "schema:\n  schema_id: double_pinyin\n  name: 自然码双拼\ntranslator:\n  dictionary: $schemaId\n",
            "$schemaId-main/$schemaId.dict.yaml" to
                "---\nname: $schemaId\nversion: \"1\"\nsort: by_weight\n...\n你好\tni hao\t1\n",
            "$schemaId-main/lua/date_translator.lua" to "return function(input, seg) end\n",
            "$schemaId-main/opencc/s2t.json" to "{\"package\": true}\n",
            "$schemaId-main/README.md" to "# readme\n",
            "__MACOSX/$schemaId-main/._README.md" to "junk",
            "$schemaId-main/.DS_Store" to "junk",
        )

    /** A package whose dictionary can take a stacked one: a script translator over `rime_ice`, which imports tables. */
    fun stackablePackageFiles(rows: String = "你好\tni hao\t1\n"): Map<String, String> =
        linkedMapOf(
            "rime_ice-main/default.yaml" to "schema_list:\n  - schema: rime_ice\n  - schema: double_pinyin\n",
            "rime_ice-main/rime_ice.schema.yaml" to
                "schema:\n  schema_id: rime_ice\n  name: 雾凇拼音\nengine:\n  translators:\n" +
                "    - punct_translator\n    - script_translator\ntranslator:\n  dictionary: rime_ice\n",
            "rime_ice-main/double_pinyin.schema.yaml" to
                "schema:\n  schema_id: double_pinyin\n  name: 自然码双拼\nengine:\n  translators:\n" +
                "    - script_translator\ntranslator:\n  dictionary: rime_ice\n",
            "rime_ice-main/rime_ice.dict.yaml" to
                "---\nname: rime_ice\nversion: \"1\"\nsort: by_weight\nimport_tables:\n  - cn_dicts/base\n...\n$rows",
            "rime_ice-main/cn_dicts/base.dict.yaml" to "---\nname: cn_dicts/base\n...\n世界\tshi jie\t1\n",
        )

    fun zip(
        target: File,
        files: Map<String, String>,
    ): File {
        target.parentFile?.mkdirs()
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            for ((path, text) in files) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return target
    }

    fun pickSchemePackage(
        storage: AppStorage,
        requestId: String,
        files: Map<String, String> = schemePackageFiles(),
    ): File = zip(File(storage.incomingDir(requestId), DeployPipeline.PACKAGE_FILE), files)
}
