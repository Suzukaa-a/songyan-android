package app.songyan.ime.ime

import android.content.Context
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The settle step of the inline suggestion strip: every response gets it, and a detached strip runs none. */
@RunWith(AndroidJUnit4::class)
class InlineSuggestionStripTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** A chip the system never sized: it stays 0 x 0 because the strip is not laid out. */
    private fun unsizedChip(): View = View(context)

    @Test
    fun aLaterResponseStillGetsTheFallbackWidthForUnsizedChips() {
        lateinit var strip: InlineSuggestionStrip
        onMain { strip = InlineSuggestionStrip(context) }
        onMain { strip.show(listOf(unsizedChip())) }
        // Past both settle steps: the chip was widened once, then dropped and the strip cleared.
        Thread.sleep(SETTLE_WAIT_MS * 2)
        onMain { assertTrue(!strip.hasSuggestions) }
        val second = unsizedChip()
        onMain { strip.show(listOf(second)) }
        Thread.sleep(SETTLE_WAIT_MS)
        onMain {
            assertTrue("the first settle step gives the chip a width", second.layoutParams.width > 0)
        }
    }

    @Test
    fun aCancelledSettleLeavesTheChipsAlone() {
        lateinit var strip: InlineSuggestionStrip
        val chip = unsizedChip()
        onMain {
            strip = InlineSuggestionStrip(context)
            strip.show(listOf(chip))
            strip.cancelSettle()
        }
        Thread.sleep(SETTLE_WAIT_MS * 2)
        onMain {
            assertTrue(strip.hasSuggestions)
            assertEquals(android.view.ViewGroup.LayoutParams.WRAP_CONTENT, chip.layoutParams.width)
        }
    }

    private companion object {
        const val SETTLE_WAIT_MS = 1000L
    }
}
