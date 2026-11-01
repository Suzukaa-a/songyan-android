package app.songyan.ime.content

import app.songyan.ime.R
import app.songyan.ime.packages.DictionaryImportError
import java.io.IOException

/**
 * Why one dictionary request stopped. The deploy process records the kind's name in the request
 * status; the settings gateway maps it to a string resource, so the copy stays localizable and the
 * mapping testable.
 */
internal enum class DictionaryImportProblemKind {
    /** Not a .dict.yaml file, or a file name the stager cannot represent. */
    UNSUPPORTED_FILE,
    ENCODING,
    FILE_TOO_LARGE,
    TOO_MANY_FILES,
    DUPLICATE_FILE,
    TOO_MANY_ENTRIES,
    EMPTY,

    /** A header or column shape the importer does not support. */
    FORMAT,

    /** The selected dictionaries import files that were not selected. */
    MISSING_DEPENDENCIES,
    DEPENDENCY_CYCLE,

    /** A selected file is not part of the imported dictionary. */
    UNREFERENCED_FILE,

    /** An entry row the importer rejects; the message names the file and line. */
    INVALID_ROW,
    INVALID_WORD,
    SYLLABLE_COUNT_MISMATCH,
    UNKNOWN_SYLLABLE,
    INVALID_WEIGHT,
    TEMPLATE,
    READ_FAILED,
    UNSUPPORTED_URL,

    /** The link does not point at a .dict.yaml file. */
    UNSUPPORTED_URL_FILE,
    DOWNLOAD_FAILED,

    /** The link's domain does not resolve although the device is online. */
    DOWNLOAD_HOST,

    /** The device has no network connection. */
    DOWNLOAD_OFFLINE,

    /** The server answered with an error; the status code is the problem's detail. */
    DOWNLOAD_SERVER,

    /** A scheme package link answered with something that is not a ZIP archive. */
    NOT_ZIP,

    /** The server has no file at the link (404 / 410). */
    DOWNLOAD_NOT_FOUND,
    DOWNLOAD_TIMEOUT,
    TOO_MANY_REDIRECTS,

    /** An HTTPS link redirected to plain HTTP. */
    INSECURE_REDIRECT,
    STAGING_FAILED,
    DEPLOY_UNAVAILABLE,
    DEPLOY_FAILED,
    DEPLOY_TIMEOUT,
    CANCELLED,
    REMOVE_FAILED,

    /** The chosen scheme package's dictionary cannot take an imported dictionary. */
    TARGET_UNSUPPORTED,
}

internal class DictionaryImportProblemException(
    val kind: DictionaryImportProblemKind,
    cause: Throwable? = null,
    /** Extra fact for the message, such as the HTTP status of [DictionaryImportProblemKind.DOWNLOAD_SERVER]. */
    val detail: String? = null,
) : IOException(kind.name, cause)

internal object DictionaryImportProblems {
    /** Largest accepted total, as shown to the user. */
    const val SIZE_LIMIT_LABEL = "64 MB"

    fun kindOf(reason: DictionaryImportError.Reason): DictionaryImportProblemKind =
        when (reason) {
            DictionaryImportError.Reason.UNSUPPORTED_FILE_TYPE,
            DictionaryImportError.Reason.UNSAFE_FILE_NAME,
            -> DictionaryImportProblemKind.UNSUPPORTED_FILE

            DictionaryImportError.Reason.INVALID_ENCODING -> DictionaryImportProblemKind.ENCODING
            DictionaryImportError.Reason.FILE_TOO_LARGE -> DictionaryImportProblemKind.FILE_TOO_LARGE
            DictionaryImportError.Reason.TOO_MANY_FILES -> DictionaryImportProblemKind.TOO_MANY_FILES
            DictionaryImportError.Reason.DUPLICATE_FILE -> DictionaryImportProblemKind.DUPLICATE_FILE
            DictionaryImportError.Reason.EMPTY_DICTIONARY -> DictionaryImportProblemKind.EMPTY
            DictionaryImportError.Reason.TOO_MANY_ENTRIES -> DictionaryImportProblemKind.TOO_MANY_ENTRIES
            DictionaryImportError.Reason.MISSING_DEPENDENCIES -> DictionaryImportProblemKind.MISSING_DEPENDENCIES
            DictionaryImportError.Reason.DEPENDENCY_CYCLE -> DictionaryImportProblemKind.DEPENDENCY_CYCLE
            DictionaryImportError.Reason.UNREFERENCED_FILE -> DictionaryImportProblemKind.UNREFERENCED_FILE
            DictionaryImportError.Reason.INCOMPATIBLE_TEMPLATE -> DictionaryImportProblemKind.TEMPLATE
            DictionaryImportError.Reason.INVALID_ROW -> DictionaryImportProblemKind.INVALID_ROW
            DictionaryImportError.Reason.NON_HAN_WORD -> DictionaryImportProblemKind.INVALID_WORD
            DictionaryImportError.Reason.SYLLABLE_COUNT_MISMATCH -> DictionaryImportProblemKind.SYLLABLE_COUNT_MISMATCH
            DictionaryImportError.Reason.UNKNOWN_SYLLABLE -> DictionaryImportProblemKind.UNKNOWN_SYLLABLE
            DictionaryImportError.Reason.INVALID_WEIGHT -> DictionaryImportProblemKind.INVALID_WEIGHT

            DictionaryImportError.Reason.MISSING_HEADER_END,
            DictionaryImportError.Reason.UNSUPPORTED_YAML,
            DictionaryImportError.Reason.MISSING_NAME,
            DictionaryImportError.Reason.UNSUPPORTED_PRESET_VOCABULARY,
            DictionaryImportError.Reason.UNSUPPORTED_SORT_ORDER,
            DictionaryImportError.Reason.UNSUPPORTED_COLUMNS,
            -> DictionaryImportProblemKind.FORMAT
        }

    /** Kinds a second attempt can fix without changing the link. */
    fun retryable(kind: DictionaryImportProblemKind): Boolean =
        when (kind) {
            DictionaryImportProblemKind.DOWNLOAD_FAILED,
            DictionaryImportProblemKind.DOWNLOAD_OFFLINE,
            DictionaryImportProblemKind.DOWNLOAD_SERVER,
            DictionaryImportProblemKind.DOWNLOAD_TIMEOUT,
            DictionaryImportProblemKind.STAGING_FAILED,
            DictionaryImportProblemKind.DEPLOY_FAILED,
            DictionaryImportProblemKind.DEPLOY_UNAVAILABLE,
            DictionaryImportProblemKind.DEPLOY_TIMEOUT,
            -> true

            else -> false
        }

    /** Kinds whose copy names the file and line of the rejected entry. */
    fun namesLine(kind: DictionaryImportProblemKind): Boolean =
        when (kind) {
            DictionaryImportProblemKind.INVALID_ROW,
            DictionaryImportProblemKind.INVALID_WORD,
            DictionaryImportProblemKind.SYLLABLE_COUNT_MISMATCH,
            DictionaryImportProblemKind.UNKNOWN_SYLLABLE,
            DictionaryImportProblemKind.INVALID_WEIGHT,
            -> true

            else -> false
        }

    /**
     * Splits the importer's `path:line` detail. The importer reports the detail only for entry
     * rows, so anything else yields null and the caller falls back to a generic message.
     */
    fun parseLocation(detail: String?): Pair<String, Int>? {
        val colon = detail?.lastIndexOf(':') ?: return null
        if (colon <= 0) return null
        val line = detail.substring(colon + 1).toIntOrNull() ?: return null
        return detail.substring(0, colon).substringAfterLast('/') to line
    }

    fun kindNamed(name: String?): DictionaryImportProblemKind? =
        DictionaryImportProblemKind.entries.firstOrNull { it.name == name }

    fun messageRes(kind: DictionaryImportProblemKind): Int =
        when (kind) {
            DictionaryImportProblemKind.UNSUPPORTED_FILE -> R.string.dictionary_import_error_unsupported_file
            DictionaryImportProblemKind.ENCODING -> R.string.dictionary_import_error_encoding
            DictionaryImportProblemKind.FILE_TOO_LARGE -> R.string.dictionary_import_error_too_large
            DictionaryImportProblemKind.TOO_MANY_FILES -> R.string.dictionary_import_error_too_many_files
            DictionaryImportProblemKind.DUPLICATE_FILE -> R.string.dictionary_import_error_duplicate_file
            DictionaryImportProblemKind.TOO_MANY_ENTRIES -> R.string.dictionary_import_error_too_many_entries
            DictionaryImportProblemKind.EMPTY -> R.string.dictionary_import_error_empty
            DictionaryImportProblemKind.FORMAT -> R.string.dictionary_import_error_format
            DictionaryImportProblemKind.MISSING_DEPENDENCIES -> R.string.dictionary_import_error_missing_dependencies
            DictionaryImportProblemKind.DEPENDENCY_CYCLE -> R.string.dictionary_import_error_dependency_cycle
            DictionaryImportProblemKind.UNREFERENCED_FILE -> R.string.dictionary_import_error_unreferenced_file
            DictionaryImportProblemKind.INVALID_ROW -> R.string.dictionary_import_error_row
            DictionaryImportProblemKind.INVALID_WORD -> R.string.dictionary_import_error_word
            DictionaryImportProblemKind.SYLLABLE_COUNT_MISMATCH -> R.string.dictionary_import_error_syllable_count
            DictionaryImportProblemKind.UNKNOWN_SYLLABLE -> R.string.dictionary_import_error_syllable_unknown
            DictionaryImportProblemKind.INVALID_WEIGHT -> R.string.dictionary_import_error_weight
            DictionaryImportProblemKind.TEMPLATE -> R.string.dictionary_import_error_template
            DictionaryImportProblemKind.READ_FAILED -> R.string.dictionary_import_error_read
            DictionaryImportProblemKind.UNSUPPORTED_URL -> R.string.dictionary_import_error_url
            DictionaryImportProblemKind.UNSUPPORTED_URL_FILE -> R.string.dictionary_import_error_url_file
            DictionaryImportProblemKind.DOWNLOAD_FAILED -> R.string.dictionary_import_error_download
            DictionaryImportProblemKind.DOWNLOAD_HOST -> R.string.dictionary_import_error_download_host
            DictionaryImportProblemKind.DOWNLOAD_OFFLINE -> R.string.dictionary_import_error_download_offline
            DictionaryImportProblemKind.DOWNLOAD_SERVER -> R.string.dictionary_import_error_download_server
            DictionaryImportProblemKind.NOT_ZIP -> R.string.scheme_import_error_link_not_zip
            DictionaryImportProblemKind.DOWNLOAD_NOT_FOUND -> R.string.dictionary_import_error_download_not_found
            DictionaryImportProblemKind.DOWNLOAD_TIMEOUT -> R.string.dictionary_import_error_download_timeout
            DictionaryImportProblemKind.TOO_MANY_REDIRECTS -> R.string.dictionary_import_error_redirects
            DictionaryImportProblemKind.INSECURE_REDIRECT -> R.string.dictionary_import_error_insecure_redirect
            DictionaryImportProblemKind.STAGING_FAILED -> R.string.dictionary_import_error_staging
            DictionaryImportProblemKind.DEPLOY_UNAVAILABLE -> R.string.dictionary_import_error_deploy_unavailable
            DictionaryImportProblemKind.DEPLOY_FAILED -> R.string.dictionary_import_error_deploy
            DictionaryImportProblemKind.DEPLOY_TIMEOUT -> R.string.dictionary_import_error_timeout
            DictionaryImportProblemKind.CANCELLED -> R.string.import_cancelled
            DictionaryImportProblemKind.REMOVE_FAILED -> R.string.dictionary_import_error_remove
            DictionaryImportProblemKind.TARGET_UNSUPPORTED -> R.string.dictionary_import_error_target
        }
}
