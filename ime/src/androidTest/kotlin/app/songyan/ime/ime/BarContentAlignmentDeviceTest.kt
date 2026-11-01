package app.songyan.ime.ime

import android.content.ClipData
import android.content.ClipboardManager
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Every kind of content in the candidate bar's row sits on the shared centre line
 * ([BarContentBox]), and the band takes no view that bypasses it. The real inline autofill chips
 * come from an autofill service, which a test cannot install, so a plain view is added through the
 * same path ([InlineSuggestionStrip.show]); the chip's height is the one requested from the system.
 */
@RunWith(AndroidJUnit4::class)
class BarContentAlignmentDeviceTest {
    private fun host(
        layout: String = "qwerty26",
        clipboard: Boolean = false,
        block: (FeatureHost) -> Unit,
    ) {
        val host = FeatureHost(engine.backend(), layout)
        try {
            if (clipboard) {
                host.chips = true
                host.allowClipboardReads()
                host.main { host.context.getSystemService(ClipboardManager::class.java).clearPrimaryClip() }
            }
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun FeatureHost.assertCentred(what: String) {
        main {
            relayout()
            val band = controller.bandRow
            val density = context.resources.displayMetrics.density
            val shown = (0 until band.childCount).map { band.getChildAt(it) }.filter { it.visibility == View.VISIBLE }
            assertEquals("$what: one kind of content at a time", 1, shown.size)
            val view = shown.single()
            val underReadingRow = controller.readingRow.visibility == View.VISIBLE
            val expected = band.height / 2f + BarContentBox.dropDp(underReadingRow) * density
            val actual = view.top + (view as BarContent).contentCentreY()
            assertEquals("$what: centre in dp", expected / density, actual / density, 1f)
        }
    }

    @Test
    fun everyChildOfTheBandRowIsBarContent() =
        host { host ->
            host.start()
            host.main {
                val band = host.controller.bandRow
                assertEquals(3, band.childCount)
                for (i in 0 until band.childCount) {
                    assertTrue("${band.getChildAt(i)} bypasses the shared rule", band.getChildAt(i) is BarContent)
                }
            }
        }

    @Test
    fun theToolbarCandidatesChipNoticeAndAutofillShareTheCentreLine() =
        host(clipboard = true) { host ->
            host.start()
            host.assertCentred("toolbar")

            host.main {
                host.context
                    .getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("t", "copied"))
            }
            assertTrue(host.await { host.toolbarLabels().any { it == host.string(R.string.ime_chip_dismiss) } })
            host.assertCentred("copied chip")

            host.main { host.controller.setEngineNotice(EngineNotice(EngineNoticeKind.PREPARING)) }
            host.assertCentred("notice")
            host.main { host.controller.setEngineNotice(null) }

            host.type("ni")
            assertTrue(host.await { host.barLabels().isNotEmpty() })
            host.assertCentred("candidates")
        }

    @Test
    fun autofillChipsAreRequestedAtTheContentBoxAndSitOnTheCentreLine() =
        host { host ->
            host.start()
            val strip = host.controller.inlineSuggestions
            val heightPx =
                host.main2 {
                    (strip.contentHeightDp * host.context.resources.displayMetrics.density).toInt()
                }
            for (given in listOf(heightPx, heightPx - 20, heightPx + 60)) {
                host.main {
                    strip.show(
                        listOf(
                            chip(host, "github.com", given),
                            chip(host, "打开 1Password", given),
                        ),
                    )
                }
                host.assertCentred("autofill chips of height $given")
            }
        }

    @Test
    fun autofillChipsSitOnTheCentreLineUnderAReadingRow() =
        host(layout = "merged18") { host ->
            host.start()
            // The window may be short on a given run; the rule is what is under test, not the budget.
            host.main {
                host.controller.readingRowDp = ReadingRowBudget.ROW_DP
                host.controller.updateReadingSurface(null, false)
            }
            assertEquals(View.VISIBLE, host.main2 { host.controller.readingRow.visibility })
            host.assertCentred("toolbar under the reading row")
            val strip = host.controller.inlineSuggestions
            val heightPx =
                host.main2 {
                    (strip.contentHeightDp * host.context.resources.displayMetrics.density).toInt()
                }
            host.main { strip.show(listOf(chip(host, "github.com", heightPx))) }
            host.assertCentred("autofill under the reading row")
        }

    @Test
    fun theAutofillStripHasNoCloseButtonAndNothingCoversAChip() =
        host { host ->
            host.start()
            val strip = host.controller.inlineSuggestions
            host.main { strip.show(listOf(chip(host, "Bitwarden My vault", 100))) }
            host.main {
                host.relayout()
                val band = host.controller.bandRow
                val visible =
                    (0 until band.childCount).map { band.getChildAt(it) }.filter {
                        it.visibility ==
                            View.VISIBLE
                    }
                assertEquals("only the strip is shown in the band", listOf<View>(strip), visible)
                val texts = mutableListOf<String>()

                fun collect(view: View) {
                    if (view is TextView) texts += view.text.toString()
                    if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                }
                collect(band)
                assertFalse(texts.toString(), texts.any { it == "×" })
                val chip = (strip.getChildAt(0) as ViewGroup).getChildAt(0)
                assertTrue(chip.width > 0)
                // The strip runs to the band's end: no room is held back for a button.
                assertEquals(band.width, strip.width)
                assertEquals(0, strip.paddingEnd)
            }
        }

    private fun chip(
        host: FeatureHost,
        label: String,
        heightPx: Int,
    ): View =
        TextView(host.context).apply {
            text = label
            gravity = android.view.Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(300, heightPx)
        }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("bar-alignment", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
