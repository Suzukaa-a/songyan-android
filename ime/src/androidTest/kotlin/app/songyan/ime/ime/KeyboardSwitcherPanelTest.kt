package app.songyan.ime.ime

import android.content.Context
import android.content.res.Configuration
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** P7-1: the in-keyboard menu keeps 48 dp touch targets and stays reachable at a large font size. */
@RunWith(AndroidJUnit4::class)
class KeyboardSwitcherPanelTest {
    private fun panel(fontScale: Float): Pair<KeyboardSwitcherPanel, Float> {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val context =
            base.createConfigurationContext(
                Configuration(base.resources.configuration).apply {
                    this.fontScale =
                        fontScale
                },
            )
        val panel = KeyboardSwitcherPanel(context, BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.QWERTY26)
        // The input method rows are part of the menu when the system has something to switch to.
        panel.setInputMethodOptions(InputMethodOptions(canSwitch = true, voiceLabel = "Voice"))
        val density = context.resources.displayMetrics.density
        panel.measure(
            View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(1_000_000, View.MeasureSpec.AT_MOST),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        return panel to density
    }

    private fun clickables(view: View): List<View> =
        buildList {
            if (view.visibility != View.VISIBLE) return@buildList
            if (view.isClickable) add(view)
            if (view is ViewGroup) for (i in 0 until view.childCount) addAll(clickables(view.getChildAt(i)))
        }

    private fun assertEveryTargetIs48Dp(fontScale: Float) {
        val (panel, density) = panel(fontScale)
        val targets = clickables(panel)
        assertTrue("expected chips, rows and the close button", targets.size >= 9)
        for (view in targets) {
            assertTrue(
                "a ${view.javaClass.simpleName} is ${view.height / density} dp tall at font scale $fontScale",
                view.height >= (48 * density).toInt() - 1,
            )
        }
    }

    @Test
    fun everyTargetIs48DpTallAtTheDefaultFontSize() = assertEveryTargetIs48Dp(1f)

    @Test
    fun everyTargetIs48DpTallAtTheLargestFontSize() = assertEveryTargetIs48Dp(2f)

    @Test
    fun aMenuTallerThanItsWindowShowsThatItScrollsAndReachesTheLastRow() {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val panel = KeyboardSwitcherPanel(base, BuiltinTemplateSchemas.QUANPIN, KeyboardLayoutKind.QWERTY26)
        panel.setInputMethodOptions(InputMethodOptions(canSwitch = true, voiceLabel = "Voice"))
        val density = base.resources.displayMetrics.density
        panel.measure(
            View.MeasureSpec.makeMeasureSpec((411 * density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec((300 * density).toInt(), View.MeasureSpec.EXACTLY),
        )
        panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
        val scroll = panel.scroll
        assertTrue("the content is taller than the window", scroll.canScrollVertically(1))
        assertTrue(
            "the scroll bar is always visible",
            scroll.isVerticalScrollBarEnabled && !scroll.isScrollbarFadingEnabled,
        )
        assertTrue("the bottom edge fades", scroll.isVerticalFadingEdgeEnabled && scroll.verticalFadingEdgeLength > 0)
        scroll.scrollTo(0, Int.MAX_VALUE)
        assertTrue("the last row can be scrolled to", !scroll.canScrollVertically(1))
    }
}
