package app.songyan.ime.ime

import android.graphics.Rect
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.keyboard.kana12.Kana12Layout
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeEngineBackend
import kotlinx.coroutines.CompletableDeferred
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
 * J2.7 acceptance: the twelve-key kana board types through the real kagiroi package with the
 * gestures a finger makes, and the shipped editor wiring receives what the engine commits.
 *
 * The board is reached the way production reaches it: a saved keyboard pairing chooses [KANA_BOARD]
 * as the letters page of the kagiroi input method, the real [KeyboardView] builds the surface, and
 * every case below sends raw touch events to that view (ACTION_DOWN, ACTION_MOVE for a flick,
 * ACTION_UP). Nothing calls the kana model or the controller directly: a green result means the
 * gesture path, the serializer, the engine and the editor routing all worked.
 *
 * The package is the same pushed ZIP the Japanese romaji acceptance uses
 * (`-e kagiroiFixture <absolute path>` inside this test package's cache); a missing package fails
 * instead of skipping. The fixture step owns keeping this class out of the default run.
 */
@RunWith(AndroidJUnit4::class)
class KanaFlickDeviceTest {
    @Test
    fun flickTypesAWord() =
        withKagiroi("flick-word") { host ->
            host.startKana()
            // な left = に, は down = ほ, わ up = ん.
            host.flickRow("na", Kana12Direction.Left)
            host.flickRow("ha", Kana12Direction.Down)
            host.flickRow("wa", Kana12Direction.Up)
            host.assertComposed("にほん")
            assertTrue("the composition is marked text in the editor", host.editor.composing)
            assertTrue(
                "the engine offered no 日本 for にほん: " + host.barLabels(),
                host.await {
                    host.barLabels().any { label -> label == CANDIDATE || label.startsWith(CANDIDATE + " ") }
                },
            )
            host.tapCandidate(CANDIDATE)
            host.assertText(CANDIDATE)
            assertFalse("the composition ended after the candidate", host.editor.composing)
            assertFalse("the engine composition ended", host.backend.currentSnapshot().isComposing)
        }

    @Test
    fun toggleCyclesARow() =
        withKagiroi("toggle") { host ->
            host.startKana()
            // Two taps on か inside the 0.8 s window walk the row's tap cycle: か -> き.
            host.doubleTapRow("ka")
            host.assertComposed("き")
        }

    @Test
    fun theDakutenKeyTurnsTsuIntoSmallTsuThenDzu() =
        withKagiroi("dakuten") { host ->
            host.startKana()
            // た up = つ; the modifier walks つ -> っ -> づ.
            host.flickRow("ta", Kana12Direction.Up)
            host.assertComposed("つ")
            host.tapModifier()
            host.assertComposed("っ")
            host.tapModifier()
            host.assertComposed("づ")
        }

    @Test
    fun returnCommitsKana() =
        withKagiroi("return") { host ->
            host.startKana()
            host.flickRow("na", Kana12Direction.Left)
            host.flickRow("ha", Kana12Direction.Down)
            host.flickRow("wa", Kana12Direction.Up)
            host.assertComposed("にほん")
            host.tapControl(ControlKey.ENTER)
            // Return commits the engine's script text: the kana, never the candidate or the keys.
            host.assertText("にほん")
            assertFalse("the composition ended", host.editor.composing)
            assertFalse("the engine composition ended", host.backend.currentSnapshot().isComposing)
        }

    /**
     * A restart of the same field does not finish its input: a kana press queued before it is still
     * typed once the restarted session is ready, as every other key is.
     */
    @Test
    fun aKanaPressQueuedForARestartOfItsFieldIsStillTyped() =
        withKagiroi("restart") { host ->
            host.startKana()
            val queue = CompletableDeferred<Unit>()
            host.main { host.controller.enqueue { queue.await() } }
            host.main { host.controller.handleKana12(Kana12Action.Kana("na", Kana12Direction.Left)) }
            host.main {
                val keep = host.editor.startInput(host.info, restarting = true)
                val policy = EditorPolicy.from(host.info)
                host.controller.configure(policy)
                host.controller.startInput(policy, keep)
            }
            host.main { queue.complete(Unit) }
            host.awaitIdle()
            host.assertComposed("に")
        }

    /**
     * Starts the keyboard on the real package and proves the production path reached the kana
     * surface: the running session is kagiroi, the saved pairing resolved the kana board, and the
     * drawn view carries the eleven kana rows of the input model in reading order.
     */
    private fun FeatureHost.startKana() {
        start()
        awaitIdle()
        val session = requireNotNull(main2 { controller.session }) { "the keyboard never opened a session" }
        assertEquals(
            "the keyboard is not typing with the real kagiroi package",
            KanaFixture.SCHEMA,
            session.selectedSchemaID,
        )
        assertEquals(
            "the saved pairing did not select the kana board",
            KANA_BOARD,
            main2 { controller.mainDefinition().id },
        )
        val rows =
            main2 {
                val view = find(KeyboardView::class.java)
                view.kana12RowIds.filterNotNull()
            }
        assertEquals(
            "the drawn board is not the twelve-key kana surface: rows=" + rows +
                "; the letters page must keep the four-row kana grid",
            Kana12Layout.rowIds,
            rows,
        )
    }

    /** The virtual index of the drawn kana row [rowId], or a failure naming the rows that are drawn. */
    private fun FeatureHost.kanaRowIndex(rowId: String): Int {
        val rows =
            main2 {
                val view = find(KeyboardView::class.java)
                view.keys.indices.map { view.kana12RowIdAt(it) }
            }
        val index = rows.indexOf(rowId)
        assertTrue("no kana row '" + rowId + "' on the drawn board: " + rows, index >= 0)
        return index
    }

    /** The virtual index of the 小゛゜ key. */
    private fun FeatureHost.modifierIndex(): Int {
        val index =
            main2 {
                val view = find(KeyboardView::class.java)
                view.keys.indexOfFirst { it.action.type == LayoutActionKind.KANA_MODIFIER }
            }
        assertTrue("the twelve-key board drew no 小゛゜ key", index >= 0)
        return index
    }

    /** Waits for the composition to become [expected] and then asserts it, naming what it shows instead. */
    private fun FeatureHost.assertComposed(expected: String) {
        await { text() == expected }
        assertEquals("the composition is '" + text() + "', expected '" + expected + "'", expected, text())
    }

    /** The real touch centre of key [index], in the view's own coordinates. */
    private fun FeatureHost.keyCenter(index: Int): Pair<Float, Float> =
        main2 {
            val view = find(KeyboardView::class.java)
            val bounds = Rect()
            requireNotNull(view.accessibilityNodeProvider.createAccessibilityNodeInfo(index)) {
                "the keyboard exposed no node for key " + index
            }.getBoundsInParent(bounds)
            bounds.exactCenterX() to bounds.exactCenterY()
        }

    private fun FeatureHost.tapKey(index: Int) {
        val center = keyCenter(index)
        val down = SystemClock.uptimeMillis()
        main {
            val view = find(KeyboardView::class.java)
            dispatchTouch(view, down, down, MotionEvent.ACTION_DOWN, center)
            dispatchTouch(view, down, down + TAP_MS, MotionEvent.ACTION_UP, center)
        }
        awaitIdle()
    }

    /** Two taps on one row inside the model's toggle window, dispatched before the queue drains. */
    private fun FeatureHost.doubleTapRow(rowId: String) {
        val center = keyCenter(kanaRowIndex(rowId))
        val start = SystemClock.uptimeMillis()
        main {
            val view = find(KeyboardView::class.java)
            for (tap in 0 until 2) {
                val at = start + tap * DOUBLE_TAP_GAP_MS
                dispatchTouch(view, at, at, MotionEvent.ACTION_DOWN, center)
                dispatchTouch(view, at, at + TAP_MS, MotionEvent.ACTION_UP, center)
            }
        }
        awaitIdle()
    }

    /** A flick: down on the key, one move past the lock threshold, then up at the same point. */
    private fun FeatureHost.flickRow(
        rowId: String,
        direction: Kana12Direction,
    ) {
        val center = keyCenter(kanaRowIndex(rowId))
        if (direction == Kana12Direction.Center) {
            tapKey(kanaRowIndex(rowId))
            return
        }
        val density = context.resources.displayMetrics.density
        val travel = (Kana12Flick.THRESHOLD_DP + FLICK_OVERSHOOT_DP).toFloat() * density
        val dx =
            when (direction) {
                Kana12Direction.Left -> -travel
                Kana12Direction.Right -> travel
                else -> 0f
            }
        val dy =
            when (direction) {
                Kana12Direction.Up -> -travel
                Kana12Direction.Down -> travel
                else -> 0f
            }
        val target = (center.first + dx) to (center.second + dy)
        val down = SystemClock.uptimeMillis()
        main {
            val view = find(KeyboardView::class.java)
            dispatchTouch(view, down, down, MotionEvent.ACTION_DOWN, center)
            dispatchTouch(view, down, down + FLICK_MOVE_MS, MotionEvent.ACTION_MOVE, target)
            dispatchTouch(view, down, down + FLICK_UP_MS, MotionEvent.ACTION_UP, target)
        }
        awaitIdle()
    }

    private fun FeatureHost.tapModifier() = tapKey(modifierIndex())

    private fun FeatureHost.dispatchTouch(
        view: KeyboardView,
        downTime: Long,
        eventTime: Long,
        action: Int,
        point: Pair<Float, Float>,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, point.first, point.second, 0)
        try {
            view.dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun <T> withKagiroi(
        name: String,
        block: (FeatureHost) -> T,
    ): T {
        val backend = KanaFixture.open(prepared, name)
        val host = FeatureHost(backend, layout = KANA_SHAPE, schemaID = KanaFixture.SCHEMA)
        try {
            return block(host)
        } finally {
            host.close()
            runBlocking { backend.shutdown() }
        }
    }

    companion object {
        /** The physical shape the kagiroi pairing is stored under; the kana board is its letters page. */
        const val KANA_SHAPE = "qwerty26"

        /** The bundled twelve-key Japanese board. */
        const val KANA_BOARD = "kana12"

        private const val CANDIDATE = "日本"
        private const val TAP_MS = 30L
        private const val DOUBLE_TAP_GAP_MS = 60L
        private const val FLICK_MOVE_MS = 24L
        private const val FLICK_UP_MS = 48L

        /** Travel past the 18 dp flick lock, so a tap on the boundary never decides the direction. */
        private const val FLICK_OVERSHOOT_DP = 10.0

        private lateinit var prepared: KanaFixture.Prepared

        @BeforeClass
        @JvmStatic
        fun installTheKanaPairing() {
            prepared = KanaFixture.prepare()
            // The kana board is only ever chosen, never guessed from the catalog: a saved pairing
            // for the kagiroi input method on the 26-key shape is how production selects it.
            val keyboard = KeyboardCompositionKey(KanaFixture.SCHEMA, LayoutInput.Builtin.QWERTY26).rawValue
            KeyboardLayoutCatalog.install(
                LayoutRegistry.create(
                    compositions = mapOf(keyboard to KeyboardComposition(mainLayoutID = KANA_BOARD)),
                ),
            )
        }

        @AfterClass
        @JvmStatic
        fun removeTheExtractedPackage() {
            // Leave the process-wide catalog with the bundled defaults other test classes expect.
            KeyboardLayoutCatalog.install(LayoutRegistry.create())
            if (::prepared.isInitialized) KanaFixture.cleanup(prepared)
        }
    }
}

/**
 * The locally compiled kagiroi package the kana keyboard tests type on.
 *
 * The package was deployed and compiled on a machine that runs librime (nothing is compiled on the
 * device) and pushed as a ZIP into this test package's own cache; the instrumentation argument names
 * the archive. The ZIP carries the deployed shared tree at its root - the compiled `default.yaml`,
 * `*.schema.yaml`, `lua/` and `opencc/` - and librime's prebuilt tables under `build/`. This helper
 * streams both out without ever holding a compiled table in the heap, copies the small compiled YAML
 * into the build directory where the prebuilt config resolver reads it, and opens the same prebuilt
 * backend the shipped keyboard uses.
 *
 * A missing argument, a missing archive, or an archive that is not a prebuilt deployment fails the
 * test. Nothing is written outside this test package's cache.
 */
internal object KanaFixture {
    /** Instrumentation argument: the pushed ZIP's absolute path, inside this test package's cache. */
    const val ARG_FIXTURE = "kagiroiFixture"

    /** The input method the twelve-key keyboard types on. */
    const val SCHEMA = "kagiroi"

    private const val CACHE_DIRECTORY = "kana-flick-device"
    private const val BUILD_ENTRY = "build/"
    private const val DEFAULT_CONFIG = "default.yaml"
    private const val SCHEMA_SUFFIX = ".schema.yaml"
    private const val YAML_SUFFIX = ".yaml"
    private const val BIN_SUFFIX = ".bin"
    private const val BUFFER_BYTES = 1024 * 1024

    /** The extracted package and the test scratch it owns; [cleanup] removes all of it. */
    class Prepared(
        val root: File,
        val shared: File,
        val compiled: File,
    ) {
        fun userDir(name: String): File = File(root, "user-" + name).also { it.mkdirs() }
    }

    fun prepare(): Prepared {
        val cache = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val archive = requireArchive(cache)
        val root = File(cache, CACHE_DIRECTORY)
        if (root.exists() && !root.deleteRecursively()) {
            throw AssertionError("cannot clear the previous kana keyboard scratch: " + root)
        }
        val shared = File(root, "shared")
        val compiled = File(root, "compiled")
        shared.mkdirs()
        compiled.mkdirs()
        if (!shared.isDirectory || !compiled.isDirectory) {
            throw AssertionError("cannot create the kana keyboard scratch under " + root)
        }
        try {
            val extracted = extract(archive, shared, compiled)
            placeCompiledConfig(shared, compiled)
            validate(shared, compiled)
            println("KANA_FIXTURE\t" + archive.name + "\tfiles=" + extracted)
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
                "the prebuilt package does not offer '" + SCHEMA + "': " + backend.availableSchemas.map { it.id }
            }
            runBlocking { backend.selectSchema(SCHEMA) }
        } catch (error: Throwable) {
            runBlocking { backend.shutdown() }
            throw error
        }
        return backend
    }

    fun cleanup(prepared: Prepared) {
        prepared.root.deleteRecursively()
    }

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
        if (!file.isFile) throw AssertionError("the kagiroi package is not a file: " + file)
        if (!hasZipMagic(file)) throw AssertionError("the kagiroi package is not a ZIP archive: " + file)
        return file
    }

    private fun extract(
        archive: File,
        shared: File,
        compiled: File,
    ): Int {
        var files = 0
        ZipFile(archive).use { zip ->
            val entries =
                zip
                    .entries()
                    .asSequence()
                    .filterNot { it.isDirectory }
                    .toList()
            if (entries.isEmpty()) throw AssertionError("the kagiroi package carries no files: " + archive)
            val prefix = wrapperPrefix(entries.map { it.name })
            for (entry in entries) {
                // A ZIP made by 'zip -r archive .' may prefix every name with './'.
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
                    throw AssertionError("cannot create the kagiroi package directory: " + parent)
                }
                // The compiled tables weigh about 174 MB in total: stream them, never hold one in the heap.
                zip.getInputStream(entry).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output, BUFFER_BYTES) }
                }
                files++
            }
        }
        return files
    }

    /**
     * Copies the archive's small compiled YAML into the prebuilt build directory, where librime's
     * deployed config resolver reads 'default' and the schemas; the tables stay where they are.
     */
    private fun placeCompiledConfig(
        shared: File,
        compiled: File,
    ) {
        val configs = shared.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(YAML_SUFFIX) }
        if (configs.isEmpty()) {
            throw AssertionError("the kagiroi package has no compiled YAML at its root: " + shared)
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
            throw AssertionError("the kagiroi package has no " + DEFAULT_CONFIG + " at its root: " + shared)
        }
        if (!File(compiled, DEFAULT_CONFIG).isFile) {
            throw AssertionError("the prebuilt build has no " + DEFAULT_CONFIG + " in it: " + compiled)
        }
        val schemas = compiled.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(SCHEMA_SUFFIX) }
        if (schemas.isEmpty()) {
            throw AssertionError(
                "the prebuilt build has no compiled *" + SCHEMA_SUFFIX + " in " + compiled +
                    "; a prebuilt engine cannot activate any input method from it",
            )
        }
        val tables = compiled.listFiles().orEmpty().filter { it.isFile && it.name.endsWith(BIN_SUFFIX) }
        if (tables.isEmpty()) {
            throw AssertionError(
                "the prebuilt build has no compiled *" + BIN_SUFFIX + " in " + compiled +
                    "; without the locally compiled tables this test would have to compile on the device",
            )
        }
        if (!File(shared, "lua").isDirectory) {
            throw AssertionError("the kagiroi package has no lua/ modules in " + shared)
        }
        if (!File(shared, "opencc").isDirectory) {
            throw AssertionError("the kagiroi package has no opencc/ data in " + shared)
        }
    }

    /** The single enclosing directory a GitHub-style ZIP wraps everything in, or an empty string. */
    private fun wrapperPrefix(names: List<String>): String {
        val roots = names.map { it.substringBefore('/') }.toSet()
        val root = roots.singleOrNull() ?: return ""
        return if (names.all { it.contains('/') }) root + "/" else ""
    }

    private fun safeEntry(name: String): String {
        val normalized = name.replace('\\', '/')
        val unsafe =
            normalized.startsWith("/") || normalized.split('/').any { it.isEmpty() || it == ".." }
        if (unsafe) throw AssertionError("the kagiroi package has an unsafe entry: " + name)
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
