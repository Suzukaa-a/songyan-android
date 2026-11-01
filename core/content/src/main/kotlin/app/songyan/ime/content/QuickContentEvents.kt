package app.songyan.ime.content

/**
 * Process-local event used to tell a running keyboard that the quick-content files changed.
 *
 * The settings screen writes those files directly, while the keyboard keeps its own in-memory
 * snapshot; a package-scoped broadcast closes that gap without making either side read the disk on
 * the main thread. The receiver must be registered with RECEIVER_NOT_EXPORTED.
 */
object QuickContentEvents {
    const val ACTION_CHANGED = "app.songyan.ime.action.QUICK_CONTENT_CHANGED"
}
