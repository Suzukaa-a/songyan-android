package app.songyan.ime.deploy

import app.songyan.ime.packages.EngineFeatureReport
import app.songyan.ime.packages.EngineFeatureReport.Availability
import app.songyan.ime.packages.EngineFeatureSettings
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A deployment patches the user's feature choices into each input method that can take
 * them, as managed blocks of a work-directory custom file over the package's own, compiles the
 * input method again and reports what each input method supports.
 */
class EngineFeaturesTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val id = "songyan_quanpin"
    private val schemaText = File("../core/rime/src/main/assets/rime/builtin/$id.schema.yaml").readText()
    private val projection =
        File("../core/packages/src/test/resources/package-fixtures/$id.projection.json").readBytes()

    private inner class FakeEngine(
        override val compiledDir: File,
    ) : DeployedEngine {
        val deployed = mutableListOf<File>()
        var compiles = true

        override suspend fun sourceProjection(schemaId: String): ByteArray? = projection

        override suspend fun schemaValue(
            schemaId: String,
            key: String,
        ): String? = null

        override suspend fun deploySchema(file: File): Boolean {
            deployed += file
            return compiles
        }

        override suspend fun composesKeys(
            schemaId: String,
            keys: String,
        ): Boolean = true
    }

    private class Dirs(
        val source: File,
        val work: File,
        val engine: DeployedEngine,
    )

    private fun setUp(
        compiled: String = schemaText,
        packageCustom: String? = null,
    ): Pair<Dirs, FakeEngine> {
        val source = temp.newFolder("source")
        val work = temp.newFolder("work")
        val build = File(work, "build").also { it.mkdirs() }
        File(source, "$id.schema.yaml").writeText(schemaText)
        packageCustom?.let { File(source, "$id.custom.yaml").writeText(it) }
        File(build, "$id.schema.yaml").writeText(compiled)
        val engine = FakeEngine(build)
        return Dirs(source, work, engine) to engine
    }

    private fun features(
        dirs: Dirs,
        settings: EngineFeatureSettings,
        database: Boolean = false,
    ) = EngineFeatures(settings, dirs.source, dirs.work, listOf(id), database, emptySet())

    private fun report(dirs: Dirs): EngineFeatureReport.SchemaFeatures =
        requireNotNull(
            EngineFeatureReport.decode(File(dirs.engine.compiledDir, EngineFeatureReport.FILE_NAME).readText()),
        ).schemas.getValue(id)

    @Test
    fun typoCorrectionIsOnByDefaultAndFuzzyPinyinFollowsTheChosenPairs() =
        runBlocking {
            val (dirs, engine) = setUp()

            val changed =
                features(dirs, EngineFeatureSettings(fuzzyPinyin = mapOf(id to listOf("z-zh", "unknown"))))
                    .apply(engine)

            assertEquals(setOf(id), changed)
            assertEquals(listOf(File(dirs.source, "$id.schema.yaml")), engine.deployed)
            val custom = File(dirs.work, "$id.custom.yaml").readText()
            assertTrue(custom, custom.contains("# Rx: songyan:typo-correction: {"))
            assertTrue(custom, custom.contains("translator/enable_correction: true"))
            assertTrue(custom, custom.contains("# Rx: songyan:fuzzy-pinyin: {"))
            assertTrue(custom, custom.contains("\"fuzz/^zh/z/\""))
            val report = report(dirs)
            assertEquals(Availability.AVAILABLE, report.typoCorrection)
            assertEquals(Availability.AVAILABLE, report.fuzzyPinyin)
            assertEquals(Availability.AVAILABLE, report.prediction)
            // The fake engine compiles nothing, so the report shows what the build really does.
            assertFalse(report.typoCorrectionOn)
            assertTrue(report.fuzzyPairs.isEmpty())
        }

    @Test
    fun nothingToChangeMeansNoCustomFileAndNoSecondCompile() =
        runBlocking {
            val (dirs, engine) = setUp()

            val changed = features(dirs, EngineFeatureSettings(typoCorrection = false)).apply(engine)

            assertTrue(changed.isEmpty())
            assertTrue(engine.deployed.isEmpty())
            assertFalse(File(dirs.work, "$id.custom.yaml").exists())
        }

    @Test
    fun thePackagesOwnCustomFileIsKeptAndWhatItSetsIsLeftToIt() =
        runBlocking {
            val own = "patch:\n  translator/enable_correction: false\n  menu/page_size: 7\n"
            val (dirs, engine) = setUp(packageCustom = own)

            features(dirs, EngineFeatureSettings(fuzzyPinyin = mapOf(id to listOf("n-l")))).apply(engine)

            val custom = File(dirs.work, "$id.custom.yaml").readText()
            assertTrue(custom, custom.contains("menu/page_size: 7"))
            assertFalse(custom, custom.contains("# Rx: songyan:typo-correction: {"))
            assertTrue(custom, custom.contains("\"fuzz/^n/l/\""))
            assertEquals(Availability.SCHEME, report(dirs).typoCorrection)
            assertEquals(own, File(dirs.source, "$id.custom.yaml").readText())
        }

    @Test
    fun predictionNeedsTheDatabaseAndASchemeThatAlreadyFuzzesKeepsItsOwnRules() =
        runBlocking {
            val fuzzed = schemaText.replace("  algebra: []\n", "  algebra:\n    - fuzz/^ch/c/\n")
            val (dirs, engine) = setUp(compiled = fuzzed)

            features(dirs, EngineFeatureSettings(typoCorrection = false), database = false).apply(engine)
            assertTrue(engine.deployed.isEmpty())

            features(dirs, EngineFeatureSettings(typoCorrection = false), database = true).apply(engine)
            val custom = File(dirs.work, "$id.custom.yaml").readText()
            assertTrue(custom, custom.contains("engine/processors/@before 0: predictor"))
            assertTrue(custom, custom.contains("predictor/db: songyan-predict.db"))
            assertEquals(Availability.SCHEME, report(dirs).fuzzyPinyin)
        }

    @Test
    fun aPatchedInputMethodLibrimeRefusesFailsTheDeployment() {
        val (dirs, engine) = setUp()
        engine.compiles = false
        val failure =
            runCatching { runBlocking { features(dirs, EngineFeatureSettings()).apply(engine) } }.exceptionOrNull()
        assertTrue(failure is DeployException)
    }
}
