package app.songyan.ime.ime

import android.content.Context
import android.graphics.Rect
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeProvider

abstract class AccessibleCanvasView(
    context: Context,
) : View(context) {
    protected abstract val itemCount: Int

    protected abstract fun itemBounds(index: Int): Rect

    protected abstract fun itemLabel(index: Int): String

    protected abstract fun activateItem(index: Int)

    protected abstract fun itemAt(
        x: Float,
        y: Float,
    ): Int

    /** True when the item behaves like a long press, i.e. has a discrete action beyond click. */
    protected open fun itemLongClickable(index: Int): Boolean = false

    /** Performs the item's long-press behaviour; false means there is none. */
    protected open fun performItemLongClick(index: Int): Boolean = false

    /** An item took accessibility focus; a scrolling surface brings it into view. */
    protected open fun onItemAccessibilityFocused(index: Int) = Unit

    /** Extra actions of one item (alternates, overflow choices); empty by default. */
    protected open fun itemCustomActions(index: Int): List<AccessibilityNodeInfo.AccessibilityAction> = emptyList()

    /** Performs one action returned by [itemCustomActions]. */
    protected open fun performItemCustomAction(
        index: Int,
        actionId: Int,
    ): Boolean = false

    /**
     * Scroll actions of the surface as a whole (ACTION_SCROLL_FORWARD / BACKWARD); empty for a surface
     * that does not scroll. Assistive technology uses them to reach items that are off screen.
     */
    protected open fun hostScrollActions(): List<AccessibilityNodeInfo.AccessibilityAction> = emptyList()

    /** Performs one action returned by [hostScrollActions]. */
    protected open fun performHostAction(action: Int): Boolean = false

    /** The order TalkBack walks the items in; override when the visual order differs from the index order. */
    protected open fun itemOrder(): List<Int> = (0 until itemCount).toList()

    private var focused = -1
    private var hovered = -1
    private val manager = context.getSystemService(AccessibilityManager::class.java)
    private val provider =
        object : AccessibilityNodeProvider() {
            override fun createAccessibilityNodeInfo(id: Int): AccessibilityNodeInfo? {
                if (id == View.NO_ID) {
                    return AccessibilityNodeInfo.obtain(this@AccessibleCanvasView).apply {
                        onInitializeAccessibilityNodeInfo(this)
                        for (i in itemOrder()) addChild(this@AccessibleCanvasView, i)
                        val scrolls = hostScrollActions()
                        if (scrolls.isNotEmpty()) {
                            isScrollable = true
                            scrolls.forEach { addAction(it) }
                        }
                    }
                }
                if (id !in 0 until itemCount) return null
                return AccessibilityNodeInfo.obtain().apply {
                    setSource(this@AccessibleCanvasView, id)
                    setParent(this@AccessibleCanvasView)
                    packageName = context.packageName
                    className = "android.widget.Button"
                    contentDescription = itemLabel(id)
                    isEnabled = this@AccessibleCanvasView.isEnabled
                    isClickable = true
                    val longClickable = itemLongClickable(id)
                    isLongClickable = longClickable
                    isFocusable = true
                    isVisibleToUser = isShown
                    isAccessibilityFocused = focused == id
                    setBoundsInParent(itemBounds(id))
                    val screen = IntArray(2)
                    getLocationOnScreen(screen)
                    val bounds = Rect(itemBounds(id))
                    bounds.offset(screen[0], screen[1])
                    setBoundsInScreen(bounds)
                    addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK)
                    if (longClickable) addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK)
                    itemCustomActions(id).forEach { addAction(it) }
                    addAction(
                        if (focused == id) {
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                        } else {
                            AccessibilityNodeInfo.AccessibilityAction.ACTION_ACCESSIBILITY_FOCUS
                        },
                    )
                }
            }

            override fun performAction(
                id: Int,
                action: Int,
                arguments: Bundle?,
            ): Boolean {
                if (id == View.NO_ID) return performHostAction(action)
                if (id !in 0 until itemCount) return false
                when (action) {
                    AccessibilityNodeInfo.ACTION_CLICK -> {
                        activateItem(id)
                        emit(id, AccessibilityEvent.TYPE_VIEW_CLICKED)
                    }
                    AccessibilityNodeInfo.ACTION_LONG_CLICK -> {
                        if (!performItemLongClick(id)) return false
                        emit(id, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                    }
                    AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS -> {
                        if (focused >= 0) emit(focused, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                        focused = id
                        onItemAccessibilityFocused(id)
                        emit(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED)
                    }
                    AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS -> {
                        focused = -1
                        emit(id, AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED)
                    }
                    else -> return performItemCustomAction(id, action)
                }
                invalidate()
                return true
            }
        }

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        isFocusable = true
    }

    override fun getAccessibilityNodeProvider(): AccessibilityNodeProvider = provider

    private fun emit(
        id: Int,
        type: Int,
    ) {
        if (!manager.isEnabled) return
        val event = AccessibilityEvent.obtain(type)
        event.setSource(this, id)
        event.packageName = context.packageName
        event.className = "android.widget.Button"
        if (id in 0 until itemCount) event.contentDescription = itemLabel(id)
        parent?.requestSendAccessibilityEvent(this, event)
    }

    override fun dispatchHoverEvent(event: MotionEvent): Boolean {
        if (!manager.isTouchExplorationEnabled) return super.dispatchHoverEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT &&
            hovered >= 0 &&
            itemAt(event.x, event.y) == hovered
        ) {
            activateItem(hovered)
            emit(hovered, AccessibilityEvent.TYPE_VIEW_CLICKED)
        }
        val next = if (event.actionMasked == MotionEvent.ACTION_HOVER_EXIT) -1 else itemAt(event.x, event.y)
        if (next != hovered) {
            if (next >= 0) emit(next, AccessibilityEvent.TYPE_VIEW_HOVER_ENTER)
            if (hovered >= 0) emit(hovered, AccessibilityEvent.TYPE_VIEW_HOVER_EXIT)
            hovered = next
        }
        return true
    }
}
