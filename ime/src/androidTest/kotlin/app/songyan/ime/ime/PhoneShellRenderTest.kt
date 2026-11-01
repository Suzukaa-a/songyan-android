package app.songyan.ime.ime

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The nine-key shell drawing path: every key the model places must become one accessible key with
 * the right label and a real hit rectangle, and rail choices must replace the symbol slots.
 */
@RunWith(AndroidJUnit4::class)
class PhoneShellRenderTest {
    private val labels =
        PhoneKeypadLayout.Labels(
            page = "123",
            language = "英文",
            space = "空格",
            symbols = "符号",
            back = "返回",
            zero = "0",
            enter = "换行",
            clear = "重输",
            delete = "⌫",
        )

    private fun config(
        numeric: Boolean = false,
        composing: Boolean = false,
    ) = PhoneKeypadLayout.Config(
        width = 1000f,
        height = 240f,
        numeric = numeric,
        composing = composing,
        asciiPunctuation = false,
        labels = labels,
    )

    private fun render(
        config: PhoneKeypadLayout.Config,
        railChoices: List<String> = emptyList(),
        faces: KeyboardFaces = KeyboardFaces(phone = true),
        captureName: String? = null,
        width: Int = 1080,
    ): List<Pair<String, android.graphics.Rect>> {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: KeyboardView
        instrumentation.runOnMainSync {
            view = KeyboardView(instrumentation.targetContext)
            view.updatePhone(
                config = config,
                shift = false,
                enterLabel = "换行",
                faces = faces,
                railChoices = railChoices,
            )
            view.measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, width, view.measuredHeight)
        }
        instrumentation.waitForIdleSync()
        if (captureName != null) capture(view, captureName)
        val provider = requireNotNull(view.accessibilityNodeProvider)
        val result = mutableListOf<Pair<String, android.graphics.Rect>>()
        for (index in 0 until 64) {
            val node = provider.createAccessibilityNodeInfo(index) ?: break
            val rect = android.graphics.Rect()
            node.getBoundsInParent(rect)
            result += (node.contentDescription?.toString() ?: "") to rect
        }
        return result
    }

    /** Renders the view into a PNG the adb pull can collect, for visual review. */
    private fun capture(
        view: KeyboardView,
        name: String,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val bitmap =
            android.graphics.Bitmap.createBitmap(
                view.width,
                view.height,
                android.graphics.Bitmap.Config.ARGB_8888,
            )
        instrumentation.runOnMainSync { view.draw(android.graphics.Canvas(bitmap)) }
        // Internal storage: the adb shell cannot read app-scoped external storage on API 36.
        val directory = java.io.File(context.filesDir, "screenshots")
        directory.mkdirs()
        val file = java.io.File(directory, name + ".png")
        java.io.FileOutputStream(file).use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        android.util.Log.i("PhoneShellRenderTest", "captured " + file.absolutePath)
    }

    /** Writes the three shell states and stays alive so adb can collect them via run-as. */
    @Test
    fun holdsScreenshotsForCollection() {
        render(config(), captureName = "9key-letters")
        render(config(composing = true), railChoices = listOf("ni", "mi", "ne"), captureName = "9key-reading-rail")
        render(config(numeric = true), captureName = "9key-123-pad")
        Thread.sleep(25_000)
    }

    @Test
    fun letterShellDrawsGridRailsAndBottomRow() {
        val keys = render(config(), captureName = "9key-letters")
        val descriptions = keys.map { it.first }
        // Locale-free labels first: the symbols, the letter groups and the authored editing keys.
        assertTrue(descriptions.toString(), descriptions.containsAll(listOf("，", "。", "？")))
        // The 9-key grid shows the letter groups; the digit is the corner badge, spoken with the key.
        assertTrue(
            descriptions.toString(),
            descriptions.containsAll(listOf("ABC 2", "DEF 3", "GHI 4", "JKL 5", "MNO 6", "PQRS 7", "TUV 8", "WXYZ 9")),
        )
        assertTrue(
            descriptions.toString(),
            descriptions.containsAll(
                listOf(R.string.ime_delete, R.string.ime_clear, R.string.ime_enter).map(targetContext::getString),
            ),
        )
        // 3 rail symbols + 9 grid keys + 3 editing rail keys + 4 bottom keys (123 中/英 space 0).
        assertEquals(descriptions.toString(), 19, keys.size)
        // Every key has a real hit rectangle inside the shell.
        assertTrue(keys.all { it.second.width() > 0 && it.second.height() > 0 })
        assertTrue(keys.maxOf { it.second.bottom } <= 2000)
    }

    @Test
    fun aShellNarrowerThanTheWindowIsCentred() {
        // Wider than the shell's preferred keys allow (a foldable's cover screen, a large phone).
        val width = (520 * targetContext.resources.displayMetrics.density).toInt()
        val keys = render(config(), width = width)
        // The edge keys' hit regions reach the window edges, so the middle column shows the placement.
        val middle = keys.first { it.first == "JKL 5" }.second
        assertTrue("JKL at ${middle.centerX()} of $width", kotlin.math.abs(middle.centerX() - width / 2) <= 2)
    }

    @Test
    fun railChoicesReplaceTheSymbolSlots() {
        val keys =
            render(config(composing = true), railChoices = listOf("ni", "mi", "ne"), captureName = "9key-reading-rail")
        val descriptions = keys.map { it.first }
        assertTrue(descriptions.containsAll(listOf("ni", "mi", "ne")))
        // The footer keeps its own comma and period; the rail's third symbol is what the choices replace.
        assertTrue(descriptions.none { it == "？" })
    }

    @Test
    fun numericPageIsTheDigitPad() {
        val keys = render(config(numeric = true), captureName = "9key-123-pad")
        val descriptions = keys.map { it.first }
        assertTrue(descriptions.toString(), descriptions.containsAll((0..9).map { it.toString() }))
        assertTrue(
            descriptions.toString(),
            descriptions.containsAll(listOf(R.string.ime_delete, R.string.ime_enter).map(targetContext::getString)),
        )
        // 3 marks + 9 digits + Delete, 0 and the comma + 返回, 中/英, space, 符号 and Return.
        assertEquals(descriptions.toString(), 20, keys.size)
        assertTrue(descriptions.toString(), descriptions.none { it == targetContext.getString(R.string.ime_clear) })
        // 0 stands right of the 6, below Delete; the space bar under the 8; Return is one row tall.
        val six = keys.first { it.first == "6" }.second
        val zero = keys.first { it.first == "0" }.second
        assertEquals(six.top.toFloat(), zero.top.toFloat(), 2f)
        assertTrue(zero.left >= six.right - 2)
        val eight = keys.first { it.first == "8" }.second
        val space = keys.first { it.first == targetContext.getString(R.string.ime_space) }.second
        assertEquals(eight.centerX().toFloat(), space.centerX().toFloat(), 2f)
        assertTrue(space.top >= eight.bottom - 2)
        val enter = keys.first { it.first == targetContext.getString(R.string.ime_enter) }.second
        assertEquals(space.top.toFloat(), enter.top.toFloat(), 2f)
        assertEquals(space.height().toFloat(), enter.height().toFloat(), 2f)
        assertEquals(zero.centerX().toFloat(), enter.centerX().toFloat(), 2f)
    }

    @Test
    fun rowPagesStillRenderAfterThePhoneShell() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: KeyboardView
        instrumentation.runOnMainSync {
            view = KeyboardView(instrumentation.targetContext)
            val definition =
                LayoutDefinition(
                    id = "test",
                    name = "test",
                    input = LayoutInput(builtinLayout = LayoutInput.Builtin.QWERTY26),
                    rows =
                        listOf(
                            LayoutRow(
                                listOf(
                                    app.songyan.ime.layout.LayoutKey(
                                        "q",
                                        action =
                                            app.songyan.ime.layout.LayoutAction
                                                .code("q"),
                                    ),
                                ),
                            ),
                        ),
                )
            view.updatePhone(config(), shift = false, enterLabel = "换行")
            view.update(definition = definition, shift = false, enterLabel = "换行")
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, 1080, view.measuredHeight)
        }
        instrumentation.waitForIdleSync()
        val provider = requireNotNull(view.accessibilityNodeProvider)
        val node = requireNotNull(provider.createAccessibilityNodeInfo(0))
        assertEquals("q", node.contentDescription?.toString())
        assertEquals(null, provider.createAccessibilityNodeInfo(1))
    }

    private val targetContext get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** A rail of three drawn choices, the first [applied] of them already chosen. */
    private fun railView(applied: Int): KeyboardView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: KeyboardView
        instrumentation.runOnMainSync {
            view = KeyboardView(instrumentation.targetContext)
            view.updatePhone(
                config = config(composing = true),
                shift = false,
                enterLabel = "换行",
                faces = KeyboardFaces(phone = true),
                railChoices = listOf("ni", "mi", "ne"),
                railSelected = applied,
            )
            view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(2000, View.MeasureSpec.AT_MOST),
            )
            view.layout(0, 0, 1080, view.measuredHeight)
        }
        return view
    }

    private fun slotCenter(
        view: KeyboardView,
        description: String,
    ): Pair<Float, Float> {
        val provider = requireNotNull(view.accessibilityNodeProvider)
        for (index in 0 until 64) {
            val node = provider.createAccessibilityNodeInfo(index) ?: break
            if (node.contentDescription?.toString() == description) {
                val rect = android.graphics.Rect()
                node.getBoundsInParent(rect)
                return rect.exactCenterX() to rect.exactCenterY()
            }
        }
        error("no key '$description'")
    }

    private fun gesture(
        view: KeyboardView,
        from: Pair<Float, Float>,
        toY: Float,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val start = android.os.SystemClock.uptimeMillis()

        fun event(
            action: Int,
            time: Long,
            y: Float,
        ) = android.view.MotionEvent.obtain(start, time, action, from.first, y, 0)
        instrumentation.runOnMainSync {
            for (
            (action, offset, y) in
            listOf(
                Triple(android.view.MotionEvent.ACTION_DOWN, 0L, from.second),
                Triple(android.view.MotionEvent.ACTION_MOVE, 20L, (from.second + toY) / 2),
                Triple(android.view.MotionEvent.ACTION_MOVE, 40L, toY),
                Triple(android.view.MotionEvent.ACTION_UP, 80L, toY),
            )
            ) {
                val motion = event(action, start + offset, y)
                view.dispatchTouchEvent(motion)
                motion.recycle()
            }
        }
        instrumentation.waitForIdleSync()
    }

    @Test
    fun aTapOnARailChoiceReportsItsSlotAndLabel() {
        val view = railView(applied = 0)
        val taps = mutableListOf<Pair<Int, String>>()
        view.onRailChoice = { position, label -> taps += position to label }
        val center = slotCenter(view, "mi")
        gesture(view, center, center.second)
        assertEquals(listOf(1 to "mi"), taps)
    }

    @Test
    fun anAppliedRailChoiceIsNotTappable() {
        val view = railView(applied = 1)
        val taps = mutableListOf<Pair<Int, String>>()
        view.onRailChoice = { position, label -> taps += position to label }
        val chosen = slotCenter(view, targetContext.getString(R.string.ime_rail_chosen, "ni"))
        gesture(view, chosen, chosen.second)
        assertTrue("an applied choice does nothing: $taps", taps.isEmpty())
    }

    @Test
    fun draggingTheRailScrollsItAndNeverSelects() {
        val view = railView(applied = 0)
        val taps = mutableListOf<Pair<Int, String>>()
        val scrolls = mutableListOf<Int>()
        view.onRailChoice = { position, label -> taps += position to label }
        view.onRailScroll = { scrolls += it }
        val center = slotCenter(view, "mi")
        val slot = slotCenter(view, "ne").second - center.second
        // Up by two and a half slots reveals later choices: two whole steps in all, and the release
        // selects nothing. The half slot keeps the count independent of how the slot height rounds
        // to pixels, which differs from screen to screen; the travel may leave a short view, as a
        // finger on a real screen may.
        gesture(view, center, center.second - 2.5f * slot)
        assertEquals(2, scrolls.sum())
        assertTrue("a scroll is not a tap: $taps", taps.isEmpty())
    }
}
