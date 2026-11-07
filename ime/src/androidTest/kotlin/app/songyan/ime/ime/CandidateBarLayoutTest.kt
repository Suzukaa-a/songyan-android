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
}
