package app.songyan.ime.ime.voice

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.EditorConnection
import app.songyan.ime.ime.EditorPolicy
import app.songyan.ime.ime.KeyboardController
import app.songyan.ime.ime.KeyboardPreferencesSource
import app.songyan.ime.ime.R
import app.songyan.ime.ime.ToolbarView
import app.songyan.ime.ime.keyboardCallbacks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The whole keyboard window chain for voice input, without the microphone: a real
 * [KeyboardController], the real toolbar view and its click routing, the real controller over a
 * platform [EditText] editor, and a stand-in host whose recording, permission answer and script are
 * controlled by the test.
 *
 * The hardware path (AudioRecord, RECORD_AUDIO, the script proxy) is covered by the app module's
 * voice device tests; this one proves the microphone appears in the toolbar exactly when voice is
 * on and a script is picked, that pressing it records, that a granted permission answer starts the
 * take it was waiting for, that a refused one leaves a message, that the host's cap transcribes a
 * take by itself, and that the undo slot takes the committed text back.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInputToolbarDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val preferences = FakePreferences()
    private val messages = mutableListOf<String>()

    private lateinit var activeConnection: InputConnection
    private val editor = EditorConnection { activeConnection }

    private inner class Field(
        val edit: EditText,
        val info: EditorInfo,
    )

    @After
    fun tearDown() {
        scope.cancel()
    }

    private fun field(
        text: String,
        inputType: Int = InputType.TYPE_CLASS_TEXT,
    ): Field {
        lateinit var result: Field
        instrumentation.runOnMainSync {
            val edit = EditText(context)
            edit.setInputType(inputType)
            edit.setText(text)
            edit.setSelection(text.length)
            val info = EditorInfo()
            info.inputType = inputType
            info.packageName = context.packageName
            info.fieldId = 11
            info.initialSelStart = edit.selectionStart
            info.initialSelEnd = edit.selectionEnd
            activeConnection = requireNotNull(edit.onCreateInputConnection(info))
            result = Field(edit, info)
            editor.startInput(info, false)
        }
        return result
    }

    private fun controller(host: VoiceInputHost): KeyboardController {
        lateinit var created: KeyboardController
        instrumentation.runOnMainSync {
            created =
                KeyboardController(
                    context,
                    scope,
                    editor.keyboardCallbacks(action = {}),
                    preferences = preferences,
                    voiceInputHost = host,
                    onVoiceMessage = { messages += it },
                )
        }
        return created
    }

    private fun configure(
        controller: KeyboardController,
        target: Field,
        script: String? = "dictation",
    ) {
        instrumentation.runOnMainSync { controller.configure(EditorPolicy.from(target.info)) }
        preferences.scriptId.value = script
        preferences.enabled.value = true
    }

    private fun awaitAction(
        controller: KeyboardController,
        action: ToolbarView.Action,
    ): Boolean {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            instrumentation.waitForIdleSync()
            if (controller.toolbar.actions.contains(action)) return true
            Thread.sleep(20)
        }
        return controller.toolbar.actions.contains(action)
    }

    private fun awaitText(
        target: Field,
        text: String,
    ) {
        val deadline = System.currentTimeMillis() + 6_000
        while (System.currentTimeMillis() < deadline && !target.edit.text.endsWith(text)) {
            instrumentation.waitForIdleSync()
            Thread.sleep(20)
        }
        assertTrue("the result must reach the editor, had: " + target.edit.text, target.edit.text.endsWith(text))
    }

    private fun awaitMessage(message: String) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline && !messages.contains(message)) {
            instrumentation.waitForIdleSync()
            Thread.sleep(20)
        }
        assertTrue("the message must be shown: " + messages, messages.contains(message))
    }

    @Test
    fun theToolbarMicrophoneRecordsAndTheUndoSlotTakesTheTextBack() {
        val target = field("你好")
        val original = target.edit.text.toString()
        val host = FakeHost()
        val controller = controller(host)
        configure(controller, target)
        assertTrue("the microphone must appear in the toolbar", awaitAction(controller, ToolbarView.Action.VOICE_INPUT))

        instrumentation.runOnMainSync { controller.toolbar.onAction(ToolbarView.Action.VOICE_INPUT) }
        instrumentation.waitForIdleSync()
        assertEquals("the first press must start a take", 1, host.starts)

        instrumentation.runOnMainSync { controller.toolbar.onAction(ToolbarView.Action.VOICE_INPUT) }
        awaitText(target, host.transcript)
        assertEquals("the take must have been stopped", 1, host.stops)
        assertEquals("the script must have run", 1, host.runs)
        assertTrue(
            "after the commit the same slot offers undo",
            awaitAction(controller, ToolbarView.Action.VOICE_UNDO),
        )

        instrumentation.runOnMainSync { controller.toolbar.onAction(ToolbarView.Action.VOICE_UNDO) }
        instrumentation.waitForIdleSync()
        assertEquals("the undo must take the result back", original, target.edit.text.toString())
        assertTrue(
            "the microphone must come back after the undo",
            awaitAction(controller, ToolbarView.Action.VOICE_INPUT),
        )
        assertFalse(controller.toolbar.actions.contains(ToolbarView.Action.VOICE_UNDO))

        instrumentation.runOnMainSync { controller.retire() }
    }

    @Test
    fun aGrantedPermissionStartsTheTakeThatWasWaitingForIt() {
        val target = field("hey")
        val host = FakeHost(permission = false, grantsPermission = true)
        val controller = controller(host)
        configure(controller, target)

        instrumentation.runOnMainSync { controller.toolbar.onAction(ToolbarView.Action.VOICE_INPUT) }
        awaitMessage(context.getString(R.string.ime_voice_recording))
        assertEquals("the host must be asked for the permission once", 1, host.waits)
        assertEquals("the granted answer must start the take", 1, host.starts)

        instrumentation.runOnMainSync { controller.toolbar.onAction(ToolbarView.Action.VOICE_INPUT) }
        awaitText(target, host.transcript)
        instrumentation.runOnMainSync { controller.retire() }
    }

    @Test
    fun aRefusedPermissionLeavesTheReasonAndStartsNothing() {
        val target = field("hey")
        val host = FakeHost(permission = false, grantsPermission = false)
        val controller = controller(host)
        configure(controller, target)

        instrumentation.runOnMainSync { controller.toolbar.onAction(ToolbarView.Action.VOICE_INPUT) }
        awaitMessage(context.getString(R.string.ime_voice_permission_needed))
        assertEquals("the permission must be asked for", 1, host.waits)
        assertEquals("nothing may record without it", 0, host.starts)
        assertFalse(host.recording)

        instrumentation.runOnMainSync { controller.retire() }
    }

    @Test
    fun theHostCapTranscribesTheTakeWithoutASecondPress() {
        val target = field("cap")
        val host = FakeHost(maxTakeMillis = 200)
        val controller = controller(host)
        configure(controller, target)

        instrumentation.runOnMainSync { controller.toolbar.onAction(ToolbarView.Action.VOICE_INPUT) }
        instrumentation.waitForIdleSync()
        assertEquals(1, host.starts)
        // The controller must collect the take the recorder ended at the cap, not stay "recording".
        awaitText(target, host.transcript)
        assertEquals("the take must be stopped once", 1, host.stops)
        assertFalse(host.recording)
        assertTrue(
            "the committed text must offer undo",
            awaitAction(controller, ToolbarView.Action.VOICE_UNDO),
        )
        instrumentation.runOnMainSync { controller.retire() }
    }

    @Test
    fun aPasswordFieldHasNoMicrophoneInTheToolbar() {
        val target = field("secret", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val host = FakeHost()
        val controller = controller(host)
        configure(controller, target)
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
        instrumentation.waitForIdleSync()

        assertFalse(
            "a password field must not offer the microphone: " + controller.toolbar.actions,
            controller.toolbar.actions.contains(ToolbarView.Action.VOICE_INPUT),
        )
        assertFalse(controller.toolbar.actions.contains(ToolbarView.Action.VOICE_UNDO))
        instrumentation.runOnMainSync { controller.retire() }
    }

    @Test
    fun withoutAScriptOrWithVoiceOffTheToolbarHasNoMicrophone() {
        val target = field("hello")
        val host = FakeHost()
        val controller = controller(host)
        instrumentation.runOnMainSync { controller.configure(EditorPolicy.from(target.info)) }

        preferences.scriptId.value = "dictation"
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
        instrumentation.waitForIdleSync()
        assertFalse(
            "voice off must leave no microphone",
            controller.toolbar.actions.contains(ToolbarView.Action.VOICE_INPUT),
        )

        preferences.enabled.value = true
        preferences.scriptId.value = null
        instrumentation.waitForIdleSync()
        Thread.sleep(200)
        instrumentation.waitForIdleSync()
        assertFalse(
            "no chosen script must leave no microphone",
            controller.toolbar.actions.contains(ToolbarView.Action.VOICE_INPUT),
        )
        instrumentation.runOnMainSync { controller.retire() }
    }

    /** The stored settings, reduced to what the voice input reads. */
    private class FakePreferences : KeyboardPreferencesSource {
        val enabled = MutableStateFlow(false)
        val scriptId = MutableStateFlow<String?>(null)

        override val layoutId: Flow<String> = flowOf("qwerty26")
        override val activeSchemaId: Flow<String> = flowOf("songyan_quanpin")

        override suspend fun setLayoutId(id: String) = Unit

        override suspend fun setActiveSchemaId(id: String) = Unit

        override val voiceInputEnabled: Flow<Boolean> = enabled
        override val voiceInputScriptId: Flow<String?> = scriptId
    }

    /** Stands in for the app: recording, the permission answer and the transcript are controlled. */
    private class FakeHost(
        private val permission: Boolean = true,
        private val grantsPermission: Boolean = true,
        override val maxTakeMillis: Long = 60_000,
    ) : VoiceInputHost {
        val transcript = "语音上屏"
        var starts = 0
        var stops = 0
        var runs = 0
        var waits = 0
        var recording = false

        override fun script(id: String): VoiceScript = VoiceScript(id, "听写")

        override fun hasMicrophonePermission(): Boolean = permission

        override suspend fun awaitMicrophonePermission(): Boolean {
            waits += 1
            delay(20)
            return grantsPermission
        }

        override fun startRecording(): VoiceInputProblem? {
            starts += 1
            recording = true
            return null
        }

        override suspend fun stopRecording(): VoiceRecordingResult {
            stops += 1
            recording = false
            return VoiceRecordingResult.Recorded(ByteArray(44 + 320), durationMs = 100)
        }

        override fun cancelRecording() {
            recording = false
        }

        override suspend fun runAudio(
            scriptId: String,
            wav: ByteArray,
        ): VoiceScriptResult {
            runs += 1
            assertTrue("the script must receive the take", wav.size > 0)
            return VoiceScriptResult.Text(transcript)
        }
    }
}
