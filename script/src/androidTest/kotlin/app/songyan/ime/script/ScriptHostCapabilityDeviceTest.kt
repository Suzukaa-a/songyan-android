package app.songyan.ime.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The host capability declaration travels through the real proxy `hello` and decides both the
 * registry answers and the catalog snapshot the IME process reads without binding.
 */
@RunWith(AndroidJUnit4::class)
class ScriptHostCapabilityDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val audioScript =
        """
        // @api 1
        // @name 听写
        // @input audio
        export default async ({ audio }) => "audio " + audio.byteLength;
        """.trimIndent()
            .toByteArray(Charsets.UTF_8)

    private fun entryOf(
        registry: ScriptProxyRegistry,
        id: String,
    ): ScriptEntry {
        runBlocking { registry.reload() }
        val entry = registry.entries.firstOrNull { it.id == id }
        assertTrue("the catalog keeps " + id, entry != null)
        return entry!!
    }

    @Test
    fun theDeclaredCapabilitiesDecideAvailabilityAndTheSnapshot() {
        val textOnly = ScriptProxyRegistry(context)
        val audioHost = ScriptProxyRegistry(context, setOf(ScriptHostCapability.TEXT, ScriptHostCapability.AUDIO))
        val added =
            runBlocking {
                textOnly.reload()
                textOnly.add(audioScript, ScriptSourceOrigin.Pasted)
            }
        try {
            assertFalse(
                "TEXT only: an @input audio script is unavailable",
                entryOf(textOnly, added.id).availableOnThisHost,
            )
            assertTrue(
                "TEXT+AUDIO: the same script becomes available",
                entryOf(audioHost, added.id).availableOnThisHost,
            )
            // The IME process reads this snapshot file without binding; the last declaration wins.
            assertTrue(
                "the snapshot carries the audio-capable answer",
                ScriptCatalogCache(context).entries().first { it.id == added.id }.availableOnThisHost,
            )
        } finally {
            runBlocking { textOnly.delete(added.id) }
        }
    }
}
