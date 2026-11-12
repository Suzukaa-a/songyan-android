package app.songyan.ime.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The S2 flow against the real processes: the proxy owns storage and secrets, the isolated
 *  process runs the engine, and the public registry drives both over Binder. */
@RunWith(AndroidJUnit4::class)
class ScriptProxyFlowTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry = ScriptProxyRegistry(context)

    @Test
    fun importConfirmEnableAndRunThroughTheRealProcesses() =
        runBlocking {
            val quote = '\u0022'
            val bookend =
                listOf(
                    "// @api 1",
                    "// @name 加书名号",
                    "// @description 把选中的文字两边加上书名号",
                    "export default async ({ text }) => " + quote + "\u300c" + quote + " + text + " + quote + "\u300d" +
                        quote +
                        ";",
                )
            val source = bookend.joinToString("\n").toByteArray(Charsets.UTF_8)

            val header = registry.inspect(source)
            assertTrue("the header parses: " + header, header is ScriptHeader.Parsed)

            val entry = registry.add(source, ScriptSourceOrigin.Pasted)
            assertEquals("加书名号", entry.name)
            assertTrue("fresh imports wait for confirmation", entry.confirmationPending)

            val confirmed = registry.confirm(entry.id, emptyMap())
            assertTrue("confirmation clears the pending flag", !confirmed.confirmationPending)

            val enabled = registry.setEnabled(entry.id, true)
            assertTrue(enabled.enabled)

            val outcome = registry.runForTest(entry.id, "测试")
            assertTrue("the run succeeds: " + outcome, outcome is ScriptRunOutcome.Success)
            assertEquals(listOf("「测试」"), (outcome as ScriptRunOutcome.Success).outputs)

            registry.delete(entry.id)
            assertTrue(registry.entries.none { it.id == entry.id })
        }

    @Test
    fun refusedHeaderSurfacesTheContractCode() =
        runBlocking {
            val header = registry.inspect("// @name no api here".toByteArray())
            assertTrue(header is ScriptHeader.Refused)
            assertEquals("badHeader", (header as ScriptHeader.Refused).code)
        }

    @Test
    fun secretsStayOutOfTheStateFile() =
        runBlocking {
            val quote = '\u0022'
            val secret =
                listOf(
                    "// @api 1",
                    "// @name 带密钥",
                    "// @secret key 密钥",
                    "export default async () => " + quote + "x" + quote + ";",
                )
            val source = secret.joinToString("\n").toByteArray(Charsets.UTF_8)
            val entry = registry.add(source, ScriptSourceOrigin.Pasted)
            registry.confirm(entry.id, mapOf("key" to "topsecret"))
            val state = java.io.File(context.filesDir, "script/scripts/" + entry.id + "/state.json").readText()
            assertTrue("state.json must not contain the secret", !state.contains("topsecret"))
            assertTrue(
                "the secret blob exists",
                java.io.File(context.filesDir, "script/scripts/" + entry.id + "/secret.bin").isFile,
            )
            registry.delete(entry.id)
        }
}
