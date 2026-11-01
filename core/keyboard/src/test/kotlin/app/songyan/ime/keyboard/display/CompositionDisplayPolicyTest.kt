package app.songyan.ime.keyboard.display

import app.songyan.ime.keyboard.engine.CompositionDisplayMode
import app.songyan.ime.keyboard.engine.EngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from KeyboardCoreTests.CompositionDisplayPolicyTests (strict decode + resolution). */
class CompositionDisplayPolicyTest {
    private fun capability(
        mode: String,
        policy: String = "unique_normal",
        adapter: String = "keyboard_x_14",
        syllables: String = "{\"zao\":\"zc\",\"shang\":\"uh\"}",
        separator: String = "'",
        version: Int = 1,
    ): SchemaDisplayCapability? =
        SchemaDisplayCapability.decode(
            """
            {"version":$version,"display_mode":"$mode","source_schema":"songyan_flypy",
             "translator_namespace":"translator","separator":"$separator",
             "mapping_policy":"$policy","adapter_schema":"$adapter","syllables":$syllables}
            """.trimIndent(),
        )

    @Test
    fun nativeModeKeepsTheEnginePreedit() {
        val resolved = requireNotNull(capability("native"))
        assertEquals(CompositionDisplayMode.NATIVE, resolved.mode)
        assertEquals(
            CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.MODE_NATIVE),
            CompositionDisplayPolicy.resolve(listOf("zao"), resolved),
        )
    }

    @Test
    fun canonicalJoinsTheCanonicalSyllables() {
        val resolved = requireNotNull(capability("canonical"))
        assertEquals(
            CompositionDisplayOutcome.Display("zao'shang"),
            CompositionDisplayPolicy.resolve(listOf("zao", "shang"), resolved),
        )
    }

    @Test
    fun sourceCodeMapsEveryTokenOrFallsBackAsAWhole() {
        val resolved = requireNotNull(capability("source_code"))
        assertEquals(
            CompositionDisplayOutcome.Display("zc'uh"),
            CompositionDisplayPolicy.resolve(listOf("zao", "shang"), resolved),
        )
        // One missing token falls the whole segment back; never a partial rewrite.
        assertEquals(
            CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.MISSING_SYLLABLE),
            CompositionDisplayPolicy.resolve(listOf("zao", "hao"), resolved),
        )
    }

    @Test
    fun reservedSourceRenderedModeFallsBackToNative() {
        val resolved = requireNotNull(capability("source_rendered"))
        assertEquals(
            CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.UNSUPPORTED_MODE),
            CompositionDisplayPolicy.resolve(listOf("zao"), resolved),
        )
    }

    @Test
    fun sourceCodeRequiresTheUniqueNormalPolicyAndAMapping() {
        assertNull(capability("source_code", policy = "other"))
        assertNull(capability("source_code", syllables = "{}"))
    }

    @Test
    fun canonicalRequiresAnAdapterSchema() {
        assertNull(capability("canonical", adapter = ""))
    }

    @Test
    fun unknownVersionAndModeDecodeToNoCapability() {
        assertNull(capability("canonical", version = 2))
        assertNull(capability("bogus"))
    }

    @Test
    fun emptyTokensAndSeparatorFallBack() {
        val canonical = requireNotNull(capability("canonical"))
        assertEquals(
            CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.EMPTY_TOKENS),
            CompositionDisplayPolicy.resolve(emptyList(), canonical),
        )
        assertNull("an empty separator is not a usable capability", capability("canonical", separator = ""))
    }

    @Test
    fun numericTransportOnlyInspectsRawDigits() {
        assertTrue(CompositionDisplayPolicy.isNumericTransport("64426"))
        assertFalse("engine spacing must not make it safe", CompositionDisplayPolicy.isNumericTransport("64 426"))
        assertFalse(CompositionDisplayPolicy.isNumericTransport(""))
        assertFalse(CompositionDisplayPolicy.isNumericTransport("nihao"))
    }

    @Test
    fun appliedWritesOnlyDisplayFieldsAndAuthorizesCanonical() {
        val base = EngineSnapshot(preedit = "64", rawInput = "64", isComposing = true)
        val capability = requireNotNull(capability("canonical"))
        val (snapshot, outcome) = CompositionDisplayPolicy.applied(base, listOf("ni"), capability, hasCapability = true)
        assertEquals(CompositionDisplayOutcome.Display("ni"), outcome)
        assertEquals("ni", snapshot.highlightedDisplay)
        assertEquals(CompositionDisplayMode.CANONICAL, snapshot.displayMode)
        assertTrue("canonical must not echo digit codes", snapshot.hidesNumericTransportCode)
        assertEquals("64", snapshot.preedit)
        assertEquals("64", snapshot.rawInput)
    }

    @Test
    fun appliedWithoutCapabilityRunsNativeAndClearsStaleDisplay() {
        val base =
            EngineSnapshot().copy(
                highlightedDisplay = "stale",
                displayMode = CompositionDisplayMode.SOURCE_CODE,
            )
        val (snapshot, outcome) = CompositionDisplayPolicy.applied(base, listOf("ni"), null, hasCapability = true)
        assertEquals(
            CompositionDisplayOutcome.Native(CompositionDisplayOutcome.Reason.NO_CAPABILITY),
            outcome,
        )
        assertEquals("", snapshot.highlightedDisplay)
        assertEquals(CompositionDisplayMode.NATIVE, snapshot.displayMode)
        assertTrue("the capability ruling still propagates", snapshot.hasDisplayCapability)
    }
}
