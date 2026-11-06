package app.songyan.ime.ime

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.layout.KeyboardPageKind
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The editor pads as the keyboard really draws them: the direct-entry digit pad of a number, phone,
 * date and time editor, captured so the drawn key faces can be inspected next to the assertions.
 *
 * Each capture holds a bitmap in the test app's internal storage for `adb shell run-as` collection,
 * and the assertions read the accessibility keys, so a pad that silently drops a key the field
 * accepts, or invents one it rejects, fails here.
 */
@RunWith(AndroidJUnit4::class)
class EditorPadRenderTest {
    private class Rendered(
        val spoken: List<String>,
        /** The drawn keys, index for index with [spoken]: the provider makes one node per key. */
        val keys: List<LayoutKey>,
        val path: String,
        val heightDp: Float,
    )

    private fun render(
        kind: NumericEditorKind,
        flags: NumberInputFlags = NumberInputFlags(),
        dateTime: DateTimeFlags = DateTimeFlags(),
        enterLabel: String = "完成",
        name: String,
    ): Rendered {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pad = requireNotNull(EditorDigitLayouts.pad(kind, flags, dateTime))
        lateinit var view: KeyboardView
        var keys: List<LayoutKey> = emptyList()
        instrumentation.runOnMainSync {
            view = KeyboardView(instrumentation.targetContext)
            view.updateDial(
                pad = pad,
                enterLabel = enterLabel,
                faces = KeyboardFaces(english = true, page = KeyboardPageKind.NUMERIC),
            )
            val width = 1080
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, width, view.measuredHeight)
            keys = view.keys
        }
        instrumentation.waitForIdleSync()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        instrumentation.runOnMainSync { view.draw(Canvas(bitmap)) }
        val directory = java.io.File(instrumentation.targetContext.filesDir, "editor-pads")
        directory.mkdirs()
        val file = java.io.File(directory, name + ".png")
        java.io.FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val provider = requireNotNull(view.accessibilityNodeProvider)
        val spoken = mutableListOf<String>()
        for (index in 0 until 64) {
            val node = provider.createAccessibilityNodeInfo(index) ?: break
            node.getBoundsInParent(Rect())
            spoken += node.contentDescription?.toString() ?: ""
        }
        val density = instrumentation.targetContext.resources.displayMetrics.density
        return Rendered(spoken, keys, file.absolutePath, view.height / density)
    }

    private fun Rendered.has(label: String): Boolean = spoken.contains(label)

    @Test
    fun numberPadShowsOnlyDigitsTheFieldAccepts() {
        val integer = render(NumericEditorKind.NUMBER, name = "number-integer")
        for (digit in 0..9) {
            assertTrue(integer.spoken.toString(), integer.has(digit.toString()))
        }
        assertFalse("an integer field rejects a decimal point", integer.has("."))
        assertFalse("an integer field rejects a sign", integer.has("-"))

        val decimal =
            render(
                NumericEditorKind.NUMBER,
                NumberInputFlags(signed = true, decimal = true),
                name = "number-signed-decimal",
            )
        assertTrue(decimal.has("."))
        assertTrue(decimal.has("-"))
    }

    @Test
    fun phonePadShowsDialCharactersOnly() {
        val phone = render(NumericEditorKind.PHONE, name = "phone")
        for (symbol in listOf("+", "*", "#", "0")) {
            assertTrue(phone.spoken.toString(), phone.has(symbol))
        }
        assertFalse(phone.has("."))
        assertFalse(phone.has(":"))
    }

    @Test
    fun datePadShowsDateSeparatorsOnly() {
        val date = render(NumericEditorKind.DATETIME, dateTime = DateTimeFlags(date = true), name = "date")
        for (symbol in listOf("/", ".", "-")) {
            assertTrue(date.spoken.toString(), date.has(symbol))
        }
        assertFalse("a date field rejects a colon", date.has(":"))
    }

    @Test
    fun timePadShowsTheColonOnly() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val enter = context.getString(R.string.ime_done)
        val time =
            render(
                NumericEditorKind.DATETIME,
                dateTime = DateTimeFlags(time = true),
                enterLabel = enter,
                name = "time",
            )
        assertTrue(time.spoken.toString(), time.has(":"))
        for (rejected in listOf("/", ".", "-")) {
            assertFalse("a time field rejects " + rejected, time.has(rejected))
        }
        // The closing row still holds the digits and the editing keys the pad needs. Delete's
        // automatic face names the input language's word, not the app locale's.
        val delete = KeyNames(context).chinese().getString(R.string.ime_delete)
        for (key in listOf("0", delete, enter)) {
            assertTrue(time.spoken.toString(), time.has(key))
        }
        val deleteIndex = time.spoken.indexOf(delete)
        assertTrue(time.spoken.toString(), deleteIndex >= 0)
        assertEquals(LayoutActionKind.BACKSPACE, time.keys[deleteIndex].action.type)
    }

    @Test
    fun aFieldWithoutAVariationKeepsBothSeparators() {
        val any = render(NumericEditorKind.DATETIME, name = "datetime-any")
        for (symbol in listOf("/", ":", "-")) {
            assertTrue(any.spoken.toString(), any.has(symbol))
        }
    }

    @Test
    fun everyPadKeepsTheSameHeight() {
        val pads =
            listOf(
                render(NumericEditorKind.NUMBER, name = "height-number"),
                render(NumericEditorKind.PHONE, name = "height-phone"),
                render(NumericEditorKind.DATETIME, dateTime = DateTimeFlags(date = true), name = "height-date"),
                render(NumericEditorKind.DATETIME, dateTime = DateTimeFlags(time = true), name = "height-time"),
            )
        // Every pad is the same four bands as the letters page, so focusing an editor of another kind
        // never resizes the keyboard window.
        val tokens = KeyboardPalette.from(InstrumentationRegistry.getInstrumentation().targetContext).tokens
        val expected = tokens.rowHeightDp * EditorDigitLayouts.ROWS + tokens.keyAreaBottomGapDp
        for (pad in pads) {
            assertEquals(pad.path, expected, pad.heightDp, 0.5f)
        }
    }
}
