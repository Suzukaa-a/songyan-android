package app.songyan.ime.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.appearance.AppearancePresets
import app.songyan.ime.appearance.SkinDefinition
import app.songyan.ime.ime.palette.KeyboardAppearanceRegistry
import app.songyan.ime.ime.palette.KeyboardAppearanceSettings
import app.songyan.ime.ime.palette.KeyboardPalettes
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardPageKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** A skin's corner radius, border width and gaps reach the keys the keyboard draws, in dp. */
@RunWith(AndroidJUnit4::class)
class SkinLiteralRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @After
    fun restoreDefaultAppearance() {
        KeyboardAppearanceRegistry.install(KeyboardAppearanceSettings(document = null, dynamicColor = false))
        KeyboardPalettes.trim()
    }

    private class Drawn(
        val view: KeyboardView,
        val bitmap: Bitmap,
    )

    private fun draw(
        radius: Double,
        border: Double,
        inset: Double,
        candidatePadding: Double = 10.0,
        letters: Boolean = false,
    ): Drawn {
        val base = AppearancePresets.androidDefault.skin
        val light = base.variants.light!!
        val skin =
            base.copy(
                keycap = SkinDefinition.Keycap(cornerRadius = radius, borderWidth = border, shadow = "none"),
                spacing = SkinDefinition.Spacing(visualInsetX = inset, visualInsetY = inset),
                variants =
                    base.variants.copy(
                        light = light.copy(colors = (light.colors ?: emptyMap()) + ("border" to "#FF0000")),
                    ),
            )
        KeyboardAppearanceRegistry.install(
            KeyboardAppearanceSettings(
                document =
                    AppearancePresets.androidDefault.replacing(skin).let { document ->
                        val bar = document.candidateBar
                        document.replacing(
                            bar.copy(collapsed = bar.collapsed.copy(cellPaddingX = candidatePadding)),
                        )
                    },
                dynamicColor = false,
                forcedDark = false,
            ),
        )
        lateinit var view: KeyboardView
        instrumentation.runOnMainSync {
            view = KeyboardView(instrumentation.targetContext)
            if (letters) {
                view.update(BuiltinLayouts.qwerty26, shift = false, enterLabel = "OK")
            } else {
                view.updateDial(
                    pad =
                        requireNotNull(
                            EditorDigitLayouts.pad(NumericEditorKind.PHONE, NumberInputFlags(), DateTimeFlags()),
                        ),
                    enterLabel = "OK",
                    faces = KeyboardFaces(english = true, page = KeyboardPageKind.NUMERIC),
                )
            }
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, 1080, view.measuredHeight)
        }
        instrumentation.waitForIdleSync()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }
        return Drawn(view, bitmap)
    }

    @Test
    fun radiusBorderAndGapsAreDrawnLiterally() {
        val drawn = draw(radius = 12.0, border = 2.0, inset = 4.0)
        val view = drawn.view
        val density = view.density
        assertEquals(12f, view.metrics.keyRadiusDp, 0.001f)
        assertEquals(2f, view.metrics.keyBorderWidthDp, 0.001f)
        assertEquals(12f, view.metrics.keyGapVerticalDp, 0.001f)
        // The cap retreats half the 12 dp row gap (4 dp between cells plus the 4 dp inset twice) from its slot.
        assertEquals(6f * density, view.insetY, 0.01f)
        val box = view.bounds.first()
        val left = box.left + view.insetX
        val top = box.top + view.insetY
        val midY = ((top + box.bottom - view.insetY) / 2f).toInt()
        // The 2 dp red stroke lies inside the cap: 1 dp in from its left edge is the middle of the stroke.
        val edge = drawn.bitmap.getPixel((left + density).toInt(), midY)
        assertEquals("border colour at the left edge", 0xFFFF0000.toInt(), edge)
        // A 12 dp radius cuts the corner away: 2 px in from both edges is the keyboard background.
        val corner = drawn.bitmap.getPixel(left.toInt() + 2, top.toInt() + 2)
        assertEquals("corner is cut", view.palette.background, corner)
    }

    @Test
    fun aSmallerRadiusKeepsMoreOfTheCorner() {
        val round = draw(radius = 12.0, border = 0.0, inset = 2.0)
        val square = draw(radius = 0.0, border = 0.0, inset = 2.0)

        fun cornerPixel(drawn: Drawn): Int {
            val box = drawn.view.bounds.first()
            return drawn.bitmap.getPixel(
                (box.left + drawn.view.insetX).toInt() + 2,
                (box.top + drawn.view.insetY).toInt() + 2,
            )
        }
        assertEquals(round.view.palette.background, cornerPixel(round))
        assertNotEquals(square.view.palette.background, cornerPixel(square))
        assertTrue(square.view.metrics.keyRadiusDp == 0f)
    }

    @Test
    fun theCandidateBarPaddingDoesNotShrinkKeyLabels() {
        val narrow = draw(radius = 8.0, border = 0.0, inset = 0.5, candidatePadding = 4.0, letters = true)
        val wide = draw(radius = 8.0, border = 0.0, inset = 0.5, candidatePadding = 24.0, letters = true)
        assertEquals(24f, wide.view.metrics.candidateChipPaddingDp, 0.001f)
        assertTrue(narrow.view.labelSizes.contentEquals(wide.view.labelSizes))
    }
}
