package app.songyan.ime.ime

import android.content.Context
import android.graphics.Rect
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard surfaces are Canvas views, so accessibility depends entirely on the virtual node
 * provider in [AccessibleCanvasView]. This checks the provider directly: the root must expose one
 * child per drawn item, and each child must be a labelled, clickable node whose action reaches the
 * view.
 */
@RunWith(AndroidJUnit4::class)
class AccessibleCanvasViewTest {
    private class FakeView(
        context: Context,
    ) : AccessibleCanvasView(context) {
        var clicked = -1
        private val labels = listOf("q", "空格", "换行")

        override val itemCount: Int get() = labels.size

        override fun itemBounds(index: Int): Rect = Rect(index * 100, 0, (index + 1) * 100, 100)

        override fun itemLabel(index: Int): String = labels[index]

        override fun activateItem(index: Int) {
            clicked = index
        }

        override fun itemAt(
            x: Float,
            y: Float,
        ): Int = (x / 100).toInt().coerceIn(0, labels.lastIndex)
    }

    @Test
    fun providerExposesOneNodePerItem() {
        val view = FakeView(ApplicationProvider.getApplicationContext())
        view.measure(
            View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, 300, 100)

        val provider = view.accessibilityNodeProvider
        assertNotNull("canvas views must publish a virtual node provider", provider)

        val root = provider!!.createAccessibilityNodeInfo(View.NO_ID)
        assertNotNull(root)
        assertEquals(3, root!!.childCount)

        val space = provider.createAccessibilityNodeInfo(1)
        assertNotNull(space)
        assertEquals("空格", space!!.contentDescription)
        assertTrue(space.isClickable)
        assertTrue(space.isFocusable)
        val bounds = Rect()
        space.getBoundsInParent(bounds)
        assertEquals(Rect(100, 0, 200, 100), bounds)

        assertTrue(provider.performAction(1, AccessibilityNodeInfo.ACTION_CLICK, null))
        assertEquals(1, view.clicked)
    }
}
