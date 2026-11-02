package app.songyan.ime.settings.ui

import app.songyan.ime.settings.state.ImportedSchemeInfo
import app.songyan.ime.settings.state.TransferProgress

/** State of the one in-flight scheme package import the 输入方案 screen shows. */
sealed interface SchemeImportUiState {
    data object Idle : SchemeImportUiState

    /** [transfer] is how much of a downloaded package has arrived; null for a picked file. */
    data class Running(
        val transfer: TransferProgress? = null,
        val cancelling: Boolean = false,
    ) : SchemeImportUiState

    data class Succeeded(
        /** The imported scheme, when the app could report it; the snackbar names it when present. */
        val info: ImportedSchemeInfo?,
        /** True when a stored package was updated rather than a new one added. */
        val updated: Boolean = false,
    ) : SchemeImportUiState {
        /** The package came with learned words and the user has not answered yet. */
        val offersLearning: Boolean get() = info != null && info.sourceId != null && info.learningDictionaries > 0
    }

    /** An update found nothing newer; [info] names the package. */
    data class UpToDate(
        val info: ImportedSchemeInfo?,
    ) : SchemeImportUiState

    /** The learned words a package brought are being merged. */
    data object MergingLearning : SchemeImportUiState

    /** The learned words were merged; [message] says how many. */
    data class LearningMerged(
        val message: String,
    ) : SchemeImportUiState

    data class Failed(
        /** Message from the gateway, already localized; null means the screen supplies generic copy. */
        val message: String?,
        /** Whether the card offers "retry" for the same request. */
        val retryable: Boolean = false,
    ) : SchemeImportUiState
}

/**
 * Returns the trimmed link when [input] can be downloaded as a scheme package: https, a host, and no
 * user name or password in front of it. A GitHub repository address is fine; the app turns it into
 * the repository's ZIP.
 */
fun normalizeSchemeUrl(input: String): String? {
    val link = normalizeDictionaryUrl(input) ?: return null
    val authority =
        link
            .substring("https://".length)
            .substringBefore('/')
            .substringBefore('?')
            .substringBefore('#')
    return link.takeIf { '@' !in authority }
}
