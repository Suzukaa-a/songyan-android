package app.songyan.ime.settings.ui

import app.songyan.ime.settings.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** Which words a script failure may show: fixed sentences, or a script's own error, never the
 *  English diagnostics of the engine, the proxy or the platform. */
class ScriptFailureCopyTest {
    @Test
    fun anUnknownCodeGetsTheGenericSentenceWithoutItsMessage() {
        assertEquals(
            ScriptFailureCopy(R.string.scripts_error_generic),
            scriptFailureCopy(
                "failed",
                "the proxy returned no entry: {\"error\":\"failed\",\"message\":\"Cannot read Json element\"}",
                0,
            ),
        )
    }

    @Test
    fun aKnownCodeIgnoresTheDiagnostic() {
        assertEquals(
            ScriptFailureCopy(R.string.scripts_error_unavailable),
            scriptFailureCopy("unavailable", "the isolated service did not answer", 0),
        )
        assertEquals(
            ScriptFailureCopy(R.string.scripts_error_download),
            scriptFailureCopy("downloadFailed", "HTTP 404", 0),
        )
        assertEquals(
            ScriptFailureCopy(R.string.scripts_error_timeout),
            scriptFailureCopy("timeout", "the script ran longer than 30000 ms", 12),
        )
    }

    @Test
    fun aHeaderRefusalKeepsItsLine() {
        assertEquals(
            ScriptFailureCopy(R.string.scripts_error_bad_header, line = 3),
            scriptFailureCopy("badHeader", "unknown declaration @foo", 3),
        )
    }

    @Test
    fun aScriptsOwnErrorIsShownWithItsLine() {
        assertEquals(
            ScriptFailureCopy(R.string.scripts_error_script, "401 密钥无效", 7),
            scriptFailureCopy("script", " 401 密钥无效 ", 7),
        )
    }

    @Test
    fun aScriptErrorWithoutWordsIsGeneric() {
        assertEquals(ScriptFailureCopy(R.string.scripts_error_generic), scriptFailureCopy("script", "  ", 2))
    }
}
