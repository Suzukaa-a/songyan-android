package app.songyan.ime.settings.ui

/** The one in-flight use or delete request of a stored input scheme. */
sealed interface StoredSourceUiState {
    data object Idle : StoredSourceUiState

    data class Running(
        val sourceId: String,
    ) : StoredSourceUiState

    /** A deletion finished; the screen offers to take it back while the trash still holds it. */
    data class Deleted(
        val sourceId: String,
        val name: String,
    ) : StoredSourceUiState

    /** [message] is already localized; null falls back to the screen's generic copy. */
    data class Failed(
        val message: String?,
    ) : StoredSourceUiState
}
