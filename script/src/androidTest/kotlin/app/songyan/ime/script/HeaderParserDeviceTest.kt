package app.songyan.ime.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets

/**
 * The header parse the proxy runs on every add, edit, update and run. It must use an entry point
 * that release libraries export: it once went through the CLI, which only Debug libraries contain,
 * so every import on a release build failed with an empty report.
 */
@RunWith(AndroidJUnit4::class)
class HeaderParserDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    private fun asset(path: String): ByteArray = context.assets.open(path).use { it.readBytes() }

    @Test
    fun theBundledExampleParses() {
        val header = HeaderParser.parse(context, asset("script/examples/minimal.js"))

        assertTrue("got $header", header is ScriptHeader.Parsed)
    }

    @Test
    fun aBrokenHeaderIsRefusedWithItsLine() {
        val header = HeaderParser.parse(context, "// @name x\n// @bogus y\nconsole.log(1)\n".toByteArray())

        header as ScriptHeader.Refused
        assertEquals("badHeader", header.code)
        assertEquals(2, header.line)
    }

    /** The production entry reports what the shared contract's golden files say, case by case. */
    @Test
    fun everyContractCaseMatchesTheGoldenHeader() {
        val cases =
            context.assets
                .list("script-cases")!!
                .filter { it != "cases" }
                .sorted()
        assertEquals(39, cases.size)
        cases.forEach { case ->
            val script =
                runCatching { asset("script-cases/$case/script.js") }
                    .getOrElse { asset("script/examples/$case.js") }
            val golden = asset("script-cases/$case/expected.json").toString(StandardCharsets.UTF_8)
            assertEquals(case, ScriptProxyProto.headerFromCliReport(golden), HeaderParser.parse(context, script))
        }
    }

    /** The shape of the original failure: the child answered with an error and no report. */
    @Test
    fun aFailedAnswerIsUnavailableNotAParseError() {
        val failed = HeaderParser.fromAnswer(Triple(-1, "", "java.lang.UnsatisfiedLinkError: runCli"))
        val silent = HeaderParser.fromAnswer(null)
        val garbled = HeaderParser.fromAnswer(Triple(0, "{", ""))

        listOf(failed, silent, garbled).forEach { header ->
            header as ScriptHeader.Refused
            assertEquals("unavailable", header.code)
        }
    }
}
