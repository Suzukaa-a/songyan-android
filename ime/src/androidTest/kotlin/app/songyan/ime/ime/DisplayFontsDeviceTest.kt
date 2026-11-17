package app.songyan.ime.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The imported display fonts reach the real keyboard's views. Two platform fonts with very different
 * letter shapes stand in for imported files, so no font binary lives in the repository.
 */
@RunWith(AndroidJUnit4::class)
class DisplayFontsDeviceTest {
    private val script = File("/system/fonts/DancingScript-Regular.ttf")
    private val handwriting = File("/system/fonts/ComingSoon.ttf")

    private fun width(
        face: Typeface?,
        text: String,
    ): Float = Paint().also { it.typeface = face ?: Typeface.DEFAULT }.measureText(text)

    @Test
    fun theCascadeBuildsTheChosenFontFirst() {
        assertTrue(script.isFile && handwriting.isFile)
        val first = requireNotNull(DisplayFonts.build(listOf(script, handwriting)))
        val second = requireNotNull(DisplayFonts.build(listOf(handwriting, script)))
        val scriptOnly = requireNotNull(DisplayFonts.build(listOf(script)))
        val handwritingOnly = requireNotNull(DisplayFonts.build(listOf(handwriting)))
        assertNotEquals(width(scriptOnly, SAMPLE), width(handwritingOnly, SAMPLE), 0.01f)
        // Both fonts have these letters, so the first one in the chain decides their shapes.
        assertEquals(width(scriptOnly, SAMPLE), width(first, SAMPLE), 0.01f)
        assertEquals(width(handwritingOnly, SAMPLE), width(second, SAMPLE), 0.01f)
    }

    @Test
    fun anUnreadableChosenFontIsTheSystemFontAndAnUnreadableFallbackIsLeftOut() {
        val broken = File.createTempFile("broken", ".ttf").also { it.writeText("not a font") }
        try {
            assertNull(DisplayFonts.build(listOf(broken, script)))
            assertNull(DisplayFonts.build(listOf(File("/missing/font.ttf"))))
            assertNull(DisplayFonts.build(emptyList()))
            val withBrokenFallback = requireNotNull(DisplayFonts.build(listOf(script, broken)))
            assertEquals(width(DisplayFonts.build(listOf(script)), SAMPLE), width(withBrokenFallback, SAMPLE), 0.01f)
        } finally {
            broken.delete()
        }
    }

    @Test
    fun theChosenTypefacesReachTheKeyboardAndTheCandidateBarSeparately() {
        val host = FeatureHost(engine.backend(), "qwerty26")
        try {
            host.start()
            // The candidate bar is only on screen while the engine is composing.
            host.type("ka")
            host.awaitIdle()
            val before = host.main2 { host.find(KeyboardView::class.java).paint.typeface }
            val keysBefore = drawKeys(host)

            host.displayFontsFlow.value =
                DisplayFontSpec(candidates = listOf(handwriting), keys = listOf(script, handwriting))
            host.awaitIdle()
            waitUntil { DisplayFonts.keys != null }

            val keys = requireNotNull(DisplayFonts.keys)
            val candidates = requireNotNull(DisplayFonts.candidates)
            assertTrue(keys !== candidates)
            host.main {
                assertSame(keys, host.find(KeyboardView::class.java).paint.typeface)
                assertSame(candidates, host.find(CandidateBarView::class.java).paint.typeface)
                assertSame(candidates, host.find(CandidateBarView::class.java).textPaint.typeface)
            }
            assertTrue("the key labels are drawn in the chosen font", !keysBefore.sameAs(drawKeys(host)))
            assertNotNull(before)

            host.displayFontsFlow.value = DisplayFontSpec.SYSTEM
            host.awaitIdle()
            waitUntil { DisplayFonts.keys == null }
            host.main {
                assertEquals(before, host.find(KeyboardView::class.java).paint.typeface)
            }
        } finally {
            host.displayFontsFlow.value = DisplayFontSpec.SYSTEM
            DisplayFonts.configure(DisplayFontSpec.SYSTEM)
            host.close()
            runBlocking { host.backend.shutdown() }
        }
    }

    private fun drawKeys(host: FeatureHost): Bitmap {
        lateinit var bitmap: Bitmap
        host.main {
            val keyboard = host.find(KeyboardView::class.java)
            bitmap = Bitmap.createBitmap(keyboard.width, keyboard.height, Bitmap.Config.ARGB_8888)
            keyboard.draw(Canvas(bitmap))
        }
        return bitmap
    }

    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition() && System.currentTimeMillis() < end) Thread.sleep(50)
        assertTrue(condition())
    }

    private companion object {
        const val SAMPLE = "Aagq Rwmy"

        lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("display-fonts", english = false)
        }

        @AfterClass
        @JvmStatic
        fun remove() {
            engine.delete()
        }
    }
}
