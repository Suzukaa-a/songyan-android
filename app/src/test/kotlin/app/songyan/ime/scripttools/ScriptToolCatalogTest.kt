package app.songyan.ime.scripttools

import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptSourceOrigin
import app.songyan.ime.tools.ToolCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which stored scripts become text tools for the keyboard panel. */
class ScriptToolCatalogTest {
    private fun entry(
        id: String,
        name: String,
        needsAudio: Boolean = false,
        enabled: Boolean = true,
        available: Boolean = true,
        pending: Boolean = false,
    ) = ScriptEntry(
        id = id,
        name = name,
        description = null,
        enabled = enabled,
        order = 0,
        apiVersion = 1,
        connects = emptyList(),
        needsAudio = needsAudio,
        availableOnThisHost = available,
        confirmationPending = pending,
        settings = emptyList(),
        sourceOrigin = ScriptSourceOrigin.Pasted,
    )

    @Test
    fun aVoiceScriptIsNotATextTool() {
        val catalog =
            ScriptToolCatalog {
                listOf(
                    entry("text", "加书名号"),
                    entry("voice", "听写", needsAudio = true),
                )
            }

        val tools = catalog.tools()
        assertEquals(
            "only the text script is a text tool: " + tools,
            listOf("script:text"),
            tools.map { it.id },
        )
        assertEquals(listOf("加书名号"), tools.map { it.title })
        assertTrue(
            "text tools still declare text input",
            tools.single().requiredCapabilities == setOf(ToolCapability.TEXT),
        )
    }

    @Test
    fun aVoiceScriptStaysOutEvenWhenTheHostOffersAudio() {
        // :app installs {TEXT, AUDIO}, so availableOnThisHost is true for a voice script; the text
        // panel still cannot feed it a recording and must not offer it.
        val catalog =
            ScriptToolCatalog {
                listOf(entry("voice", "听写", needsAudio = true, available = true))
            }
        assertTrue("a voice script is never a text tool", catalog.tools().isEmpty())
    }

    @Test
    fun disabledPendingOrUnavailableScriptsAreLeftOut() {
        val catalog =
            ScriptToolCatalog {
                listOf(
                    entry("disabled", "A", enabled = false),
                    entry("pending", "B", pending = true),
                    entry("unavailable", "C", available = false),
                    entry("ready", "D"),
                )
            }
        assertEquals(listOf("script:ready"), catalog.tools().map { it.id })
    }
}
