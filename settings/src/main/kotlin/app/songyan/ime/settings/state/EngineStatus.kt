package app.songyan.ime.settings.state

/**
 * Engine availability as far as the settings UI needs it. The app module maps its real
 * engine/deployment state onto this type; settings never touches the engine directly.
 */
sealed interface EngineStatus {
    /** Engine is usable. */
    data object Ready : EngineStatus

    /** First deployment is running; [percent] is the raw reported progress. */
    data class Preparing(
        val percent: Int,
    ) : EngineStatus

    /** Last deployment or engine start failed. */
    data object Failed : EngineStatus

    /** Nothing reported yet (cold start or no provider wired). */
    data object Unknown : EngineStatus
}

/** Whether a real percentage was reported; without one the UI shows indeterminate progress. */
val EngineStatus.Preparing.hasProgress: Boolean
    get() = percent > 0

/** Progress clamped into the range the UI can display. */
val EngineStatus.Preparing.displayPercent: Int
    get() = percent.coerceIn(0, 100)
