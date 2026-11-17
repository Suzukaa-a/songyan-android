package app.songyan.ime.ime

import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The comment master switch over the real engine: the engine keeps producing comments, and the
 * candidate bar draws none of them and gives none of them width, collapsed or expanded.
 */
@RunWith(AndroidJUnit4::class)
class RealEngineCandidateCommentsTest : RealEngineEditorTestBase() {
    private fun RealEngineEditorTestBase.Host.commentedEntries(): List<CandidateBarView.Entry> =
        main2 { find(CandidateBarView::class.java).entries.filter { it.comment.isNotEmpty() } }

    /** What the bar draws for each commented entry: whether the comment is shown and the width it asks for. */
    private fun RealEngineEditorTestBase.Host.commentState(): List<Pair<Boolean, Float>> =
        main2 {
            val bar = find(CandidateBarView::class.java)
            bar.entries.filter { it.comment.isNotEmpty() }.map { bar.showsComment(it) to bar.chipWidth(it) }
        }

    @Test
    fun noCommentIsDrawnOrMeasuredWhileCommentsAreOffCollapsedOrExpanded() =
        withHost(schema = "comment_probe", fixtureAssetRoot = "engine-fixtures/comments") { host ->
            host.start()
            host.typeKeys("n")
            host.awaitComposing()
            assertTrue("the engine sends comments", host.commentedEntries().isNotEmpty())
            val wide = host.commentState()
            assertTrue("the collapsed row shows comments by default", wide.all { it.first })

            host.preferences.showCandidateComments.value = false
            host.awaitIdle()
            val plainWidths =
                host.main2 {
                    host
                        .find(CandidateBarView::class.java)
                        .entries
                        .filter { it.comment.isNotEmpty() }
                        .map { it.textWidth + 2 * host.chipPaddingPx() }
                }
            val collapsed = host.commentState()
            assertTrue("collapsed: no comment is shown", collapsed.none { it.first })
            assertEquals(
                "collapsed: each cell is as wide as a plain candidate",
                plainWidths,
                collapsed.map { it.second },
            )
            host.saveScreenshot("comments-off-collapsed")
            assertTrue("the comment text is still the engine's", host.commentedEntries().isNotEmpty())

            host.tap(host.string(R.string.ime_expand), bar = true)
            host.awaitIdle()
            assertTrue(host.main2 { host.find(CandidateBarView::class.java).expanded })
            val expanded = host.commentState()
            assertTrue("expanded: no comment is shown", expanded.none { it.first })
            host.saveScreenshot("comments-off-expanded")

            host.preferences.showCandidateComments.value = true
            host.saveScreenshot("comments-on-expanded")
            host.awaitIdle()
            assertTrue("expanded with comments on shows them", host.commentState().all { it.first })
            assertEquals(View.VISIBLE, host.main2 { host.find(CandidateBarView::class.java).visibility })
        }
}
