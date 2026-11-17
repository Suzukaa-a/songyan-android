package app.songyan.ime.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Every example in the guide a host hands out must import on that host, through the real header
 *  parse: a text-only host's guide has no voice script, an audio host's guide has one. */
@RunWith(AndroidJUnit4::class)
class ScriptGuideDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context

    /** An example starts at a "// @api" line and runs to the next blank line. */
    private fun examples(guide: String): List<String> {
        val found = mutableListOf<String>()
        var current: StringBuilder? = null
        for (line in guide.lines()) {
            if (current == null && line.startsWith("// @api ")) current = StringBuilder()
            if (current == null) continue
            if (line.isEmpty()) {
                found += current.toString()
                current = null
            } else {
                current.append(line).append('\n')
            }
        }
        current?.let { found += it.toString() }
        return found
    }

    private fun parsedExamples(capabilities: Set<ScriptHostCapability>): List<ScriptHeader.Parsed> {
        val guide = ScriptGuide.read(context.assets, capabilities)
        return examples(guide).map { source ->
            val header = HeaderParser.parse(context, source.toByteArray())
            assertTrue("refused: $header\n$source", header is ScriptHeader.Parsed)
            header as ScriptHeader.Parsed
        }
    }

    @Test
    fun aTextOnlyHostsGuideImportsAndHasNoVoiceScript() {
        val parsed = parsedExamples(ScriptHostCapability.DEFAULT)

        assertEquals(3, parsed.size)
        assertFalse(parsed.any { it.needsAudio })
    }

    @Test
    fun anAudioHostsGuideImportsIncludingTheVoiceScript() {
        val parsed = parsedExamples(setOf(ScriptHostCapability.TEXT, ScriptHostCapability.AUDIO))

        assertEquals(4, parsed.size)
        assertEquals(1, parsed.count { it.needsAudio })
    }
}
