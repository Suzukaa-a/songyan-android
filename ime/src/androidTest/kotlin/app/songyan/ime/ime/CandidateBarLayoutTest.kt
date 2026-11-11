package app.songyan.ime.ime

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CandidateBarLayoutTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun aListShownBeforeTheFirstLayoutIsFilledOnceTheBarHasASize() {
        val reads = mutableListOf<Int>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bar = CandidateBarView(context)
            bar.onLoadMore = { _, page -> reads += page }
            bar.update(
                EngineSnapshot(
                    schemaID = "s",
                    rawInput = "ji",
                    isComposing = true,
                    candidates = List(5) { Candidate(it, "字$it") },
                    pageSize = 5,
                    isLastPage = false,
                ),
            )
            assertEquals("no width yet: nothing can be measured", emptyList<Int>(), reads)
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            )
            bar.layout(0, 0, 1080, 300)
        }
        assertEquals(listOf(1), reads)
    }

    /**
     * A held Delete's step must run only for the touch that queued it: pressing the same control
     * again (the release displayed nothing yet) must not let the finished touch's step delete.
     */
    @Test
    fun aQueuedRepeatStepBelongsToItsOwnTouchEvenOnTheSameControl() {
        val steps = mutableListOf<() -> Boolean>()
        var firstTouch: (() -> Boolean)? = null
        var secondTouch: (() -> Boolean)? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bar = CandidateBarView(context)
            bar.onRepeatBackspace = { stillHeld -> steps += stillHeld }
            bar.update(
                EngineSnapshot(
                    schemaID = "s",
                    rawInput = "ji",
                    isComposing = true,
                    candidates = List(5) { Candidate(it, "字$it") },
                    pageSize = 5,
                    isLastPage = false,
                ),
            )
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            )
            bar.layout(0, 0, 1080, 300)
            bar.setExpanded(true)
            bar.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            )
            bar.layout(0, 0, 1080, 300)
            // The expanded grid's fixed column holds Back, Delete, Retype from the top.
            val delete = bar.controlCells[1]

            fun touch(
                time: Long,
                action: Int,
                downTime: Long,
            ) {
                val event =
                    MotionEvent.obtain(
                        downTime,
                        time,
                        action,
                        delete.exactCenterX(),
                        delete.exactCenterY(),
                        0,
                    )
                bar.dispatchTouchEvent(event)
                event.recycle()
            }
            var at = SystemClock.uptimeMillis()
            touch(at, MotionEvent.ACTION_DOWN, at)
            bar.repeatBackspace.run()
            firstTouch = steps.last()
            touch(at + 20, MotionEvent.ACTION_UP, at)
            at += 100
            touch(at, MotionEvent.ACTION_DOWN, at)
            bar.repeatBackspace.run()
            secondTouch = steps.last()
            assertFalse("the old step stays dead while the same control is held again", firstTouch!!())
            assertTrue("its own touch's step is live", secondTouch!!())
            touch(at + 20, MotionEvent.ACTION_UP, at)
        }
        assertEquals("one step captured per hold", 2, steps.size)
        assertFalse("the finished touch's step must not delete", firstTouch!!())
        assertFalse("a step dies with its own touch too", secondTouch!!())
    }

    /** Expanding must read as the bar growing: row 1 of the grid is the collapsed row, pixel for pixel. */
    @Test
    fun theExpandedFirstRowIsDrawnExactlyWhereTheCollapsedRowWas() {
        val words = listOf("你好", "拟好", "你", "泥", "妮好啊", "逆", "尼", "霓虹灯下", "匿", "腻")
        var collapsedPixels: Bitmap? = null
        var expandedPixels: Bitmap? = null
        var collapsedCells = emptyList<android.graphics.RectF>()
        var expandedCells = emptyList<android.graphics.RectF>()
        var rowHeight = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val bar = CandidateBarView(context)
            bar.update(
                EngineSnapshot(
                    schemaID = "s",
                    rawInput = "nihao",
                    isComposing = true,
                    candidates = words.mapIndexed { i, w -> Candidate(i, w) },
                    pageSize = 10,
                    isLastPage = true,
                ),
            )

            fun lay(height: Int) {
                bar.measure(
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
                )
                bar.layout(0, 0, 1080, bar.measuredHeight)
            }

            fun shot(): Bitmap =
                Bitmap.createBitmap(1080, bar.height, Bitmap.Config.ARGB_8888).also { bar.draw(Canvas(it)) }
            lay(300)
            collapsedPixels = shot()
            collapsedCells = bar.cells.map { android.graphics.RectF(it) }
            rowHeight = bar.height
            bar.setExpanded(true)
            lay(rowHeight * 4)
            expandedPixels = shot()
            expandedCells = bar.cells.map { android.graphics.RectF(it) }
        }
        val firstRow = expandedCells.indices.filter { expandedCells[it].top == 0f }
        assertTrue(firstRow.size >= 5)
        for (i in firstRow) {
            assertEquals("cell $i left", collapsedCells[i].left, expandedCells[i].left, 0.01f)
            assertEquals("cell $i width", collapsedCells[i].width(), expandedCells[i].width(), 0.01f)
        }
        val a = collapsedPixels!!
        val b = expandedPixels!!
        // The collapsed strip fades and clips what runs past its end and the grid wraps it, so the
        // comparison stops where the last whole cell of row 1 begins.
        val compareTo = expandedCells[firstRow.last()].left.toInt()
        var differing = 0
        val box = android.graphics.Rect(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
        for (y in 0 until rowHeight) {
            for (x in 0 until compareTo) {
                if (a.getPixel(x, y) == b.getPixel(x, y)) continue
                differing++
                box.union(x, y, x + 1, y + 1)
            }
        }
        assertEquals("pixels of row 1 that moved, in $box of row height $rowHeight", 0, differing)
    }
}
