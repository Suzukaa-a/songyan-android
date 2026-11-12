// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.voice.AddonVoiceListener
import app.songyan.ime.ime.voice.VoiceInputProblem
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The extension voice access point with the sample addon installed as a second APK: Songyan binds
 * while the flow runs, the addon records and recognizes itself, the final line reaches the host,
 * a stopped flow still produces its final line, and a dead addon becomes a typed failure.
 */
@RunWith(AndroidJUnit4::class)
class AddonVoiceDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val samplePackage = "app.songyan.ime.addon.sample"
    private val providerId = AddonVoiceIds.of(samplePackage)

    private fun store(): AddonStore = AddonStore(File(targetContext.filesDir, "addons"))

    private class Recorder : AddonVoiceListener {
        val handler = Handler(Looper.getMainLooper())
        val finals: MutableList<String> = mutableListOf()
        val partials: MutableList<String> = mutableListOf()
        var failure: VoiceInputProblem? = null

        override fun onText(
            text: String,
            isFinal: Boolean,
        ) {
            handler.post {
                if (isFinal) finals += text else partials += text
            }
        }

        override fun onFailed(problem: VoiceInputProblem) {
            handler.post { failure = problem }
        }
    }

    @Before
    fun requireTheSampleInstalled() {
        val installed = runCatching { context.packageManager.getPackageInfo(samplePackage, 0) }.isSuccess
        assumeTrue(
            "install the sample addon first: adb install -r addon-sample/build/outputs/apk/debug/addon-sample-debug.apk",
            installed,
        )
        store().write(StoredAddonState())
    }

    private fun enabledRegistry(): AddonRegistry =
        runBlocking {
            val registry = createAddonRegistry(targetContext)
            registry.scan(negotiate = true)
            registry.setEnabled(samplePackage, true)
            registry
        }

    @Test
    fun theExtensionVoiceFlowProducesFinalTextAndIsRecorded() {
        val registry = enabledRegistry()
        val session = AddonVoiceSession(targetContext, registry)
        val recorder = Recorder()
        val provider = session.provider(providerId)
        assertNotNull("the enabled sample addon must offer voice", provider)
        assertNull(session.start(providerId, recorder))

        assertTrue("the extension sent no final text", waitFor { recorder.finals.isNotEmpty() })
        assertTrue(recorder.finals.first().isNotBlank())
        assertTrue("the extension never reported a partial line", recorder.partials.isNotEmpty())
        assertNull(recorder.failure)
        assertTrue(
            "the voice call must reach the registry",
            waitFor {
                registry
                    .entries()
                    .singleOrNull { it.packageName == samplePackage }
                    ?.lastCall
                    ?.kind ==
                    AddonCallKinds.VOICE
            },
        )
    }

    @Test
    fun stoppingTheFlowStillDeliversItsFinalLine() {
        val registry = enabledRegistry()
        val session = AddonVoiceSession(targetContext, registry)
        val recorder = Recorder()
        assertNull(session.start(providerId, recorder))
        session.stop()
        assertTrue("stop did not produce the final line", waitFor { recorder.finals.isNotEmpty() })
        assertTrue(recorder.finals.first().isNotBlank())
    }

    @Test
    fun aKilledAddonBecomesATypedFailure() {
        val registry = enabledRegistry()
        val session = AddonVoiceSession(targetContext, registry)
        val recorder = Recorder()
        assertNull(session.start(providerId, recorder))
        // Let the flow start, then take the extension's process away.
        assertTrue(waitFor { session.provider(providerId) != null })
        val killed =
            InstrumentationRegistry
                .getInstrumentation()
                .uiAutomation
                .executeShellCommand("am force-stop " + samplePackage)
        killed.close()
        assertTrue("the death never reached the keyboard", waitFor { recorder.failure != null })
        assertTrue(
            "the failed voice call must be recorded",
            waitFor {
                registry
                    .entries()
                    .singleOrNull { it.packageName == samplePackage }
                    ?.lastCall
                    ?.error != null
            },
        )
        session.cancel()
    }

    @Test
    fun aDisabledExtensionCannotStartVoice() {
        val registry = enabledRegistry()
        runBlocking { registry.setEnabled(samplePackage, false) }
        val session = AddonVoiceSession(targetContext, registry)
        assertNull(session.provider(providerId))
        assertEquals(
            VoiceInputProblem.SCRIPT_FAILED,
            session.start(providerId, Recorder()),
        )
    }

    private fun waitFor(
        timeoutMillis: Long = 10_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            if (condition()) return true
            SystemClock.sleep(25)
        }
        return condition()
    }
}
