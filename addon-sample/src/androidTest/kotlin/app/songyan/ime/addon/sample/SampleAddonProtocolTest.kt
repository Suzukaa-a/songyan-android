// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.addon.AddonBinding
import app.songyan.ime.addon.AddonConnectResult
import app.songyan.ime.addon.AddonHostClient
import app.songyan.ime.addon.AddonVoiceState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end checks of the addon protocol against the installed sample APK. The addon runs in its
 * own process, so every assertion here crosses a real Binder boundary.
 */
@RunWith(AndroidJUnit4::class)
class SampleAddonProtocolTest {
    private lateinit var client: AddonHostClient
    private val recorder = CallbackRecorder()

    @Before
    fun setUp() {
        client = AddonHostClient(testContext())
    }

    @After
    fun tearDown() {
        client.disconnect()
    }

    private fun connect(className: String): AddonConnectResult =
        client.connect(
            component = sampleComponent(className),
            hostInfo = testHostInfo(),
            listener = recorder,
            bindFlags = AddonBinding.IME_FLAGS,
            timeoutMillis = 5_000,
        )

    private fun connectSample(): app.songyan.ime.addon.AddonHandshake {
        val result = connect(SAMPLE_SERVICE)
        assertTrue("connect returned " + result, result is AddonConnectResult.Connected)
        return (result as AddonConnectResult.Connected).handshake
    }

    @Test
    fun negotiationListsTheSampleCapabilitiesAndTools() {
        val handshake = connectSample()
        assertEquals(1, handshake.protocolVersion)
        assertTrue(handshake.supportsPanel)
        assertTrue(handshake.supportsVoice)
        assertEquals(
            setOf("uppercase", "bookTitle", "delay", "crash"),
            handshake.tools.map { it.id }.toSet(),
        )
        assertNotNull(handshake.settingsComponent)
        assertEquals(handshake.tools.map { it.id }.toSet(), client.tools().map { it.id }.toSet())
    }

    @Test
    fun runsTheTwoTextToolsAcrossProcesses() {
        connectSample()
        assertTrue(client.runTextTool("run#upper", "uppercase", "hello 世界"))
        assertEquals(listOf("HELLO 世界"), recorder.awaitResults("run#upper"))

        assertTrue(client.runTextTool("run#book", "bookTitle", "abc"))
        assertEquals(listOf("《abc》"), recorder.awaitResults("run#book"))
    }

    @Test
    fun unknownToolIsReportedAsAFailureNotACrash() {
        connectSample()
        assertTrue(client.runTextTool("run#unknown", "doesNotExist", "x"))
        val failure = recorder.awaitFailure("run#unknown")
        assertNotNull(failure)
        assertEquals("notSupported", failure?.code?.wireName)
        assertTrue(!recorder.disconnected)
    }

    @Test
    fun voiceFlowReportsStatesAndFinalText() {
        connectSample()
        assertTrue(client.startVoice("voice#1"))
        val finalText = recorder.awaitVoiceFinal(5_000)
        assertEquals(sampleString("voice_final"), finalText)
        assertTrue(recorder.voiceStates.contains(AddonVoiceState.LISTENING))
        assertTrue(recorder.voiceStates.contains(AddonVoiceState.FINISHED))
        assertTrue(recorder.voiceTexts.any { it.second.not() })
    }

    @Test
    fun missingServiceIsReportedWithoutThrowing() {
        val result = client.connect(missingComponent(), testHostInfo(), recorder)
        assertTrue("connect returned " + result, result is AddonConnectResult.Unavailable)
        assertFalse(client.isConnected)
    }

    @Test
    fun incompatibleProtocolVersionIsNotRun() {
        val result = connect("app.songyan.ime.addon.sample.debug.FutureProtocolAddonService")
        assertTrue("connect returned " + result, result is AddonConnectResult.Incompatible)
        val handshake = (result as AddonConnectResult.Incompatible).handshake
        assertEquals(0, handshake.protocolVersion)
        assertEquals(99, handshake.supportedMinVersion)
        assertFalse(client.isConnected)
    }

    @Test
    fun anUntrustedCallerIsRejectedByTheReleasePolicy() {
        val result = connect("app.songyan.ime.addon.sample.debug.StrictCallerAddonService")
        assertTrue("connect returned " + result, result is AddonConnectResult.Incompatible)
        val handshake = (result as AddonConnectResult.Incompatible).handshake
        assertNotNull(handshake.incompatibleReason)
        assertFalse(client.isConnected)
    }

    @Test
    fun aTimedOutRunDoesNotPoisonLaterRuns() {
        connectSample()
        // The host policy times the run out after 300 ms; the addon answers after 2 s.
        assertTrue(client.runTextTool("run#slow", "delay", "x"))
        assertNull(recorder.awaitResults("run#slow", timeoutMillis = 300))

        // The same connection still runs a fast tool.
        assertTrue(client.runTextTool("run#fast", "uppercase", "still works"))
        assertEquals(listOf("STILL WORKS"), recorder.awaitResults("run#fast"))

        // A cancelled slow run never reports.
        assertTrue(client.runTextTool("run#cancelled", "delay", "y"))
        Thread.sleep(100)
        client.cancelRun("run#cancelled")
        Thread.sleep(2_500)
        assertNull(recorder.awaitResults("run#cancelled", timeoutMillis = 100))
        assertTrue(!recorder.disconnected)
    }

    @Test
    fun anAddonCrashLeavesTheHostUsableAndReconnectWorks() {
        connectSample()
        // The addon kills itself while serving this call, so the call itself may fail; what
        // matters is that the host notices the death and keeps working.
        client.runTextTool("run#crash", "crash", "x")
        assertTrue("the addon process did not die", recorder.awaitDisconnected(5_000))
        assertFalse(client.isConnected)
        // No call throws after the crash; they simply refuse.
        assertFalse(client.runTextTool("run#after", "uppercase", "x"))
        assertTrue(client.tools().isEmpty())

        val handshake = connectSample()
        assertEquals(1, handshake.protocolVersion)
        assertTrue(client.runTextTool("run#recovered", "uppercase", "back"))
        assertEquals(listOf("BACK"), recorder.awaitResults("run#recovered"))
    }

    @Test
    fun hostInfoNamesTheCallingPackage() {
        val info = testHostInfo()
        assertEquals(InstrumentationRegistry.getInstrumentation().context.packageName, info.hostPackage)
        assertTrue(info.capabilities.isNotEmpty())
    }
}
