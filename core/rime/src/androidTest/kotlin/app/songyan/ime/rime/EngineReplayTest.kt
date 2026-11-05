package app.songyan.ime.rime

import android.content.res.AssetManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.layout.CompactFold
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device acceptance over the synced engine fixtures.
 *
 * The real engine deploys the bundled package into a temporary user directory and
 * replays every synced case, so the assertions describe what the shipped engine
 * actually commits rather than what a stub was told to return.
 */
@RunWith(AndroidJUnit4::class)
class EngineReplayTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test
    fun playbackCasesReadScriptOptionsAndFirstCandidateExpectation() {
        val fixture =
            Json
                .parseToJsonElement(
                    """[{"schema":"songyan_quanpin","layout":"qwerty26","keys":"fazhan","expect_text":"發展","options":{"simplification":false,"zht":true,"zh_tw":false,"zh_hk":false},"expect_first":"發展"}]""",
                ).jsonArray
        val case = PlaybackFixtures.parse(fixture).single()
        assertEquals(
            mapOf("simplification" to false, "zht" to true, "zh_tw" to false, "zh_hk" to false),
            case.options,
        )
        assertEquals("發展", case.expectFirst)
    }

    @Test
    fun olderPlaybackCasesKeepTheirCommitExpectation() {
        val fixture =
            Json
                .parseToJsonElement(
                    """[{"schema":"songyan_quanpin","layout":"qwerty26","keys":"nihao","expect_text":"你好"}]""",
                ).jsonArray
        val case = PlaybackFixtures.parse(fixture).single()
        assertEquals("你好", case.expectText)
        assertEquals(emptyMap<String, Boolean>(), case.options)
        assertEquals(null, case.expectFirst)
    }

    @Test
    fun playbackCasesCommitTheDictionaryWord() =
        runBlocking {
            val assets = instrumentation.context.assets
            val cases = PlaybackFixtures.load(assets)
            assertTrue("no playback cases in the fixture", cases.isNotEmpty())
            withEngine { engine ->
                val adapters = discoverAdapters(engine)
                val failures = StringBuilder()
                var committed = 0
                var missing = 0
                for (case in cases) {
                    val schema = deployedSchema(case.schema, case.layout, adapters)
                    if (schema == null) {
                        failures.append("not deployed: ${case.schema}/${case.layout}\n")
                        continue
                    }
                    val text =
                        replayAndCommit(
                            engine,
                            schema,
                            case.keys,
                            case.expectText,
                            case.options,
                            case.expectFirst,
                        )
                    when {
                        text == case.expectText -> committed++
                        text == null -> {
                            missing++
                            if (missing <= MAX_REPORTED_FAILURES) {
                                failures.append(
                                    "no candidate: ${case.schema}/${case.layout} '${case.keys}' -> ${case.expectText}\n",
                                )
                            }
                        }
                        else ->
                            failures.append(
                                "wrong commit: ${case.schema}/${case.layout} '${case.keys}' -> '$text' != ${case.expectText}\n",
                            )
                    }
                }
                assertEquals(
                    "committed $committed/${cases.size} (candidate missing: $missing)\n$failures",
                    cases.size,
                    committed,
                )
            }
        }

    /**
     * Every layout x input method: the 26-key playback cases are typed on the folded keys of 14, 18
     * and 9 keys (what each key sends is [CompactFold]), and each must still reach and commit its
     * word on the generated companion of that layout. 26 keys is the plain fixture above.
     */
    @Test
    fun foldedKeysCommitTheDictionaryWordOnEveryCompactLayout() =
        runBlocking {
            val cases =
                PlaybackFixtures
                    .load(instrumentation.context.assets)
                    .filter { it.layout == "qwerty26" }
            assertTrue("no 26-key cases in the fixture", cases.isNotEmpty())
            val folds =
                mapOf(
                    "merged14" to CompactFold.MERGED14,
                    "merged18" to CompactFold.MERGED18,
                    "phone9" to CompactFold.PHONE9,
                )
            withEngine { engine ->
                val adapters = discoverAdapters(engine)
                val failures = StringBuilder()
                var committed = 0
                var total = 0
                for ((layout, fold) in folds) {
                    for (case in cases) {
                        total++
                        val schema = deployedSchema(case.schema, layout, adapters)
                        if (schema == null) {
                            failures.append("not deployed: ${case.schema}/$layout\n")
                            continue
                        }
                        val keys = case.keys.map { fold.map[it] ?: it }.joinToString("")
                        // Folded keys prove reachability; the declared layout checks first-candidate order.
                        val text = replayAndCommit(engine, schema, keys, case.expectText, case.options)
                        if (text == case.expectText) {
                            committed++
                        } else if (failures.length < MAX_REPORTED_FAILURES * 80) {
                            failures.append(
                                "${case.schema}/$layout '${case.keys}' as '$keys' -> '$text' != ${case.expectText}\n",
                            )
                        }
                    }
                }
                assertEquals("committed $committed/$total\n$failures", total, committed)
            }
        }

    @Test
    fun replayedLearningCasesCommitTheirOwnReading() =
        runBlocking {
            val cases = LearningFixtures.load(instrumentation.context.assets)
            assertTrue("no learning cases in the fixture", cases.isNotEmpty())
            withEngine { engine ->
                val adapters = discoverAdapters(engine)
                val failures = StringBuilder()
                for (case in cases) {
                    for ((column, target) in LEARNING_LAYOUTS) {
                        val code = case.layouts[column] ?: continue
                        val schema = deployedSchema(target.schema, target.layout, adapters)
                        if (schema == null) {
                            failures.append("not deployed: ${target.schema}/${target.layout}\n")
                            continue
                        }
                        // The fixture lists a self-made word with its own reading; each
                        // reading must still commit the character the fixture names, which
                        // is what makes the word reachable through learning.
                        val syllables = code.split(" ")
                        val characters = case.word.toList()
                        if (syllables.size != characters.size) {
                            failures.append("${case.word}/$column: syllable count differs from character count\n")
                            continue
                        }
                        for (index in characters.indices) {
                            val committed =
                                replayAndCommit(engine, schema, syllables[index], characters[index].toString())
                            if (committed != characters[index].toString()) {
                                failures.append(
                                    "${case.word}/$column: '${syllables[index]}' committed '$committed', " +
                                        "expected '${characters[index]}'\n",
                                )
                            }
                        }
                    }
                }
                assertEquals("learning fixture readings do not replay:\n$failures", "", failures.toString())
            }
        }

    @Test
    fun committingThroughTheUserDictionaryPersists() =
        runBlocking {
            withEngine { engine ->
                // The shared user dictionary is the one writable learning store; a commit
                // must create it and a fresh session must still open it.
                val name = engine.userDictNames().firstOrNull()
                if (name == null) {
                    val committed = replayAndCommit(engine, "songyan_quanpin", "nihao", "你好")
                    assertEquals("cannot commit 你好 with songyan_quanpin", "你好", committed)
                    engine.userDictNames()
                    assertTrue(
                        "user dictionary was not created by the commit",
                        engine.userDictNames().isNotEmpty(),
                    )
                } else {
                    assertTrue("user dictionary name is empty", name.isNotEmpty())
                }
            }
        }

    // ---- engine plumbing ---------------------------------------------------

    private suspend fun <T> withEngine(block: suspend (RimeEngine) -> T): T {
        val root = File(instrumentation.targetContext.cacheDir, "engine-replay")
        root.deleteRecursively()
        val source = File(root, "source")
        val user = File(root, "user")
        copyAssetTree(instrumentation.targetContext.assets, BUILTIN_ASSET_ROOT, source)
        // The frontend tables must sit beside the source, as in the app installer.
        copyAssetTree(instrumentation.targetContext.assets, "rime/opencc", File(source, "opencc"))
        val deployment =
            RimeDeployment.deploy(
                sharedDataDir = source,
                userDataDir = user,
                appName = RimeEngine.DEFAULT_APP_NAME,
                appVersion = "test",
            )
        assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
        assertTrue("deployment produced no schema", deployment.schemaIds.isNotEmpty())
        val engine =
            RimeEngine.createPrebuilt(
                sharedDataDir = source,
                userDataDir = user,
                appName = RimeEngine.DEFAULT_APP_NAME,
                appVersion = "test",
                compiledDir = deployment.compiledDir,
            )
        try {
            return block(engine)
        } finally {
            engine.close()
            root.deleteRecursively()
        }
    }

    /** (source schema, fixture layout) to the deployed adapter schema id. */
    private suspend fun discoverAdapters(engine: RimeEngine): Map<Pair<String, String>, String> {
        val adapters = LinkedHashMap<Pair<String, String>, String>()
        for (schema in engine.schemas()) {
            val match = ADAPTER_ID.find(schema.id) ?: continue
            val layout =
                when (match.groupValues[2]) {
                    "9" -> "phone9"
                    "14" -> "merged14"
                    "18" -> "merged18"
                    else -> continue
                }
            val source = engine.schemaValue(schema.id, "keyboard_adapter/source_schema") ?: continue
            adapters[source to layout] = schema.id
        }
        return adapters
    }

    private fun deployedSchema(
        sourceSchema: String,
        layout: String,
        adapters: Map<Pair<String, String>, String>,
    ): String? = if (layout == "qwerty26") sourceSchema else adapters[sourceSchema to layout]

    /**
     * Feeds [keys] then commits the candidate whose text is [expected], paging while
     * the candidate is not on the current page. Null when no page carries it.
     */
    private suspend fun replayAndCommit(
        engine: RimeEngine,
        schema: String,
        keys: String,
        expected: String,
        options: Map<String, Boolean> = emptyMap(),
        expectFirst: String? = null,
    ): String? {
        if (!engine.selectSchema(schema)) return null
        engine.clearComposition()
        // The engine keeps options across cases, so legacy cases start in the original script.
        for (option in SCRIPT_OPTIONS) engine.setOption(option, option == "simplification")
        for ((name, value) in options) {
            assertTrue("could not set fixture option $name", engine.setOption(name, value))
        }
        for (char in keys) {
            val keycode = RimeKey.baseKeycode(char) ?: return null
            engine.processKey(RimeKey(keycode))
        }
        var snapshot = engine.readSnapshot()
        if (expectFirst != null) {
            assertEquals("first candidate for $schema '$keys'", expectFirst, snapshot.candidates.firstOrNull()?.text)
        }
        var pages = 0
        while (true) {
            val index = snapshot.candidates.indexOfFirst { it.text == expected }
            if (index >= 0) return engine.selectCandidate(index)?.commitText
            if (snapshot.isLastPage || pages >= MAX_PAGES) break
            snapshot = engine.pageForward() ?: break
            pages++
        }
        engine.clearComposition()
        return null
    }

    private fun copyAssetTree(
        assets: AssetManager,
        assetPath: String,
        target: File,
    ) {
        target.mkdirs()
        for (name in assets.list(assetPath).orEmpty()) {
            val childPath = "$assetPath/$name"
            val child = File(target, name)
            if (assets.list(childPath).orEmpty().isNotEmpty()) {
                copyAssetTree(assets, childPath, child)
            } else {
                assets.open(childPath).use { input ->
                    child.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    private data class PlaybackCase(
        val schema: String,
        val layout: String,
        val keys: String,
        val expectText: String,
        val options: Map<String, Boolean> = emptyMap(),
        val expectFirst: String? = null,
    )

    private data class LearningCase(
        val word: String,
        val sourceSchema: String,
        val layouts: Map<String, String>,
    )

    private object PlaybackFixtures {
        fun load(assets: AssetManager): List<PlaybackCase> =
            parse(readJson(assets, "engine-fixtures/builtin/playback_cases.json"))

        fun parse(root: JsonArray): List<PlaybackCase> =
            root.mapNotNull { element ->
                val fields = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                PlaybackCase(
                    schema = fields.string("schema") ?: return@mapNotNull null,
                    layout = fields.string("layout") ?: return@mapNotNull null,
                    keys = fields.string("keys") ?: return@mapNotNull null,
                    expectText = fields.string("expect_text") ?: return@mapNotNull null,
                    options =
                        fields["options"]
                            ?.jsonObject
                            ?.mapValues { (_, value) ->
                                value.jsonPrimitive.boolean
                            }.orEmpty(),
                    expectFirst = fields.string("expect_first"),
                )
            }
    }

    private object LearningFixtures {
        fun load(assets: AssetManager): List<LearningCase> {
            val root = readJson(assets, "engine-fixtures/builtin/learning_cases.json")
            return root.mapNotNull { element ->
                val fields = runCatching { element.jsonObject }.getOrNull() ?: return@mapNotNull null
                val layouts =
                    runCatching { fields["layouts"]?.jsonObject }.getOrNull()
                        ?: return@mapNotNull null
                LearningCase(
                    word = fields.string("word") ?: return@mapNotNull null,
                    sourceSchema = fields.string("schema") ?: DEFAULT_SOURCE_SCHEMA,
                    layouts =
                        layouts.mapValues { (_, value) ->
                            runCatching { value.jsonPrimitive.content }.getOrNull().orEmpty()
                        },
                )
            }
        }
    }

    companion object {
        private const val BUILTIN_ASSET_ROOT = "rime/builtin"
        private const val DEFAULT_SOURCE_SCHEMA = "songyan_quanpin"
        private const val MAX_PAGES = 40
        private val SCRIPT_OPTIONS = listOf("simplification", "zht", "zh_tw", "zh_hk")
        private const val MAX_REPORTED_FAILURES = 20
        private val ADAPTER_ID = Regex("keyboard_([0-9a-f]+)_(\\d+)")

        /** Fixture layout column to the (source schema, deployed layout) pair it encodes. */
        private val LEARNING_LAYOUTS =
            listOf(
                "pinyin_26" to LayoutTarget("songyan_quanpin", "qwerty26"),
                "flypy26" to LayoutTarget("songyan_flypy", "qwerty26"),
                "flypy14" to LayoutTarget("songyan_flypy", "merged14"),
            )

        private data class LayoutTarget(
            val schema: String,
            val layout: String,
        )

        private fun readJson(
            assets: AssetManager,
            path: String,
        ): JsonArray =
            Json
                .parseToJsonElement(
                    assets.open(path).bufferedReader().use { it.readText() },
                ).jsonArray

        private fun JsonObject.string(key: String): String? =
            runCatching { this[key]?.jsonPrimitive?.content }.getOrNull()?.ifEmpty { null }
    }
}
