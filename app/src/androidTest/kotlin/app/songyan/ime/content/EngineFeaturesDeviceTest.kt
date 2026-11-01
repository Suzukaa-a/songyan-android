package app.songyan.ime.content

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.packages.EngineFeatureReport.Availability
import app.songyan.ime.rime.RimeEngine
import app.songyan.ime.rime.RimeKey
import app.songyan.ime.settings.state.EngineFeaturesHolder
import app.songyan.ime.settings.state.FeatureChangeResult
import app.songyan.ime.settings.state.StoredSourceResult
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On a device: feature choices made through the settings gateway are deployed by the real
 * :deploy process into the bundled input method, its regenerated compact companions included, and
 * the keyboard's engine over the published build types with them.
 */
@RunWith(AndroidJUnit4::class)
class EngineFeaturesDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val storage = AppStorage(context.filesDir)
    private val publisher = GenerationPublisher(storage)
    private val settings = SettingsInForeground()
    private val gateway get() = EngineFeaturesHolder.current

    @Before
    fun setUp() {
        settings.open()
        runBlocking {
            storage.featureSettingsFile.delete()
            assertEquals(StoredSourceResult.Success, StoredSourcesHolder.current.use(SourceIds.BUILTIN))
        }
    }

    @After
    fun tearDown() {
        runBlocking {
            storage.featureSettingsFile.delete()
            runCatching { StoredSourcesHolder.current.use(SourceIds.BUILTIN) }
        }
        settings.close()
    }

    @Test
    fun fuzzyPinyinAndTypoCorrectionReachTheKeyboard() =
        runBlocking {
            assertEquals(
                FeatureChangeResult.Success,
                gateway.setFuzzyPinyin(BuiltinTemplateSchemas.QUANPIN, setOf("z-zh")),
            )

            val snapshot = requireNotNull(gateway.load(BuiltinTemplateSchemas.QUANPIN))
            assertEquals(Availability.AVAILABLE, snapshot.typoCorrection)
            assertTrue("typo correction is on by default", snapshot.typoCorrectionOn)
            assertEquals(Availability.AVAILABLE, snapshot.fuzzyPinyin)
            assertEquals(setOf("z-zh"), snapshot.fuzzyPairs)
            val zong = candidates(BuiltinTemplateSchemas.QUANPIN, "zong")
            assertTrue("zong offers a zh word: $zong", zong.contains(ZH_WORD))
            val merged14 =
                requireNotNull(
                    BuiltinTemplateSchemas.engineSchemaID(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.MERGED14),
                )
            val folded = candidates(merged14, "zobg")
            assertTrue("the 14-key companion mixes too: $folded", folded.contains(ZH_WORD))
            assertTrue(
                "a neighbouring key is corrected",
                candidates(BuiltinTemplateSchemas.QUANPIN, "nihap").contains("你好"),
            )

            assertEquals(
                FeatureChangeResult.Success,
                gateway.setFuzzyPinyin(BuiltinTemplateSchemas.QUANPIN, emptySet()),
            )
            assertEquals(FeatureChangeResult.Success, gateway.setTypoCorrection(false))
            val off = requireNotNull(gateway.load(BuiltinTemplateSchemas.QUANPIN))
            assertTrue(off.fuzzyPairs.isEmpty())
            assertFalse(off.typoCorrectionOn)
            assertFalse(candidates(BuiltinTemplateSchemas.QUANPIN, "zong").contains(ZH_WORD))
        }

    private suspend fun candidates(
        schema: String,
        keys: String,
    ): List<String> {
        val record = requireNotNull(publisher.readActive())
        val generation = storage.generationDir(record.generation)
        val user = File(context.cacheDir, "features-test-user").also { it.deleteRecursively() }
        val engine =
            RimeEngine.createPrebuilt(
                sharedDataDir = File(generation, "resources"),
                userDataDir = user,
                appVersion = "test",
                compiledDir = File(generation, "build"),
            )
        try {
            assertTrue("cannot select $schema", engine.selectSchema(schema))
            for (char in keys) engine.processKey(RimeKey(requireNotNull(RimeKey.baseKeycode(char))))
            return engine.readSnapshot().candidates.map { it.text }
        } finally {
            engine.close()
            user.deleteRecursively()
        }
    }

    private companion object {
        /** A zhong word of the bundled dictionary that "zong" only reaches through z/zh. */
        const val ZH_WORD = "种"
    }
}
