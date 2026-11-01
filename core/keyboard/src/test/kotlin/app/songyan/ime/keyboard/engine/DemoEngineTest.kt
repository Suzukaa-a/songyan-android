package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ported from KeyboardCoreTests.KeyboardCoreTests: the demo engine's code projection is checked
 * against the builtin package fixture values (26-key / 14-key / 9-key codes of the same words).
 */
class DemoEngineTest {
    private fun codes(
        text: String,
        pinyin: List<String>,
    ): Map<String, String> =
        mapOf(
            "26" to LayoutCodeProjector.composeCode(pinyin, LayoutCodeProjector.Encoding.FULL_PINYIN),
            "flypy26" to LayoutCodeProjector.composeCode(pinyin, LayoutCodeProjector.Encoding.XIAOHE26),
            "flypy14" to LayoutCodeProjector.composeCode(pinyin, LayoutCodeProjector.Encoding.XIAOHE14),
            "t9" to LayoutCodeProjector.composeCode(pinyin, LayoutCodeProjector.Encoding.PHONE_DIGITS),
        )

    @Test
    fun helloAgainstFixture() {
        assertEquals(
            mapOf("26" to "nihao", "flypy26" to "nihc", "flypy14" to "bugc", "t9" to "64426"),
            codes("你好", listOf("ni", "hao")),
        )
    }

    @Test
    fun chineseAgainstFixture() {
        assertEquals(
            mapOf("26" to "zhongguo", "flypy26" to "vsgo", "flypy14" to "cago", "t9" to "94664486"),
            codes("中国", listOf("zhong", "guo")),
        )
    }

    @Test
    fun learningAndWork() {
        assertEquals(
            mapOf("26" to "xuexi", "flypy26" to "xtxi", "flypy14" to "ztzu", "t9" to "98394"),
            codes("学习", listOf("xue", "xi")),
        )
        assertEquals(
            mapOf("26" to "gongzuo", "flypy26" to "gszo", "flypy14" to "gazo", "t9" to "4664986"),
            codes("工作", listOf("gong", "zuo")),
        )
    }

    @Test
    fun keyboardFixture() {
        assertEquals(
            mapOf("26" to "jianpan", "flypy26" to "jmpj", "flypy14" to "jmoj", "t9" to "5426726"),
            codes("键盘", listOf("jian", "pan")),
        )
    }

    @Test
    fun bankAndGrowth() {
        assertEquals(
            mapOf("26" to "yinhang", "flypy26" to "ybhh", "flypy14" to "tbgg", "t9" to "9464264"),
            codes("银行", listOf("yin", "hang")),
        )
        assertEquals(
            mapOf("26" to "zhangda", "flypy26" to "vhda", "flypy14" to "cgda", "t9" to "9426432"),
            codes("长大", listOf("zhang", "da")),
        )
    }

    @Test
    fun t9Groups() {
        assertEquals(
            "68424",
            LayoutCodeProjector.composeCode(listOf("nv", "hai"), LayoutCodeProjector.Encoding.PHONE_DIGITS),
        )
        assertEquals(
            "37983",
            LayoutCodeProjector.composeCode(listOf("er", "yue"), LayoutCodeProjector.Encoding.PHONE_DIGITS),
        )
    }

    @Test
    fun singleSyllableEdges() {
        assertEquals("ul", LayoutCodeProjector.codeForSyllable("shuang", LayoutCodeProjector.Encoding.XIAOHE26))
        assertEquals("ll", LayoutCodeProjector.codeForSyllable("liang", LayoutCodeProjector.Encoding.XIAOHE26))
        assertEquals("er", LayoutCodeProjector.codeForSyllable("er", LayoutCodeProjector.Encoding.XIAOHE26))
        assertEquals("mf", LayoutCodeProjector.codeForSyllable("men", LayoutCodeProjector.Encoding.XIAOHE26))
        // ch -> i, e kept; then the 14-key fold maps i -> u.
        assertEquals("ue", LayoutCodeProjector.codeForSyllable("che", LayoutCodeProjector.Encoding.XIAOHE14))
    }

    @Test
    fun demoEngineComposesAndCommits() =
        runTest {
            val engine = DemoEngine()
            engine.setup("/tmp/shared", "/tmp/user")
            "nihao".forEach { engine.processKey(it.toString()) }
            val composing = engine.currentSnapshot()
            assertTrue(composing.isComposing)
            assertEquals("nihao", composing.rawInput)
            assertTrue(composing.candidates.any { it.text == "你好" })

            val selected = engine.selectCandidate(0)
            assertEquals("你好", selected.committed)
            assertFalse(engine.currentSnapshot().isComposing)
            assertEquals("a read must not replay the commit", "", engine.currentSnapshot().committed)
        }

    @Test
    fun demoEngineSpaceCommitsTheFirstCandidate() =
        runTest {
            val engine = DemoEngine()
            engine.setup("/tmp/shared", "/tmp/user")
            "zhongguo".forEach { engine.processKey(it.toString()) }
            val committed = engine.processKey(EngineBackend.SPACE_KEY).committed
            assertEquals("中国", committed)
        }

    @Test
    fun demoEngineFourteenKeyUsesTheMergedCode() =
        runTest {
            val engine = DemoEngine()
            engine.setup("/tmp/shared", "/tmp/user")
            engine.selectSchema(
                BuiltinTemplateSchemas.engineSchemaID(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.MERGED14)!!,
            )
            // 你好 in 14-key full pinyin folds to bugao (ni hao -> b u g c ... only letters the fold keeps).
            val code =
                LayoutCodeProjector.composeCode(
                    listOf("ni", "hao"),
                    LayoutCodeProjector.Encoding.forSchemaID(
                        BuiltinTemplateSchemas.engineSchemaID(
                            BuiltinTemplateSchemas.QUANPIN,
                            KeyboardLayoutKind.MERGED14,
                        )!!,
                    )!!,
                )
            code.forEach { engine.processKey(it.toString()) }
            assertEquals(code, engine.currentSnapshot().rawInput)
            assertTrue(engine.currentSnapshot().candidates.any { it.text == "你好" })
        }

    @Test
    fun demoEngineNineKeyIgnoresKeyOne() =
        runTest {
            val engine = DemoEngine()
            engine.setup("/tmp/shared", "/tmp/user")
            engine.selectSchema(
                BuiltinTemplateSchemas.engineSchemaID(BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.PHONE9)!!,
            )
            engine.processKey("1")
            assertEquals("", engine.currentSnapshot().rawInput)
            engine.processKey("6")
            engine.processKey("4")
            assertEquals("64", engine.currentSnapshot().rawInput)
        }

    @Test
    fun demoEngineIsLabeledAsDemo() {
        val engine = DemoEngine()
        assertEquals(EngineBackendKind.DEMO_SIMULATOR, engine.kind)
        assertFalse(engine.kind.isRealRime)
    }

    @Test
    fun unknownSchemaIsRejected() =
        runTest {
            val engine = DemoEngine()
            var thrown = false
            try {
                engine.selectSchema("not_a_schema")
            } catch (error: EngineBackendError.UnsupportedSchema) {
                thrown = true
            }
            assertTrue(thrown)
        }
}
