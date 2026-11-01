package app.songyan.ime.ime

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.engine.Candidate
import app.songyan.ime.keyboard.engine.EngineSnapshot
import org.junit.Assert.assertEquals
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
}
