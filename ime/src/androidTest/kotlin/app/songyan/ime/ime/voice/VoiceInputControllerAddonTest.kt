// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.ime.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The voice button with an extension as the selected source: Songyan starts the extension's flow,
 * shows the same recording/transcribing state, commits the final text with undo, and stays usable
 * when the extension refuses or dies. The recording itself belongs to the extension.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInputControllerAddonTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val addonId = "addon:app.sample.addon"

    private class FakeHost : VoiceInputHost {
        var provider: AddonVoiceProvider? = AddonVoiceProvider("addon:app.sample.addon", "Sample")
        var startProblem: VoiceInputProblem? = null
        var listener: AddonVoiceListener? = null
        var starts: Int = 0
        var stops: Int = 0
        var cancels: Int = 0

        override fun script(id: String): VoiceScript? = null

        override fun hasMicrophonePermission(): Boolean = false

        override fun startRecording(): VoiceInputProblem = VoiceInputProblem.NO_MICROPHONE

        override suspend fun stopRecording(): VoiceRecordingResult =
            VoiceRecordingResult.Failed(VoiceInputProblem.RECORDING_FAILED)

        override fun cancelRecording() = Unit

        override suspend fun runAudio(
            scriptId: String,
            wav: ByteArray,
        ): VoiceScriptResult = VoiceScriptResult.Failed(VoiceInputProblem.SCRIPT_FAILED, null)

        override fun addonProvider(id: String): AddonVoiceProvider? = provider?.takeIf { it.id == id }

        override fun startAddonVoice(
            providerId: String,
            listener: AddonVoiceListener,
        ): VoiceInputProblem? {
            starts += 1
            startProblem?.let { return it }
            this.listener = listener
            return null
        }

        override fun stopAddonVoice() {
            stops += 1
        }

        override fun cancelAddonVoice() {
            cancels += 1
        }
    }

    private class Recorder {
        val inserted: MutableList<String> = mutableListOf()
        val replaced: MutableList<Pair<String, String>> = mutableListOf()
        val messages: MutableList<String> = mutableListOf()
        var changes: Int = 0
    }

    private fun controller(
        host: FakeHost,
        recorder: Recorder,
    ): VoiceInputController =
        VoiceInputController(
            context = context,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            host = host,
            canWrite = { true },
            isComposing = { false },
            insert = { text ->
                recorder.inserted += text
                true
            },
            replaceBeforeCursor = { expected, replacement ->
                recorder.replaced += expected to replacement
                true
            },
            onChanged = { recorder.changes += 1 },
            onMessage = { recorder.messages += it },
        )

    @Test
    fun theExtensionFlowStartsOnTheButtonAndCommitsTheFinalText() {
        val host = FakeHost()
        val recorder = Recorder()
        val voice = controller(host, recorder)
        voice.configure(enabled = true, scriptId = addonId)
        assertTrue(voice.available())

        voice.onPressed()
        assertEquals(1, host.starts)
        assertEquals(VoiceInputController.State.RECORDING, voice.state)

        // Partial lines are shown by the toolbar state, not committed.
        host.listener?.onText("part", false)
        assertTrue(recorder.inserted.isEmpty())

        host.listener?.onText("你好", true)
        assertEquals(listOf("你好"), recorder.inserted)
        assertEquals(VoiceInputController.State.UNDOABLE, voice.state)
        assertTrue(voice.undoAvailable())

        voice.undo()
        assertEquals(listOf("你好" to ""), recorder.replaced)
        assertEquals(VoiceInputController.State.IDLE, voice.state)
    }

    @Test
    fun aSecondPressStopsTheExtensionFlowInsteadOfRecordingLocally() {
        val host = FakeHost()
        val recorder = Recorder()
        val voice = controller(host, recorder)
        voice.configure(enabled = true, scriptId = addonId)
        voice.onPressed()
        voice.onPressed()
        assertEquals(1, host.stops)
        assertEquals(VoiceInputController.State.TRANSCRIBING, voice.state)
        host.listener?.onText("好的", true)
        assertEquals(listOf("好的"), recorder.inserted)
        assertTrue(recorder.messages.any { it.isNotEmpty() })
    }

    @Test
    fun anExtensionThatRefusesLeavesTheButtonUsable() {
        val host = FakeHost().apply { startProblem = VoiceInputProblem.SCRIPT_FAILED }
        val recorder = Recorder()
        val voice = controller(host, recorder)
        voice.configure(enabled = true, scriptId = addonId)
        voice.onPressed()
        assertEquals(VoiceInputController.State.IDLE, voice.state)
        assertTrue(recorder.messages.isNotEmpty())
        assertTrue("the button must stay available after a refusal", voice.available())
    }

    @Test
    fun anExtensionThatDiesReportsAndKeepsTheKeyboardUsable() {
        val host = FakeHost()
        val recorder = Recorder()
        val voice = controller(host, recorder)
        voice.configure(enabled = true, scriptId = addonId)
        voice.onPressed()
        host.listener?.onFailed(VoiceInputProblem.SCRIPT_FAILED)
        assertEquals(VoiceInputController.State.IDLE, voice.state)
        assertFalse(voice.undoAvailable())
        assertTrue(recorder.inserted.isEmpty())
        assertEquals(VoiceInputController.State.IDLE, voice.state)
    }

    @Test
    fun aMissingExtensionMakesTheButtonUnavailable() {
        val host = FakeHost().apply { provider = null }
        val voice = controller(host, Recorder())
        voice.configure(enabled = true, scriptId = addonId)
        assertFalse(voice.available())
        voice.onPressed()
        assertEquals(0, host.starts)
        assertEquals(VoiceInputController.State.IDLE, voice.state)
    }

    @Test
    fun theEditorChangeDropsTheRunningExtensionFlow() {
        val host = FakeHost()
        val recorder = Recorder()
        val voice = controller(host, recorder)
        voice.configure(enabled = true, scriptId = addonId)
        voice.onPressed()
        voice.onEditorChanged()
        assertEquals(1, host.cancels)
        assertEquals(VoiceInputController.State.IDLE, voice.state)
    }
}
