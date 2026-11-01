package app.songyan.ime.ime

import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Candidates, readings and toolbar entries tick at touch-down like keys do. */
@RunWith(AndroidJUnit4::class)
class PressFeedbackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun View.sized(
        width: Int,
        height: Int,
    ) {
        measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        layout(0, 0, width, height)
    }

    private fun View.press(
        x: Float,
        y: Float,
    ) {
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time, action, x, y, 0)
            dispatchTouchEvent(event)
            event.recycle()
        }
    }

    @Test
    fun tappingACandidateTicks() {
        var ticks = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bar = CandidateBarView(context)
            bar.onPressFeedback = { ticks++ }
            bar.sized(1080, 150)
            bar.update(
                EngineSnapshot(
                    schemaID = "s",
                    rawInput = "ni",
                    isComposing = true,
                    candidates = List(3) { Candidate(it, "你$it") },
                    pageSize = 5,
                    isLastPage = true,
                ),
            )
            bar.sized(1080, 150)
            bar.press(60f, 75f)
        }
        assertEquals(1, ticks)
    }

    @Test
    fun tappingAToolbarEntryTicks() {
        var ticks = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val toolbar = ToolbarView(context)
            toolbar.onPressFeedback = { ticks++ }
            toolbar.sized(1080, 150)
            toolbar.press(60f, 75f)
        }
        assertEquals(1, ticks)
    }

    @Test
    fun tappingAReadingTicksButAChosenOneDoesNot() {
        var chosen = 0
        var tappable = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val row = ReadingRowView(context)
            row.onPressFeedback = { chosen++ }
            row.setItems(listOf(RailItem("ni", RailKind.LOCKED)))
            row.sized(1080, 100)
            for (x in 0 until 1080 step 20) row.press(x.toFloat(), 50f)
            row.onPressFeedback = { tappable++ }
            row.setItems(listOf(RailItem("hao", RailKind.READING)))
            row.sized(1080, 100)
            for (x in 0 until 1080 step 20) row.press(x.toFloat(), 50f)
        }
        assertEquals("a chosen reading does nothing", 0, chosen)
        assertTrue("a reading the finger can pick ticks", tappable > 0)
    }
}
