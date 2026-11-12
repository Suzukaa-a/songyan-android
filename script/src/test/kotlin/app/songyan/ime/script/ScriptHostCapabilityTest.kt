package app.songyan.ime.script

import org.junit.Assert.assertEquals
import org.junit.Test

/** The capability model the proxy declares on `hello` and the catalog answers from. */
class ScriptHostCapabilityTest {
    @Test
    fun aScriptRequiresTextAlwaysAndAudioWhenItsHeaderDeclaresIt() {
        assertEquals(setOf(ScriptHostCapability.TEXT), ScriptHostCapability.required(needsAudio = false))
        assertEquals(
            setOf(ScriptHostCapability.TEXT, ScriptHostCapability.AUDIO),
            ScriptHostCapability.required(needsAudio = true),
        )
    }

    @Test
    fun wireNamesRoundTripAndUnknownNamesAreDropped() {
        val declared = setOf(ScriptHostCapability.TEXT, ScriptHostCapability.AUDIO)
        assertEquals(declared, ScriptHostCapability.parse(ScriptHostCapability.names(declared)))
        // A newer host may declare capabilities this proxy does not know; they are ignored rather
        // than breaking the connection, and a declaration with no known name offers nothing.
        assertEquals(declared, ScriptHostCapability.parse(listOf("TEXT", "CAMERA", "AUDIO")))
        assertEquals(emptySet<ScriptHostCapability>(), ScriptHostCapability.parse(listOf("CAMERA")))
    }
}
