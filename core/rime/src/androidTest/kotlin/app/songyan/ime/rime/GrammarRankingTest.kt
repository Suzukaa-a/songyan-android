package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.rime.EngineTestSupport.type
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

/**
 * Grammar-model ranking on the real engine, ported from the iOS ranking verifier
 * (verify-grammar-ranking.py): the same fixed inputs are replayed with the language model switched
 * off (`grammar/language: ""`) and on, and the first candidates must match the calibrated
 * prefixes of `grammar-ranking/wanxiang-lts-zh-hans.json`.
 *
 * The model is 418 MB and is not part of the repository. The enabled-model test needs the pinned
 * file (checked by SHA-256) at [MODEL_DEVICE_PATH], for example
 * `adb push wanxiang-lts-zh-hans.gram /data/local/tmp/songyan-grammar/model.gram`; without it the
 * test is skipped, never faked.
 */
@RunWith(AndroidJUnit4::class)
class GrammarRankingTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File
    private lateinit var fixture: Fixture

    @Before
    fun loadFixture() {
        root = File(instrumentation.targetContext.cacheDir, "grammar-ranking").also { it.deleteRecursively() }
        val text =
            instrumentation.context.assets
                .open("$FIXTURE_DIR/wanxiang-lts-zh-hans.json")
                .bufferedReader()
                .use { it.readText() }
        val json = Json.parseToJsonElement(text).jsonObject
        fixture =
            Fixture(
                modelSha256 = json.getValue("model_sha256").jsonPrimitive.content,
                cases =
                    json.getValue("cases").jsonArray.map {
                        val case = it.jsonObject
                        Case(
                            code = case.getValue("code").jsonPrimitive.content,
                            enabledPrefix =
                                case.getValue("enabled_prefix").jsonArray.map { c ->
                                    c.jsonPrimitive.content
                                },
                            disabledPrefix =
                                case.getValue("disabled_prefix").jsonArray.map { c ->
                                    c.jsonPrimitive.content
                                },
                        )
                    },
            )
        assertTrue(fixture.cases.any { it.enabledPrefix != it.disabledPrefix })
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun withoutAModelTheDictionaryWeightsDecideTheOrder() =
        runBlocking {
            val ranking = replay(model = null) { }
            for (case in fixture.cases) {
                assertEquals(
                    "disabled ranking of ${case.code}",
                    case.disabledPrefix,
                    ranking.getValue(case.code).take(case.disabledPrefix.size),
                )
            }
        }

    @Test
    fun thePinnedModelPromotesTheSentenceReading() =
        runBlocking {
            val model = File(MODEL_DEVICE_PATH)
            assumeTrue("grammar model not provisioned at $MODEL_DEVICE_PATH", model.isFile)
            assumeTrue("file at $MODEL_DEVICE_PATH is not the pinned model", sha256(model) == fixture.modelSha256)
            var queries: String? = null
            val ranking =
                replay(model) { engine ->
                    queries = engine.grammarDiagnosticsJson()
                    assertTrue(
                        "the session did not attach the model; diagnostics: $queries",
                        engine.grammarSessionLoaded(File(root, "shared/$MODEL_NAME.gram").path),
                    )
                }
            for (case in fixture.cases) {
                assertEquals(
                    "enabled ranking of ${case.code} (diagnostics $queries)",
                    case.enabledPrefix,
                    ranking.getValue(case.code).take(case.enabledPrefix.size),
                )
            }
        }

    /** Deploys the fixture with the model on or off and returns the first page per input code. */
    private suspend fun replay(
        model: File?,
        inspect: suspend (RimeEngine) -> Unit,
    ): Map<String, List<String>> {
        val shared = File(root, "shared")
        EngineTestSupport.copyAssetTree(instrumentation.context.assets, FIXTURE_DIR, shared)
        File(shared, "wanxiang-lts-zh-hans.json").delete()
        model?.copyTo(File(shared, "$MODEL_NAME.gram"))
        val language = if (model != null) MODEL_NAME else "\"\""
        File(shared, "songyan_quanpin.custom.yaml").writeText("patch:\n  grammar/language: $language\n")
        val user = File(root, "user").also { it.mkdirs() }
        File(shared, "rime.lua").copyTo(File(user, "rime.lua"))
        val deployment = RimeDeployment.deploy(shared, user, appVersion = "test")
        assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
        val engine = RimeEngine.createPrebuilt(shared, user, appVersion = "test", compiledDir = deployment.compiledDir)
        try {
            assertTrue(engine.selectSchema("songyan_quanpin"))
            val result = LinkedHashMap<String, List<String>>()
            for (case in fixture.cases) {
                engine.clearComposition()
                type(engine, case.code)
                result[case.code] = engine.readSnapshot().candidates.map { it.text }
                engine.clearComposition()
            }
            inspect(engine)
            return result
        } finally {
            engine.close()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private data class Case(
        val code: String,
        val enabledPrefix: List<String>,
        val disabledPrefix: List<String>,
    )

    private data class Fixture(
        val modelSha256: String,
        val cases: List<Case>,
    )

    private companion object {
        const val FIXTURE_DIR = "${EngineTestSupport.FIXTURE_ASSET_ROOT}/grammar-ranking"
        const val MODEL_NAME = "songyan_acceptance_model"
        const val MODEL_DEVICE_PATH = "/data/local/tmp/songyan-grammar/model.gram"
    }
}
