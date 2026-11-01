package app.songyan.ime.rime

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * UTF-8 handling across the JNI boundary. Astral characters (emoji) must survive the trip in
 * both directions, and malformed bytes must never decode to something the encoder would not
 * produce: overlong forms and code points above U+10FFFF become U+FFFD.
 */
@RunWith(AndroidJUnit4::class)
class NativeUtf8Test {
    @Before
    fun loadLibrary() {
        assertTrue("native library failed to load: " + RimeNative.loadFailure(), RimeNative.ensureLoaded())
    }

    private fun decode(vararg bytes: Int): String? =
        RimeNative.nativeUtf8Probe(
            ByteArray(bytes.size) {
                bytes[it].toByte()
            },
        )

    @Test
    fun astralCodePointsBecomeSurrogatePairs() {
        assertEquals("😀", decode(0xF0, 0x9F, 0x98, 0x80))
        assertEquals("a😀你", decode(0x61, 0xF0, 0x9F, 0x98, 0x80, 0xE4, 0xBD, 0xA0))
        // U+10FFFF is the last valid code point.
        assertEquals("􏿿", decode(0xF4, 0x8F, 0xBF, 0xBF))
    }

    @Test
    fun malformedSequencesBecomeReplacementCharacters() {
        val bad = "�"
        // Overlong encodings of NUL, '/' and a three-byte form.
        assertEquals(bad + bad, decode(0xC0, 0x80))
        assertEquals(bad, decode(0xE0, 0x80, 0xAF))
        assertEquals(bad, decode(0xF0, 0x80, 0x80, 0xAF))
        // Above U+10FFFF.
        assertEquals(bad, decode(0xF4, 0x90, 0x80, 0x80))
        // An encoded surrogate is not a character.
        assertEquals(bad, decode(0xED, 0xA0, 0x80))
        // A truncated sequence does not swallow the byte that follows it.
        assertEquals(bad + "a", decode(0xE4, 0xBD, 0x61))
    }

    @Test
    fun emojiCandidateReachesTheSnapshotAndTheCommit() =
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val root = File(context.cacheDir, "engine-utf8")
            root.deleteRecursively()
            val source = File(root, "source")
            val user = File(root, "user")
            source.mkdirs()
            File(source, "default.yaml").writeText("config_version: \"1\"\nschema_list:\n  - schema: emoji_test\n")
            File(source, "emoji_test.schema.yaml").writeText(EMOJI_SCHEMA)
            File(source, "emoji_test.dict.yaml").writeText(EMOJI_DICT)
            val deployment = RimeDeployment.deploy(source, user, RimeEngine.DEFAULT_APP_NAME, "test")
            assertTrue("deployment failed: " + deployment.lastError, deployment.success)
            val engine =
                RimeEngine.createPrebuilt(
                    sharedDataDir = source,
                    userDataDir = user,
                    appName = RimeEngine.DEFAULT_APP_NAME,
                    appVersion = "test",
                    compiledDir = deployment.compiledDir,
                )
            try {
                for (char in "smile") engine.processKey(RimeKey(RimeKey.baseKeycode(char)!!))
                val snapshot = engine.readSnapshot()
                assertEquals(listOf(EMOJI), snapshot.candidates.map { it.text })
                assertEquals(EMOJI, engine.selectCandidate(0)?.commitText)
            } finally {
                engine.close()
                root.deleteRecursively()
            }
        }

    private companion object {
        const val EMOJI = "😀"
        const val EMOJI_SCHEMA =
            "schema:\n  schema_id: emoji_test\n  name: Emoji Test\n  version: \"1\"\n" +
                "engine:\n  processors: [speller, selector, navigator, express_editor]\n" +
                "  segmentors: [abc_segmentor, fallback_segmentor]\n" +
                "  translators: [table_translator]\n" +
                "translator:\n  dictionary: emoji_test\n" +
                "speller:\n  alphabet: \"abcdefghijklmnopqrstuvwxyz\"\n"
        const val EMOJI_DICT = "---\nname: emoji_test\nversion: \"1\"\nsort: original\n...\n😀\tsmile\n"
    }
}
