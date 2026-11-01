package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.rime.EngineTestSupport.candidateTexts
import app.songyan.ime.rime.EngineTestSupport.type
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The ambiguity-spelling bridge against the real engine: enumeration is read-only and capped,
 * spelling locks apply and restore under compare-and-set guards, and a declaration that names the
 * wrong adapter schema is refused (fail closed). Mirrors the iOS macOS harness
 * (ambiguity-main.swift) for the built-in 9-key, built-in 14-key flypy, 26-key and bad-binding
 * profiles.
 */
@RunWith(AndroidJUnit4::class)
class RimeAmbiguityTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var root: File

    @Before
    fun prepareRoot() {
        root = File(instrumentation.targetContext.cacheDir, "ambiguity").also { it.deleteRecursively() }
    }

    @After
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun builtinNineKeyReportsLocksAndRestoresSpellings() =
        withBuiltin { engine, adapters ->
            val t9 = requireNotNull(adapters["songyan_quanpin" to 9]) { "no 9-key adapter in $adapters" }
            assertTrue(engine.selectSchema(t9))
            engine.clearComposition()
            val decodesBefore = engine.spellingDecodeCount()

            // 72 has no valid combination: not an ambiguity, and reading it changes nothing.
            type(engine, "72")
            val before72 = engine.readSnapshot()
            assertEquals("72", before72.input)
            val report72 = requireNotNull(engine.ambiguitySpellings())
            assertEquals("72", report72.revisionInput)
            assertEquals(0 to 2, report72.segmentStart to report72.segmentEnd)
            assertTrue("72 must not be an ambiguity: ${report72.spellings}", report72.spellings.size < 2)
            assertTrue(report72.isComplete)
            assertEquals(before72, engine.readSnapshot())
            assertEquals("enumeration must not decode candidates", decodesBefore, engine.spellingDecodeCount())
            engine.clearComposition()

            type(engine, "986")
            val before986 = engine.readSnapshot()
            val report = requireNotNull(engine.ambiguitySpellings())
            assertEquals(listOf("yun", "zuo"), report.spellings)
            assertEquals("986", report.revisionInput)
            assertEquals(0 to 3, report.segmentStart to report.segmentEnd)
            assertTrue(report.isComplete)
            assertEquals(before986, engine.readSnapshot())

            // Cap contract: 1 truncates, <= 0 means the default, above the hard limit is clamped.
            val capped = requireNotNull(engine.ambiguitySpellings(maxItems = 1))
            assertEquals(listOf("yun"), capped.spellings)
            assertFalse(capped.isComplete)
            for (limit in listOf(0, 1000, Int.MAX_VALUE, Int.MIN_VALUE)) {
                val full = requireNotNull(engine.ambiguitySpellings(maxItems = limit)) { "limit $limit refused" }
                assertEquals("limit $limit", listOf("yun", "zuo"), full.spellings)
                assertTrue("limit $limit", full.isComplete)
            }
            assertEquals(before986, engine.readSnapshot())
            engine.clearComposition()

            // A report taken before the next key no longer matches the live input.
            type(engine, "98")
            val stale = requireNotNull(engine.ambiguitySpellings())
            type(engine, "6")
            assertEquals("986", engine.readSnapshot().input)
            assertTrue(stale.revisionInput != "986")
            engine.clearComposition()

            // After a partial selection only the tail segment is enumerated.
            type(engine, "64426")
            engine.selectCandidateAbsolute(1)
            assertTrue(engine.readSnapshot().isComposing)
            val tail = requireNotNull(engine.ambiguitySpellings())
            assertEquals(2 to 5, tail.segmentStart to tail.segmentEnd)
            assertEquals("64426", tail.revisionInput)
            assertEquals(listOf("hao"), tail.spellings)
            engine.clearComposition()

            assertNull("no composition: cannot answer, not 'no ambiguity'", engine.ambiguitySpellings())

            lockAndRestore(engine, t9, merged = "986", canonical = "yun", expectedCandidates = listOf("云"))
        }

    @Test
    fun builtinFourteenKeyFlypyLocksEveryOptionAndRestoresTheOrder() =
        withBuiltin { engine, adapters ->
            val flypy14 = requireNotNull(adapters["songyan_flypy" to 14]) { "no 14-key adapter in $adapters" }
            assertTrue(engine.selectSchema(flypy14))
            engine.clearComposition()

            type(engine, "ug")
            val before = engine.readSnapshot()
            assertEquals("ug", before.input)
            val report = requireNotNull(engine.ambiguitySpellings())
            val four = listOf("chang", "cheng", "shang", "sheng")
            assertEquals(four, report.spellings)
            assertEquals(four, report.lockable)
            assertEquals(RimeLockMode.TOKEN_ALIAS, report.lockMode)
            assertEquals("ug", report.revisionInput)
            assertEquals(0 to 2, report.segmentStart to report.segmentEnd)
            assertTrue(report.isComplete)
            assertEquals(before, engine.readSnapshot())
            engine.clearComposition()
            for (canonical in four) lockAndRestore(engine, flypy14, merged = "ug", canonical = canonical)

            type(engine, "gc")
            val gc = requireNotNull(engine.ambiguitySpellings())
            assertEquals(listOf("gui", "hao", "hui"), gc.spellings)
            assertEquals(listOf("gui", "hao", "hui"), gc.lockable)
            engine.clearComposition()
            for (canonical in listOf(
                "gui",
                "hao",
                "hui",
            )) {
                lockAndRestore(engine, flypy14, merged = "gc", canonical = canonical)
            }

            // The merged-key trajectory BN + UI sends "bu"; both bu and ni must be lockable.
            type(engine, "bu")
            assertTrue(candidateTexts(engine).contains("你"))
            val bu = requireNotNull(engine.ambiguitySpellings())
            assertTrue("bu spellings: ${bu.spellings}", bu.spellings.containsAll(listOf("bu", "ni")))
            assertTrue("bu lockable: ${bu.lockable}", bu.lockable.containsAll(listOf("bu", "ni")))
            assertEquals(RimeLockMode.TOKEN_ALIAS, bu.lockMode)
            engine.clearComposition()
            lockAndRestore(engine, flypy14, merged = "bu", canonical = "ni")
            lockAndRestore(engine, flypy14, merged = "bu", canonical = "bu")

            // A wrong expected input is stale even though the lock options exist.
            type(engine, "ug")
            assertEquals(
                RimeAmbiguityApplyResult.STALE,
                engine.applyAmbiguitySpelling("zz", 0, 2, "shang"),
            )
            assertEquals("ug", engine.readSnapshot().input)
            engine.clearComposition()
        }

    @Test
    fun twentySixKeySchemaNeverReportsAmbiguityAndRefusesLocks() =
        withBuiltin { engine, _ ->
            assertTrue(engine.selectSchema("songyan_flypy"))
            engine.clearComposition()
            type(engine, "ni")
            val before = engine.readSnapshot()
            assertTrue(before.isComposing)
            val report = requireNotNull(engine.ambiguitySpellings())
            assertTrue("26-key reports ${report.spellings}", report.spellings.size < 2)
            assertEquals(before, engine.readSnapshot())
            assertEquals(RimeAmbiguityApplyResult.UNSUPPORTED, engine.applyAmbiguitySpelling("ni", 0, 2, "ni"))
            assertEquals("ni", engine.readSnapshot().input)
            engine.clearComposition()
        }

    @Test
    fun aDeclarationNamingAnotherAdapterIsRefused() =
        runBlocking {
            val shared = File(root, "shared")
            EngineTestSupport.copyAssetTree(
                instrumentation.context.assets,
                "${EngineTestSupport.FIXTURE_ASSET_ROOT}/ambiguity/bad-bindings",
                shared,
            )
            val engine = deployAndOpen(shared, File(root, "user"))
            try {
                val schemas = engine.schemas().map { it.id }
                assertTrue(
                    "fixture schemas missing: $schemas",
                    schemas.containsAll(listOf("cross_wired", "direct_unbound")),
                )
                for ((schema, merged) in listOf("cross_wired" to "ab", "direct_unbound" to "ab")) {
                    assertTrue(engine.selectSchema(schema))
                    engine.clearComposition()
                    type(engine, merged)
                    val report = requireNotNull(engine.ambiguitySpellings()) { "$schema: bridge refused to answer" }
                    assertEquals("$schema lock mode", RimeLockMode.NONE, report.lockMode)
                    assertTrue("$schema lockable: ${report.lockable}", report.lockable.isEmpty())
                    val apply =
                        engine.applyAmbiguitySpelling(merged, 0, merged.length, report.spellings.firstOrNull() ?: "x")
                    assertEquals("$schema apply", RimeAmbiguityApplyResult.UNSUPPORTED, apply)
                    assertEquals("$schema input after refused apply", merged, engine.readSnapshot().input)
                    engine.clearComposition()
                }
            } finally {
                engine.close()
            }
        }

    /** Applies [canonical] to [merged], checks the guards, and restores the typed code. */
    private suspend fun lockAndRestore(
        engine: RimeEngine,
        schema: String,
        merged: String,
        canonical: String,
        expectedCandidates: List<String>? = null,
    ) {
        val label = "$merged/$canonical"
        assertTrue(engine.selectSchema(schema))
        engine.clearComposition()
        type(engine, merged)
        val baseline = candidateTexts(engine)
        assertTrue("$label: merged baseline empty", baseline.isNotEmpty())

        val applied = engine.applyAmbiguitySpelling(merged, 0, merged.length, canonical)
        assertEquals("$label: apply", RimeAmbiguityApplyResult.APPLIED, applied)
        val locked = engine.readSnapshot()
        val alias = locked.input
        assertTrue(
            "$label: input must be an opaque alias, got '$alias'",
            alias != merged && alias != canonical && alias.startsWith("9"),
        )
        assertTrue("$label: still composing", locked.isComposing)
        assertEquals("$label: nothing committed", "", locked.commitText)
        assertEquals("$label: locked spelling", canonical, engine.highlightedSpelling())
        expectedCandidates?.let { assertEquals("$label: narrowed candidates", it, candidateTexts(engine)) }
        assertEquals(listOf(canonical), requireNotNull(engine.ambiguitySpellings()).spellings)

        // Replaying the pre-lock input must not write.
        assertEquals(
            "$label: stale apply",
            RimeAmbiguityApplyResult.STALE,
            engine.applyAmbiguitySpelling(merged, 0, merged.length, canonical),
        )
        assertEquals(alias, engine.readSnapshot().input)
        // A spelling the prism does not prove, a wrong range and out-of-range indices change nothing.
        assertEquals(
            RimeAmbiguityApplyResult.INVALID,
            engine.applyAmbiguitySpelling(alias, 0, alias.length, "nope"),
        )
        assertEquals(RimeAmbiguityApplyResult.STALE, engine.applyAmbiguitySpelling(alias, 0, 2, canonical))
        assertEquals(
            RimeAmbiguityApplyResult.INVALID,
            engine.applyAmbiguitySpelling(alias, 0, Int.MAX_VALUE, canonical),
        )
        assertEquals(
            RimeAmbiguityApplyResult.INVALID,
            engine.applyAmbiguitySpelling(alias, Int.MIN_VALUE, 3, canonical),
        )
        assertEquals(
            RimeAmbiguityApplyResult.INVALID,
            engine.restoreAmbiguitySpelling(alias, Int.MAX_VALUE, Int.MAX_VALUE, canonical, merged),
        )
        assertEquals("$label: refused calls leave the input", alias, engine.readSnapshot().input)

        val restored = engine.restoreAmbiguitySpelling(alias, 0, alias.length, canonical, merged)
        assertEquals("$label: restore", RimeAmbiguityApplyResult.APPLIED, restored)
        val after = engine.readSnapshot()
        assertEquals("$label: input restored", merged, after.input)
        assertEquals("$label: candidate order restored", baseline, candidateTexts(engine))
        assertEquals("$label: nothing committed", "", after.commitText)
        assertEquals(
            "$label: restore on an unlocked span",
            RimeAmbiguityApplyResult.STALE,
            engine.restoreAmbiguitySpelling(merged, 0, merged.length, canonical, merged),
        )
        engine.clearComposition()
    }

    private fun withBuiltin(block: suspend (RimeEngine, Map<Pair<String, Int>, String>) -> Unit) =
        runBlocking {
            val shared = File(root, "shared")
            EngineTestSupport.copyAssetTree(
                instrumentation.targetContext.assets,
                EngineTestSupport.BUILTIN_ASSET_ROOT,
                shared,
            )
            val engine = deployAndOpen(shared, File(root, "user"))
            try {
                val adapters = LinkedHashMap<Pair<String, Int>, String>()
                for (schema in engine.schemas()) {
                    val keys =
                        Regex("keyboard_[0-9a-f]+_(\\d+)")
                            .find(schema.id)
                            ?.groupValues
                            ?.get(1)
                            ?.toInt() ?: continue
                    val source = engine.schemaValue(schema.id, "keyboard_adapter/source_schema") ?: continue
                    adapters[source to keys] = schema.id
                }
                assertNotNull(adapters)
                block(engine, adapters)
            } finally {
                engine.close()
            }
        }

    private suspend fun deployAndOpen(
        shared: File,
        user: File,
    ): RimeEngine {
        val deployment = RimeDeployment.deploy(shared, user, appVersion = "test")
        assertTrue("deployment failed: ${deployment.lastError}", deployment.success)
        return RimeEngine.createPrebuilt(shared, user, appVersion = "test", compiledDir = deployment.compiledDir)
    }
}
