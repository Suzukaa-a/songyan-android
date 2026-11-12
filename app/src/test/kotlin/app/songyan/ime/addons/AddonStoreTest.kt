// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The extension state file: what the user turned on plus the last call per package. A damaged or
 * missing file must never break the keyboard; it starts from empty instead.
 */
class AddonStoreTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun store(): AddonStore = AddonStore(temporary.newFolder("addons"))

    @Test
    fun enabledCallsAndCachedToolsSurviveARoundTrip() {
        val store = store()
        val state =
            StoredAddonState(
                enabled = setOf("app.sample.addon"),
                calls =
                    mapOf(
                        "app.sample.addon" to
                            StoredAddonCall(
                                kind = AddonCallKinds.TEXT,
                                atEpochMs = 1_700_000_000_000,
                                durationMs = 42,
                                error = "boom",
                            ),
                    ),
                cached =
                    mapOf(
                        "app.sample.addon" to
                            StoredAddonTools(
                                tools = listOf(StoredAddonTool("uppercase", "Uppercase")),
                                supportsPanel = true,
                                supportsVoice = true,
                            ),
                    ),
            )
        store.write(state)
        assertEquals(state, store.read())
    }

    @Test
    fun aMissingFileReadsAsEmpty() {
        assertEquals(StoredAddonState(), store().read())
    }

    @Test
    fun aDamagedFileReadsAsEmptyInsteadOfFailing() {
        val directory = temporary.newFolder("addons")
        java.io.File(directory, "addons.json").writeText("{not json")
        assertEquals(StoredAddonState(), AddonStore(directory).read())
    }

    @Test
    fun unknownAndMissingKeysAreIgnored() {
        val directory = temporary.newFolder("addons")
        java.io.File(directory, "addons.json").writeText(
            "{\"enabled\":[\"pkg\"],\"future\":true,\"calls\":{\"pkg\":{\"kind\":\"text\"}}}",
        )
        val state = AddonStore(directory).read()
        assertEquals(setOf("pkg"), state.enabled)
        assertEquals(0L, state.calls.getValue("pkg").atEpochMs)
        assertTrue(state.cached.isEmpty())
    }

    @Test
    fun aWriteThatCannotReplaceTheFileReportsFailure() {
        val directory = temporary.newFolder("addons")
        // A directory where the state file belongs makes the rename fail, as a full disk would.
        java.io.File(directory, "addons.json").mkdirs()
        assertFalse(AddonStore(directory).write(StoredAddonState(enabled = setOf("pkg"))))
        assertEquals(StoredAddonState(), AddonStore(directory).read())
    }

    @Test
    fun writingAgainReplacesThePreviousState() {
        val store = store()
        store.write(StoredAddonState(enabled = setOf("one")))
        store.write(StoredAddonState(enabled = setOf("two")))
        assertEquals(setOf("two"), store.read().enabled)
    }
}
