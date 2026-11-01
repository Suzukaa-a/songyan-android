package app.songyan.ime.content

import app.songyan.ime.ime.EditorPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickInsertPolicyTest {
    private fun editor(privateInput: Boolean) =
        EditorPolicy(
            numeric = false,
            ascii = false,
            privateInput = privateInput,
            action = 0,
        )

    @Test
    fun aNormalEditorMayRecordAndReplay() {
        val policy = QuickInsertPolicy.of(editor(privateInput = false))
        assertTrue(policy.mayRecordCommit())
        assertTrue(policy.mayReplay())
    }

    @Test
    fun aPrivateEditorNeverRecordsOrReplays() {
        val policy = QuickInsertPolicy.of(editor(privateInput = true))
        assertFalse(
            "a password field must not seed the repeat buffer",
            policy.mayRecordCommit(),
        )
        assertFalse(
            "a password field must not be offered the previous text",
            policy.mayReplay(),
        )
    }

    @Test
    fun aPrivateEditorAlwaysClearsTheBufferEvenOnAReusedSession() {
        val policy = QuickInsertPolicy.of(editor(privateInput = true))
        assertTrue(policy.mustResetOnNewSession(restarting = false))
        assertTrue(policy.mustResetOnNewSession(restarting = true))
    }

    @Test
    fun onlyANewSessionClearsTheBufferForANormalEditor() {
        val policy = QuickInsertPolicy.of(editor(privateInput = false))
        assertTrue(
            "a new field/host must not replay the previous one",
            policy.mustResetOnNewSession(restarting = false),
        )
        assertFalse(
            "showing the keyboard again for the same field keeps the target",
            policy.mustResetOnNewSession(restarting = true),
        )
    }
}
