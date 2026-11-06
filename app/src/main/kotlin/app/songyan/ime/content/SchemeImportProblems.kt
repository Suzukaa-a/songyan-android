package app.songyan.ime.content

import app.songyan.ime.R
import app.songyan.ime.packages.SchemePackageImportError

/**
 * Why one scheme package request stopped. The deploy process records the kind's name in the
 * request status; the settings gateway maps it to a string resource.
 */
internal enum class SchemeImportProblemKind {
    /** The file is not a readable ZIP archive. */
    NOT_A_PACKAGE,

    /** Entry names are not UTF-8 (an archive written by an old Windows zip tool). */
    NAME_ENCODING,
    FILE_TOO_LARGE,
    TOO_MANY_FILES,

    /** A member path is absolute, escapes the archive or repeats. */
    UNSAFE_CONTENTS,

    /** Two archives of the package fill the same runtime file differently. */
    CONFLICTING_FILES,

    /** The package carries learning data. */
    USER_DATA,
    MISSING_SCHEMA,

    /** A schema file is malformed or its `schema_id` does not match its file name. */
    INVALID_SCHEMA,

    /** A configuration file is not UTF-8 text. */
    ENCODING,

    /** A scheme needs files the package does not ship. */
    MISSING_DEPENDENCY,
    INVALID_MODEL,

    /** A `*.custom.yaml` patches one of the bundled input methods. */
    BUILTIN_CUSTOMIZATION,
    READ_FAILED,
    STAGING_FAILED,

    /** Android refused to start the deploy service right now. */
    DEPLOY_UNAVAILABLE,
    DEPLOY_FAILED,
    DEPLOY_TIMEOUT,
    CANCELLED,

    /** The new version of a package cannot take the dictionary the user stacked onto it. */
    DICTIONARY_STACK,

    /** The package an update is meant for is no longer stored. */
    UPDATE_TARGET_MISSING,

    /** The generator refused an input method of the package for the 14-, 18- and 9-key layouts. */
    KEYBOARD_COMPANION,
}

internal class SchemeImportProblemException(
    val kind: SchemeImportProblemKind,
    cause: Throwable? = null,
) : java.io.IOException(kind.name, cause)

/** The stable core reasons of [SchemePackageImportError] mapped onto the app's problem kinds. */
internal object SchemeImportProblems {
    /** Largest accepted archive, as shown to the user; the core builder enforces the byte cap. */
    const val SIZE_LIMIT_LABEL = "1.5 GB"

    fun kindOf(reason: SchemePackageImportError.Reason): SchemeImportProblemKind =
        when (reason) {
            SchemePackageImportError.Reason.INVALID_ZIP -> SchemeImportProblemKind.NOT_A_PACKAGE
            SchemePackageImportError.Reason.UNSUPPORTED_NAME_ENCODING -> SchemeImportProblemKind.NAME_ENCODING
            SchemePackageImportError.Reason.TOO_LARGE -> SchemeImportProblemKind.FILE_TOO_LARGE
            SchemePackageImportError.Reason.TOO_MANY_ENTRIES -> SchemeImportProblemKind.TOO_MANY_FILES
            SchemePackageImportError.Reason.UNSAFE_PATH,
            SchemePackageImportError.Reason.DUPLICATE_PATH,
            -> SchemeImportProblemKind.UNSAFE_CONTENTS

            SchemePackageImportError.Reason.USER_DATA -> SchemeImportProblemKind.USER_DATA
            SchemePackageImportError.Reason.MISSING_SCHEMA -> SchemeImportProblemKind.MISSING_SCHEMA
            SchemePackageImportError.Reason.INVALID_SCHEMA -> SchemeImportProblemKind.INVALID_SCHEMA
            SchemePackageImportError.Reason.INVALID_ENCODING -> SchemeImportProblemKind.ENCODING
            SchemePackageImportError.Reason.MISSING_DEPENDENCY -> SchemeImportProblemKind.MISSING_DEPENDENCY
            SchemePackageImportError.Reason.INVALID_MODEL -> SchemeImportProblemKind.INVALID_MODEL
            SchemePackageImportError.Reason.BUILTIN_CUSTOMIZATION -> SchemeImportProblemKind.BUILTIN_CUSTOMIZATION
            SchemePackageImportError.Reason.CONFLICTING_FILE -> SchemeImportProblemKind.CONFLICTING_FILES
        }

    /** Kinds a second attempt can fix without changing the file. */
    fun retryable(kind: SchemeImportProblemKind): Boolean =
        when (kind) {
            SchemeImportProblemKind.STAGING_FAILED,
            SchemeImportProblemKind.DEPLOY_UNAVAILABLE,
            SchemeImportProblemKind.DEPLOY_FAILED,
            SchemeImportProblemKind.DEPLOY_TIMEOUT,
            -> true

            else -> false
        }

    fun kindNamed(name: String?): SchemeImportProblemKind? =
        SchemeImportProblemKind.entries.firstOrNull {
            it.name ==
                name
        }

    fun messageRes(kind: SchemeImportProblemKind): Int =
        when (kind) {
            SchemeImportProblemKind.NOT_A_PACKAGE -> R.string.scheme_import_error_not_a_package
            SchemeImportProblemKind.NAME_ENCODING -> R.string.scheme_import_error_name_encoding
            SchemeImportProblemKind.FILE_TOO_LARGE -> R.string.scheme_import_error_too_large
            SchemeImportProblemKind.TOO_MANY_FILES -> R.string.scheme_import_error_too_many_files
            SchemeImportProblemKind.UNSAFE_CONTENTS -> R.string.scheme_import_error_unsafe_files
            SchemeImportProblemKind.USER_DATA -> R.string.scheme_import_error_user_data
            SchemeImportProblemKind.MISSING_SCHEMA -> R.string.scheme_import_error_missing_scheme
            SchemeImportProblemKind.INVALID_SCHEMA -> R.string.scheme_import_error_invalid_scheme
            SchemeImportProblemKind.ENCODING -> R.string.scheme_import_error_encoding
            SchemeImportProblemKind.MISSING_DEPENDENCY -> R.string.scheme_import_error_missing_dependency
            SchemeImportProblemKind.INVALID_MODEL -> R.string.scheme_import_error_invalid_model
            SchemeImportProblemKind.BUILTIN_CUSTOMIZATION -> R.string.scheme_import_error_builtin_customization
            SchemeImportProblemKind.READ_FAILED -> R.string.scheme_import_error_read
            SchemeImportProblemKind.STAGING_FAILED -> R.string.scheme_import_error_staging
            SchemeImportProblemKind.DEPLOY_UNAVAILABLE -> R.string.scheme_import_error_deploy_unavailable
            SchemeImportProblemKind.DEPLOY_FAILED -> R.string.scheme_import_error_deploy
            SchemeImportProblemKind.DEPLOY_TIMEOUT -> R.string.scheme_import_error_timeout
            SchemeImportProblemKind.CANCELLED -> R.string.import_cancelled
            SchemeImportProblemKind.DICTIONARY_STACK -> R.string.scheme_import_error_dictionary_stack
            SchemeImportProblemKind.UPDATE_TARGET_MISSING -> R.string.scheme_update_error_missing
            SchemeImportProblemKind.KEYBOARD_COMPANION -> R.string.scheme_import_error_keyboard_companion
            SchemeImportProblemKind.CONFLICTING_FILES -> R.string.scheme_import_error_conflicting_files
        }
}
