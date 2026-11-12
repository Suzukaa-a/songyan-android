package app.songyan.ime.audio

import android.Manifest
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.EditorConnection
import app.songyan.ime.ime.EditorPolicy
import app.songyan.ime.ime.FieldKind
import app.songyan.ime.ime.keyboardCallbacks
import app.songyan.ime.ime.voice.VoiceInputController
import app.songyan.ime.ime.voice.VoiceInputHost
import app.songyan.ime.ime.voice.VoiceInputProblem
import app.songyan.ime.ime.voice.VoiceRecordingResult
import app.songyan.ime.ime.voice.VoiceScript
import app.songyan.ime.ime.voice.VoiceScriptResult
import app.songyan.ime.script.ScriptCatalogCache
import app.songyan.ime.script.ScriptHeader
import app.songyan.ime.script.ScriptRegistryHolder
import app.songyan.ime.script.ScriptSourceOrigin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The voice input pipeline on a device: the real microphone records a take, the installed audio
 * script reads its WAV header out of the ArrayBuffer, the text it returns reaches the editor and the
 * undo takes it back. A password field offers no microphone, and a refused permission starts nothing.
 *
 * The record test needs RECORD_AUDIO granted over adb first
 * (`adb -s <serial> shell pm grant app.songyan.ime.debug android.permission.RECORD_AUDIO`): granting
 * from inside the process kills it.
 */
@RunWith(AndroidJUnit4::class)
class VoiceInputPipelineDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val messages = mutableListOf<String>()

    private class Field(
        val edit: EditText,
        val info: EditorInfo,
        val connection: InputConnection,
    )

    private lateinit var activeConnection: InputConnection
    private val editor = EditorConnection { activeConnection }
    private val callbacks by lazy { editor.keyboardCallbacks(action = {}) }
    private var scriptId: String? = null

    @Before
    fun requireTheMicrophonePermission() {
        assertTrue(
            "grant RECORD_AUDIO first; adb -s <serial> shell pm grant ${context.packageName} " +
                Manifest.permission.RECORD_AUDIO,
            MicrophonePermission.isGranted(context),
        )
    }

    @After
    fun cleanUp() {
        scriptId?.let { id -> runBlocking { runCatching { ScriptRegistryHolder.current.delete(id) } } }
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
            info.fieldId = 7
            info.initialSelStart = edit.selectionStart
            info.initialSelEnd = edit.selectionEnd
            val connection = requireNotNull(edit.onCreateInputConnection(info))
            result = Field(edit, info, connection)
            activeConnection = connection
            editor.startInput(info, false)
        }
        return result
    }

    private fun controller(
        host: VoiceInputHost,
        target: Field,
    ): VoiceInputController =
        VoiceInputController(
            context = context,
            scope = scope,
            host = host,
            canWrite = { EditorPolicy.from(target.info).fieldKind != FieldKind.PASSWORD },
            isComposing = { editor.composing },
            insert = { text -> callbacks.commit(text, false) },
            replaceBeforeCursor = { expected, replacement -> callbacks.replaceBeforeCursor(expected, replacement) },
            onChanged = {},
            onMessage = { messages += it },
        )

    private fun installAudioScript(): String {
        // The fixture lives in the test APK; the target app only carries the production assets.
        val source =
            instrumentation.context.assets
                .open("voice/transcript.js")
                .use { it.readBytes() }
        val registry = ScriptRegistryHolder.current
        val header = runBlocking { registry.inspect(source) }
        assertTrue("the audio example parses: " + header, header is ScriptHeader.Parsed)
        assertTrue("the example declares audio input", (header as ScriptHeader.Parsed).needsAudio)
        return runBlocking {
            val added = registry.add(source, ScriptSourceOrigin.Pasted)
            registry.confirm(added.id, emptyMap())
            registry.setEnabled(added.id, true)
            added.id
        }
    }

    private fun awaitScript(
        host: VoiceInputHost,
        id: String,
    ): VoiceScript {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            host.script(id)?.let { return it }
            Thread.sleep(100)
        }
        throw AssertionError("the audio script never became available on this host")
    }

    @Test
    fun aRealTakeReachesTheAudioScriptAndItsTextIsCommittedAndUndone() {
        scriptId = installAudioScript()
        val target = field("你好")
        val original = target.edit.text.toString()
        val host = VoiceInputHostImpl(context, VoiceRecorder(context), ScriptCatalogCache(context))
        val script = awaitScript(host, scriptId!!)
        assertEquals(scriptId, script.id)

        val voice = controller(host, target)
        voice.configure(enabled = true, scriptId = scriptId)
        assertTrue("the microphone must be offered", voice.available())

        voice.onPressed()
        assertTrue("the take must be running", voice.isRecording())
        Thread.sleep(900)
        // The IME process names no network class (NetworkIsolationTest) and the audio run shares the
        // tool path's ScriptRegistry plumbing; this also keeps the UI thread itself free of network.
        val networkViolations = mutableListOf<String>()
        val previousPolicy = android.os.StrictMode.getThreadPolicy()
        android.os.StrictMode.setThreadPolicy(
            android.os.StrictMode.ThreadPolicy
                .Builder()
                .detectNetwork()
                .penaltyListener(context.mainExecutor) { violation ->
                    networkViolations +=
                        violation.stackTrace.toString()
                }.build(),
        )
        try {
            voice.onPressed()
            val deadline = System.currentTimeMillis() + 20_000
            while (!target.edit.text.contains("赫兹") && System.currentTimeMillis() < deadline) Thread.sleep(100)
        } finally {
            android.os.StrictMode.setThreadPolicy(previousPolicy)
        }
        assertTrue("the UI thread opened no network: " + networkViolations, networkViolations.isEmpty())
        val committed =
            target.edit.text
                .toString()
                .removePrefix(original)
        // The script read the real WAV the recorder produced: header rate and tag are in the text.
        assertTrue(
            "the script must see the recorded WAV, had: " + target.edit.text + " messages: " + messages,
            committed.contains("16000赫兹RIFF"),
        )
        val bytes =
            Regex("录音(\\d+)字节")
                .find(committed)
                ?.groupValues
                ?.get(1)
                ?.toLongOrNull()
        assertNotNull("the script must report the audio length: " + committed, bytes)
        assertTrue("the take must carry audio, had $bytes bytes", bytes!! > WavFile.HEADER_BYTES.toLong())
        assertTrue("the undo must be offered", voice.undoAvailable())

        voice.undo()
        assertEquals("the undo must take the result back", original, target.edit.text.toString())
        assertFalse(voice.undoAvailable())
    }

    @Test
    fun aPasswordFieldOffersNoMicrophone() {
        scriptId = installAudioScript()
        val target = field("secret", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val host = VoiceInputHostImpl(context, VoiceRecorder(context), ScriptCatalogCache(context))
        awaitScript(host, scriptId!!)
        val voice = controller(host, target)
        voice.configure(enabled = true, scriptId = scriptId)

        assertFalse("a password field must not offer the microphone", voice.available())
        voice.onPressed()
        assertFalse("a password field must start nothing", voice.isRecording())
    }

    @Test
    fun aRefusedPermissionIsObservableAndStartsNothing() {
        val target = field("hello")
        val refusing =
            object : VoiceInputHost {
                var waits = 0
                var starts = 0

                override fun script(id: String): VoiceScript = VoiceScript(id, "听写")

                override fun hasMicrophonePermission(): Boolean = false

                override suspend fun awaitMicrophonePermission(): Boolean {
                    waits += 1
                    return false
                }

                override fun startRecording(): VoiceInputProblem? {
                    starts += 1
                    return null
                }

                override suspend fun stopRecording(): VoiceRecordingResult =
                    VoiceRecordingResult.Failed(VoiceInputProblem.RECORDING_FAILED)

                override fun cancelRecording() = Unit

                override suspend fun runAudio(
                    scriptId: String,
                    wav: ByteArray,
                ): VoiceScriptResult = VoiceScriptResult.Failed(VoiceInputProblem.SCRIPT_FAILED, null)
            }
        val voice = controller(refusing, target)
        voice.configure(enabled = true, scriptId = "any")

        voice.onPressed()
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline &&
            !messages.contains(context.getString(app.songyan.ime.ime.R.string.ime_voice_permission_needed))
        ) {
            Thread.sleep(20)
        }
        assertEquals("the production permission wait must be asked", 1, refusing.waits)
        assertEquals("nothing may record without the permission", 0, refusing.starts)
        assertTrue(
            "the refusal must say why nothing happened: " + messages,
            messages.contains(context.getString(app.songyan.ime.ime.R.string.ime_voice_permission_needed)),
        )
        assertFalse(voice.isRecording())
    }
}
