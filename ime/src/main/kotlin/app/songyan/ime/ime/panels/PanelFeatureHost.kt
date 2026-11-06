package app.songyan.ime.ime.panels

/**
 * The view of a panel that has been opened at least once, or null while it has never opened.
 *
 * Editor, insert and refresh callbacks can arrive while a panel is closed, so they read this
 * instead of the lazy value itself, which would build a panel the user never opened.
 */
internal val <T> Lazy<T>.initializedValue: T?
    get() = if (isInitialized()) value else null

/**
 * Features share the coordinator's view registration, presentation and visibility checks.
 * Access stays on the UI thread, so lazy views need no synchronization.
 */
internal interface PanelFeatureHost {
    /**
     * A panel view created at most once, the first time its panel opens.
     *
     * The host registers what it creates, so declaring a feature's view through this call is all it
     * takes for appearance refresh and memory trimming to reach it.
     */
    fun <T : QuickContentPanelView> lazyPanel(factory: () -> T): Lazy<T>

    /** Shows [panel] and records it as the one the keyboard last opened. */
    fun show(panel: QuickContentPanelView)

    /** Whether [view] is the panel actually on screen; a hidden panel must receive nothing. */
    fun isVisible(view: QuickContentPanelView): Boolean

    /** Closes the panel if one is open. */
    fun close()
}
