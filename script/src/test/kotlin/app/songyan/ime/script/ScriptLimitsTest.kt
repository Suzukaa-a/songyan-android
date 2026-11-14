package app.songyan.ime.script

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The shipped limits live in the synced contract header; this keeps the Kotlin copy honest. */
class ScriptLimitsTest {
    private fun header(): String {
        var dir = File(System.getProperty("user.dir")).absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/main/cpp/include/songyan_script.h")
            if (candidate.isFile) return candidate.readText()
            dir = dir.parentFile ?: return@repeat
        }
        error("cannot locate the synced contract header")
    }

    private fun macro(
        text: String,
        name: String,
    ): Long {
        val match = Regex("#define\\s+$name\\s+(\\d+)u").find(text) ?: error("no $name in the header")
        return match.groupValues[1].toLong()
    }

    @Test
    fun theKotlinCopyMatchesTheContractHeader() {
        val text = header()
        assertEquals(macro(text, "SONGYAN_SCRIPT_SHIPPED_SOURCE_BYTES"), ScriptLimits.SOURCE_BYTES)
        assertEquals(macro(text, "SONGYAN_SCRIPT_SHIPPED_HEAP_BYTES"), ScriptLimits.HEAP_BYTES)
        assertEquals(macro(text, "SONGYAN_SCRIPT_SHIPPED_DURATION_MS"), ScriptLimits.DURATION_MS)
        assertEquals(macro(text, "SONGYAN_SCRIPT_SHIPPED_COMPUTE_MS"), ScriptLimits.COMPUTE_MS)
        assertEquals(macro(text, "SONGYAN_SCRIPT_SHIPPED_REQUEST_COUNT"), ScriptLimits.REQUEST_COUNT)
        assertEquals(macro(text, "SONGYAN_SCRIPT_SHIPPED_RESPONSE_BYTES"), ScriptLimits.RESPONSE_BYTES)
        assertEquals(macro(text, "SONGYAN_SCRIPT_SHIPPED_INPUT_TEXT_BYTES"), ScriptLimits.INPUT_TEXT_BYTES)
        assertEquals(macro(text, "SONGYAN_SCRIPT_MAX_TIMEOUT_SECONDS"), ScriptLimits.MAX_TIMEOUT_SECONDS)
    }
}
