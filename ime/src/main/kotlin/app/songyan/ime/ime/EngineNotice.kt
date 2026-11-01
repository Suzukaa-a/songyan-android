package app.songyan.ime.ime

/** Why the Chinese engine cannot take input right now. */
enum class EngineNoticeKind {
    /** The engine is being deployed, started or swapped; it comes back on its own. */
    PREPARING,

    /** The last attempt failed; the user can retry or open settings. */
    FAILED,
}

/** What the notice row offers besides reading it. */
enum class EngineNoticeAction {
    RETRY,
    SETTINGS,
}

/**
 * The engine's state as the keyboard shows it. While a notice is set the key area shows the notice
 * instead of keys and nothing is committed; the key area keeps its height, so the editor's layout does
 * not move when the engine comes back.
 */
data class EngineNotice(
    val kind: EngineNoticeKind,
    val actions: List<EngineNoticeAction> = emptyList(),
)
