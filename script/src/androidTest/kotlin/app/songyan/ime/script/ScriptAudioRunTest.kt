package app.songyan.ime.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * P10 S4, script side: a script that declares `@input audio` runs through the real processes
 * (proxy + isolated) and the real engine, and receives the recorded WAV as an ArrayBuffer.
 *
 * The fixture is a 16 kHz mono 16-bit WAV so the byte assertions mean what the contract says; the
 * host-policy limit case sends a minute of PCM, which is also the proof that the audio crosses
 * Binder as a descriptor rather than a transaction byte array.
 */
@RunWith(AndroidJUnit4::class)
class ScriptAudioRunTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val registry = ScriptProxyRegistry(context)

    /** Reports what the script received, so the test can compare it with the bytes it sent. */
    private val audioScript =
        """
        // @api 1
        // @name 听写
        // @input audio
        export default async ({ audio }) => {
          const bytes = new Uint8Array(audio);
          return "length=" + audio.byteLength + " first=" + bytes[0] + " last=" + bytes[bytes.length - 1];
        };
        """.trimIndent()
            .toByteArray(Charsets.UTF_8)

    private val textScript =
        """
        // @api 1
        // @name 书名号
        export default async ({ text }) => "「" + text + "」";
        """.trimIndent()
            .toByteArray(Charsets.UTF_8)

    private fun fixture(): ByteArray =
        instrumentation.context.assets
            .open("script-audio/fixture.wav")
            .use { it.readBytes() }

    private fun install(source: ByteArray): String =
        runBlocking {
            val entry = registry.add(source, ScriptSourceOrigin.Pasted)
            registry.confirm(entry.id, emptyMap())
            registry.setEnabled(entry.id, true)
            entry.id
        }

    private fun remove(id: String) {
        runBlocking { registry.delete(id) }
    }

    private fun report(wav: ByteArray): String =
        "length=" + wav.size + " first=" + (wav.first().toInt() and 0xFF) +
            " last=" + (wav.last().toInt() and 0xFF)

    private fun successOutput(outcome: ScriptRunOutcome): String {
        assertTrue("the audio run succeeds: " + outcome, outcome is ScriptRunOutcome.Success)
        return (outcome as ScriptRunOutcome.Success).outputs.single()
    }

    private fun failure(outcome: ScriptRunOutcome): ScriptRunOutcome.Failed {
        assertTrue("the run is refused: " + outcome, outcome is ScriptRunOutcome.Failed)
        return outcome as ScriptRunOutcome.Failed
    }

    /** A WAV of [totalBytes] bytes: a valid 16 kHz mono 16-bit header plus zero samples. */
    private fun silentWav(totalBytes: Int): ByteArray {
        require(totalBytes > 44)
        val wav = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)
        wav.put("RIFF".toByteArray())
        wav.putInt(totalBytes - 8)
        wav.put("WAVE".toByteArray())
        wav.put("fmt ".toByteArray())
        wav.putInt(16)
        wav.putShort(1)
        wav.putShort(1)
        wav.putInt(16_000)
        wav.putInt(32_000)
        wav.putShort(2)
        wav.putShort(16)
        wav.put("data".toByteArray())
        wav.putInt(totalBytes - 44)
        return wav.array()
    }

    @Test
    fun theRecordedWavReachesTheScriptAsAnArrayBuffer() {
        val wav = fixture()
        assertEquals("the fixture starts with the RIFF magic", 82, wav.first().toInt() and 0xFF)
        val id = install(audioScript)
        try {
            val output = successOutput(runBlocking { registry.runAudio(id, wav) })
            // The byteLength and the first/last bytes prove it is the same buffer, not a prefix.
            assertEquals(report(wav), output)
        } finally {
            remove(id)
        }
    }

    @Test
    fun oneMinuteOfAudioCrossesTheDescriptorTransport() {
        val wav = silentWav(ScriptAudioInput.PROVISIONAL_MAX_BYTES)
        val id = install(audioScript)
        try {
            val output = successOutput(runBlocking { registry.runAudio(id, wav) })
            assertEquals(report(wav), output)
        } finally {
            remove(id)
        }
    }

    @Test
    fun aTextRunOfAnAudioScriptReportsTheMissingCapability() {
        val id = install(audioScript)
        try {
            // The text path passes no audio, so the host offers no audio capability and the shared
            // bridge refuses with its contract code rather than running the script with no input.
            val refused = failure(runBlocking { registry.run(id, "文本") })
            assertEquals("capabilityUnavailable", refused.code)
        } finally {
            remove(id)
        }
    }

    @Test
    fun audioInputIsRefusedWhenTheHeaderOrTheHostCannotTakeIt() {
        val audioId = install(audioScript)
        val textId = install(textScript)
        try {
            val mismatch = failure(runBlocking { registry.runAudio(textId, fixture()) })
            assertEquals("inputMismatch", mismatch.code)

            val empty = failure(runBlocking { registry.runAudio(audioId, ByteArray(0)) })
            assertEquals("capabilityUnavailable", empty.code)

            val oversize =
                failure(
                    runBlocking {
                        registry.runAudio(audioId, ByteArray(ScriptAudioInput.PROVISIONAL_MAX_BYTES + 1))
                    },
                )
            assertEquals("audioTooLarge", oversize.code)
        } finally {
            remove(audioId)
            remove(textId)
        }
    }
}
