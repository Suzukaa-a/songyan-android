package app.songyan.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeEngineBackend
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipFile

/**
 * The Japanese romaji keyboard over the real kagiroi package and the shipped editor wiring.
 *
 * The package is not part of the repository: a machine that can run librime deployed and compiled it,
 * and the ZIP is pushed into this test package's own cache before the explicit acceptance run (see
 * the fixture below). The tests type through the keyboard's own key actions and read the platform
 * editor, so every commitment below is what the real engine told the editor.
 *
 * A missing package fails the tests instead of skipping them: a class that could not read the real
 * data must never turn green. The acceptance run names this class explicitly and hands it the pushed
 * package; the fixture step owns keeping the class out of the default run.
 */
@RunWith(AndroidJUnit4::class)
class JapaneseRomajiDeviceTest {
    @Test
    fun aJapaneseSentenceIsTypedAndCommitted() =
        withKagiroi("sentence") { host ->
            host.startJapanese()
            host.type("nihongo")
            host.awaitIdle()
            // The field shows the kana the engine composed, never the romaji keys.
            assertEquals("にほんご", host.text())
            assertTrue("the composition is marked text", host.editor.composing)
            host.tapCandidate("日本語")
            host.assertText("日本語")
            assertFalse("the composition ended", host.editor.composing)
            assertFalse("the engine composition ended", host.backend.currentSnapshot().isComposing)
        }

    @Test
    fun returnCommitsKanaNotRomaji() =
        withKagiroi("return") { host ->
            host.startJapanese()
            host.type("nihongo")
            host.awaitIdle()
            assertEquals("にほんご", host.text())
            host.tapControl(ControlKey.ENTER)
            // Return commits the engine's script text: the kana, not the candidate and not the keys.
            host.assertText("にほんご")
            assertFalse("the composition ended", host.editor.composing)
            assertFalse("the engine composition ended", host.backend.currentSnapshot().isComposing)
        }

    @Test
    fun theLongVowelKeyTypesChoonpuMidWord() =
        withKagiroi("long-vowel") { host ->
            host.startJapanese()
            host.type("ko")
            host.tapAction(LayoutActionKind.CODE, LONG_VOWEL_CODE)
            host.type("hi")
            host.tapAction(LayoutActionKind.CODE, LONG_VOWEL_CODE)
            host.awaitIdle()
            val composed = host.text()
            assertTrue("the first kana composed: '$composed'", composed.contains("こ"))
            assertTrue("the second kana composed: '$composed'", composed.contains("ひ"))
            assertTrue("the long vowel key typed ー mid-word: '$composed'", composed.contains("ー"))
            assertFalse("the long vowel key typed an ASCII hyphen: '$composed'", composed.contains('-'))
            assertTrue("kana only: '$composed'", composed.none { it.code in 'a'.code..'z'.code })
            val candidates = host.barLabels()
            assertTrue(
                "no candidate コーヒー among $candidates",
                candidates.any { it == "コーヒー" || it.startsWith("コーヒー ") },
            )
        }

    @Test
    fun spaceAndReturnAreLabelledForJapanese() =
        withKagiroi("labels") { host ->
            host.startJapanese()
            val idle = host.drawnKeys()
            assertTrue("the ja space key reads 空白: $idle", "空白" in idle)
            assertFalse("no simplified 空格 under ja: $idle", "空格" in idle)
            host.type("ka")
            host.awaitIdle()
            assertTrue("the composition started", host.editor.composing)
            val composing = host.drawnKeys()
            assertTrue("the composing space key reads 変換: $composing", "変換" in composing)
            assertTrue("the composing return key reads 確定: $composing", "確定" in composing)
        }

    @Test
    fun aPasswordFieldDoesNotLearn() {
        // Control: the same pick in an ordinary field is learned, so the engine really ranks it higher.
        var pickedWord = ""
        var before = -1
        var after = -1
        withKagiroi("password-control") { host ->
            host.startJapanese()
            host.type("nihongo")
            host.awaitIdle()
            val picked = host.pickLowCandidate()
            pickedWord = picked.word
            before = picked.rank
            host.type("nihongo")
            host.awaitIdle()
            after = host.rankOf(pickedWord)
        }
        assertTrue("the picked word is not offered again: '$pickedWord' $before -> $after", after >= 0)
        assertTrue(
            "an ordinary field must learn the pick: '$pickedWord' ranked $before then $after",
            after < before,
        )

        // A password field takes the keys as literal ASCII and never reaches the engine, so there is
        // nothing it could learn; back in an ordinary field over the same engine the ranking is
        // exactly the one from before the password field was used.
        withKagiroi("password-field") { host ->
            host.startJapanese()
            host.type("nihongo")
            host.awaitIdle()
            val baseline = host.barLabels()
            assertTrue("the engine offered no candidates for nihongo: $baseline", baseline.isNotEmpty())
            host.main { host.controller.discardComposition() }
            host.awaitIdle()

            host.switchEditor(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
            host.type("nihongo")
            host.awaitIdle()
            assertEquals("a password field receives the keys literally", "nihongo", host.text())
            assertFalse("nothing composes in a password field", host.editor.composing)
            assertFalse("the keys never reached the engine", host.backend.currentSnapshot().isComposing)
            assertTrue(
                "a password field offers no candidates: ${host.barLabels()}",
                host.barLabels().isEmpty(),
            )

            host.switchEditor(InputType.TYPE_CLASS_TEXT)
            host.setText("")
            host.type("nihongo")
            host.awaitIdle()
            assertEquals("the password field taught the engine nothing", baseline, host.barLabels())
        }
    }

    private fun <T> withKagiroi(
        name: String,
        block: (FeatureHost) -> T,
    ): T {
        val backend = KagiroiFixture.open(prepared, name)
        val host = FeatureHost(backend, layout = "qwerty26", schemaID = KagiroiFixture.SCHEMA)
        try {
            return block(host)
        } finally {
            host.close()
            runBlocking { backend.shutdown() }
        }
    }

    companion object {
        private lateinit var prepared: KagiroiFixture.Prepared

        @BeforeClass
        @JvmStatic
        fun extractThePushedPackage() {
            prepared = KagiroiFixture.prepare()
        }

        @AfterClass
        @JvmStatic
        fun removeTheExtractedPackage() {
            if (!::prepared.isInitialized) return
            prepared.root.deleteRecursively()
        }
    }
}

/** One candidate the test picked, with the rank it had before the pick. */
private class PickedCandidate(
    val word: String,
    val rank: Int,
)

private const val PICKED_RANK = 4

/** The code the romaji board's ー key sends; the scheme turns it into a long vowel while composing. */
private const val LONG_VOWEL_CODE = "-"

/**
 * Starts the keyboard on the real package and proves the production pairing took effect: the running
 * session is kagiroi, the drawn board is the bundled romaji one, and its ー key is on screen.
 */
private fun FeatureHost.startJapanese() {
    start()
    awaitIdle()
    val session = requireNotNull(main2 { controller.session }) { "the keyboard never opened a session" }
    assertEquals(
        "the keyboard is not typing with the real kagiroi package",
        KagiroiFixture.SCHEMA,
        session.selectedSchemaID,
    )
    assertEquals(
        "the production default pairing did not pick the romaji board",
        KagiroiFixture.ROMAJI_LAYOUT,
        main2 { controller.mainDefinition().id },
    )
    assertTrue(
        "the romaji board carries the ー key",
        hasAction { it.type == LayoutActionKind.CODE && it.value == LONG_VOWEL_CODE },
    )
}

/** The faces the keyboard draws now, in key order. */
private fun FeatureHost.drawnKeys(): List<String> = main2 { find(KeyboardView::class.java).labels }

/** The candidate labels on the bar, less the expand chip the bar adds when a page overflows. */
private fun FeatureHost.candidateLabels(): List<String> {
    val expand = string(R.string.ime_expand)
    return barLabels().filterNot { it == expand }
}

/** The bar rank of [word] now, or -1 when the engine no longer offers it. */
private fun FeatureHost.rankOf(word: String): Int =
    candidateLabels().indexOfFirst { it == word || it.startsWith("$word ") }

/**
 * Picks a candidate that is not already first and returns the word and its rank before the pick, so
 * a later ranking can show whether the engine learned it.
 */
private fun FeatureHost.pickLowCandidate(): PickedCandidate {
    val labels = candidateLabels()
    assertTrue("the engine offered no candidates to choose from: $labels", labels.size >= 2)
    val rank = minOf(PICKED_RANK, labels.size - 1)
    assertTrue("the only candidate is already first, so a pick cannot move it: $labels", rank > 0)
    val label = labels[rank]
    tapBar(label)
    awaitIdle()
    return PickedCandidate(label.substringBefore(' '), rank)
}

/**
 * Starts input on the same editor with [inputType], the way the service sees the field change: the
 * old connection finishes any composition, the new one reports the new editor, and the keyboard
 * re-derives its policy (a password field then types ASCII directly, an ordinary field composes again).
 */
private fun FeatureHost.switchEditor(inputType: Int) {
    main {
        connection.finishComposingText()
        field.inputType = inputType
        field.imeOptions = 0
        info = EditorInfo()
        connection =
            requireNotNull(field.onCreateInputConnection(info)) {
                "the editor offered no connection for inputType $inputType"
            }
    }
    main {
        val keep = editor.startInput(info, restarting = false)
        val policy = EditorPolicy.from(info)
        controller.configure(policy)
        controller.startInput(policy, keep)
    }
    awaitIdle()
}

/**
 * The real, locally compiled kagiroi package the Japanese keyboard tests type on.
 *
 * The package was deployed and compiled on a machine that runs librime (nothing is compiled on the
 * device) and pushed as a ZIP into this test package's own cache; the instrumentation argument names
 * the archive. The ZIP carries the deployed shared tree at its root - the compiled `default.yaml` and
 * `*.schema.yaml`, `lua/` and `opencc/` - and librime's prebuilt tables under `build/`. This helper
 * streams both out without ever holding a compiled table in the heap, copies the small compiled YAML
 * into the build directory where the prebuilt config resolver reads it, and opens the same prebuilt
 * backend the shipped keyboard uses.
 *
 * A missing argument, a missing archive, or an archive that is not a prebuilt deployment fails the
 * test. Nothing is written outside this test package's cache, and no engine of another test is
 * touched: librime is a process-wide singleton, so callers open one backend at a time.
 */
private object KagiroiFixture {
    /** Instrumentation argument: the pushed ZIP's absolute path, inside this test package's cache. */
    const val ARG_FIXTURE = "kagiroiFixture"

    /** The input method the Japanese keyboard types on. */
    const val SCHEMA = "kagiroi"

    /** The bundled board the kagiroi family's production default pairing resolves to on the 26-key shape. */
    const val ROMAJI_LAYOUT = "romaji26"

    /** Scratch root of this class inside the test package's cache. */
    private const val CACHE_DIRECTORY = "japanese-romaji-device"

    private const val BUILD_ENTRY = "build/"
    private const val DEFAULT_CONFIG = "default.yaml"
    private const val SCHEMA_SUFFIX = ".schema.yaml"
    private const val YAML_SUFFIX = ".yaml"
    private const val BIN_SUFFIX = ".bin"
    private const val BUFFER_BYTES = 1024 * 1024

    /** The extracted package: the read-only shared tree, the prebuilt build, and the test scratch. */
    class Prepared(
        val root: File,
        val shared: File,
        val compiled: File,
    ) {
        /** A user directory under the test scratch, kept for as long as the caller needs it. */
        fun userDir(name: String): File = File(root, "user-$name").also { it.mkdirs() }
    }

    /**
     * Extracts the pushed package and returns the directories the prebuilt engine opens. A missing
     * fixture, or an archive that carries no prebuilt build, fails here before an engine is opened.
     */
    fun prepare(): Prepared {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val archive = requireArchive(cache)
        val root = File(cache, CACHE_DIRECTORY)
        if (canonical(archive).path.startsWith(canonical(root).path + File.separator)) {
            throw AssertionError(
                "the kagiroi package must be pushed outside this class's scratch, which is cleared " +
                    "before every run: " + archive + " is inside " + root,
            )
        }
        if (root.exists() && !root.deleteRecursively()) {
            throw AssertionError("cannot clear the previous Japanese keyboard scratch: $root")
        }
        val shared = File(root, "shared")
        val compiled = File(root, "compiled")
        shared.mkdirs()
        compiled.mkdirs()
        if (!shared.isDirectory || !compiled.isDirectory) {
            throw AssertionError("cannot create the Japanese keyboard scratch under $root")
        }
        try {
            val extracted = extract(archive, shared, compiled)
            placeCompiledConfig(shared, compiled)
            validate(shared, compiled)
            println(
                "KAGIROI\t" + archive.name + "\tfiles=" + extracted.files +
                    "\texpandedBytes=" + extracted.bytes + "\tprebuiltTables=" + extracted.tables,
            )
            return Prepared(root, shared, compiled)
        } catch (error: Throwable) {
            root.deleteRecursively()
            throw error
        }
    }

    /** Opens the real prebuilt engine on [prepared] with a fresh user directory for [name]. */
    fun open(
        prepared: Prepared,
        name: String,
    ): EngineBackend {
        val user = prepared.userDir(name)
        val backend =
            runBlocking {
                RimeEngineBackend.create(
                    sharedDataDir = prepared.shared,
                    userDataDir = user,
                    compiledDir = prepared.compiled,
                    appName = RimeEngine.DEFAULT_APP_NAME,
                    appVersion = "test",
                )
            }
        try {
            check(backend.availableSchemas.any { it.id == SCHEMA }) {
                "the prebuilt package does not offer '$SCHEMA': ${backend.availableSchemas.map { it.id }}"
            }
            runBlocking { backend.selectSchema(SCHEMA) }
        } catch (error: Throwable) {
            runBlocking { backend.shutdown() }
            throw error
        }
        return backend
    }

    private class Extracted(
        val files: Int,
        val bytes: Long,
        val tables: Int,
    )

    private fun requireArchive(cache: File): File {
        val raw =
            InstrumentationRegistry
                .getArguments()
                .getString(ARG_FIXTURE)
                ?: throw AssertionError(
                    "no kagiroi package was handed to this test; pass -e " + ARG_FIXTURE +
                        " <absolute path of the prebuilt ZIP pushed into " + cache.path + ">. The class " +
                        "fails instead of skipping without the real package",
                )
        val file = File(raw)
        if (!file.exists()) {
            throw AssertionError(
                "the kagiroi package does not exist: " + raw +
                    "; push the prebuilt ZIP into " + cache.path + " first",
            )
        }
        val resolved = canonical(file)
        if (!resolved.path.startsWith(canonical(cache).path + File.separator)) {
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
        var tables = 0
        ZipFile(archive).use { zip ->
            val entries =
                zip
                    .entries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .toList()
            if (entries.isEmpty()) throw AssertionError("the kagiroi package carries no files: $archive")
            val prefix = wrapperPrefix(entries.map { it.name })
            for (entry in entries) {
                // A ZIP made by `zip -r archive .` may prefix every name with "./"; strip it so
                // the top-level build/ directory is recognised as the prebuilt build.
                val name =
                    entry.name
                        .removePrefix(prefix)
                        .removePrefix("./")
                        .removePrefix("/")
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
                // The compiled tables weigh about 174 MB in total: stream them, never hold one in the heap.
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, BUFFER_BYTES) }
                }
                files++
                bytes += target.length()
                if (inBuild && relative.endsWith(BIN_SUFFIX)) tables++
            }
        }
        return Extracted(files, bytes, tables)
    }

    /**
     * Copies the archive's small compiled YAML into the prebuilt build directory, where librime's
     * deployed config resolver reads `default` and the schemas. Only YAML is copied (a few kilobytes);
     * the compiled tables stay where the archive put them.
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
            throw AssertionError("the kagiroi package has no $DEFAULT_CONFIG at its root: $shared")
        }
        if (!File(compiled, DEFAULT_CONFIG).isFile) {
            throw AssertionError("the prebuilt build has no $DEFAULT_CONFIG in its prebuilt build: $compiled")
        }
        val schemas = compiled.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SCHEMA_SUFFIX) }
        if (schemas.isEmpty()) {
            throw AssertionError(
                "the prebuilt build has no compiled *$SCHEMA_SUFFIX in $compiled; a prebuilt " +
                    "engine cannot activate any input method from it",
            )
        }
        val tables = compiled.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(BIN_SUFFIX) }
        if (tables.isEmpty()) {
            throw AssertionError(
                "the prebuilt build has no compiled *$BIN_SUFFIX in $compiled; without the locally " +
                    "compiled tables this test would have to compile on the device, which it never does",
            )
        }
        if (!File(shared, "lua").isDirectory) {
            throw AssertionError("the kagiroi package has no lua/ modules in $shared")
        }
        if (!File(shared, "opencc").isDirectory) {
            throw AssertionError("the kagiroi package has no opencc/ data in $shared")
        }
    }

    /** The single enclosing directory a GitHub-style ZIP wraps everything in, or an empty string. */
    private fun wrapperPrefix(names: List<String>): String {
        val roots = names.map { it.substringBefore('/') }.toSet()
        val root = roots.singleOrNull() ?: return ""
        return if (names.all { it.contains('/') }) "$root/" else ""
    }

    private fun safeEntry(name: String): String {
        val normalized = name.replace('\\', '/')
        val unsafe = normalized.startsWith("/") || normalized.split('/').any { it.isEmpty() || it == ".." }
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
