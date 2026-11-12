package app.songyan.ime.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Rule 4 of the script foundation: the engine library may only load in an isolated process. This
 * test runs in the normal instrumentation process (not isolated), so the load must fail, and the
 * failure must be the guard in JNI_OnLoad - not a missing library or an ABI mismatch, which would
 * make this test green for the wrong reason. */
@RunWith(AndroidJUnit4::class)
class ScriptLoadGuardTest {
    @Test
    fun libraryRefusesToLoadOutsideAnIsolatedProcess() {
        val error = runCatching { System.loadLibrary("songyan-script") }.exceptionOrNull()
        assertTrue("the load must fail", error is UnsatisfiedLinkError)
        val text = (error?.message ?: "") + " " + (error?.cause?.message ?: "")
        // ART reports a failing JNI_OnLoad as "Failed to call JNI_OnLoad..." (or "returned JNI_ERR");
        // both name JNI_OnLoad, while a missing file or ABI problem names the library path instead.
        assertTrue(
            "the rejection must name JNI_OnLoad, got: " + text,
            text.contains("JNI_OnLoad"),
        )
    }

    @Test
    fun isolationStatusIsFalseHere() {
        // The instrumentation process is deliberately a normal one; this documents the premise of
        // the guard test above (an isolated process would report true).
        assertTrue(!android.os.Process.isIsolated())
    }
}
