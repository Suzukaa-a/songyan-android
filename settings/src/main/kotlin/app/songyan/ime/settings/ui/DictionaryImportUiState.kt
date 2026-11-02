package app.songyan.ime.settings.ui

import app.songyan.ime.settings.state.ImportedDictionaryInfo

/** What the user asked the dictionary import to do; the success message depends on it. */
enum class DictionaryImportAction { IMPORT, REMOVE }

/** Why a dictionary request failed; the screen picks the copy for input problems it can fix. */
enum class DictionaryImportFailure { INVALID_URL, GATEWAY }

/** State of the one in-flight dictionary request the 输入方案 screen shows. */
sealed interface DictionaryImportUiState {
    data object Idle : DictionaryImportUiState

    data class Running(
        val action: DictionaryImportAction,
        val cancelling: Boolean = false,
    ) : DictionaryImportUiState

    data class Succeeded(
        val action: DictionaryImportAction,
        val info: ImportedDictionaryInfo?,
    ) : DictionaryImportUiState

    data class Failed(
        val action: DictionaryImportAction,
        val reason: DictionaryImportFailure,
        /** Message from the gateway, already localized; null means the screen supplies generic copy. */
        val message: String?,
        /** Whether the card offers "retry" for the same request. */
        val retryable: Boolean = false,
    ) : DictionaryImportUiState
}

private const val HTTPS_PREFIX = "https://"

/**
 * Returns the trimmed link when [input] is a usable https URL, otherwise null.
 *
 * Dictionary links are fetched over the network, so plain http is refused: the user cannot tell
 * whether a dictionary was tampered with, and the download happens outside the keyboard process.
 */
fun normalizeDictionaryUrl(input: String): String? {
    val trimmed = input.trim()
    if (!trimmed.startsWith(HTTPS_PREFIX, ignoreCase = true)) return null
    val rest = trimmed.substring(HTTPS_PREFIX.length)
    val host = rest.substringBefore('/').substringBefore('?').substringBefore('#')
    if (host.isBlank() || host.contains(' ')) return null
    return trimmed
}
