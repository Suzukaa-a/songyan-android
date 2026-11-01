package app.songyan.ime.learning

import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceIds

/**
 * The `sourceID` of a learning backup, in the iOS structured-source format, and which backups may
 * merge into which learning namespace.
 *
 * iOS names a source `userDictionary` (custom pinyin over an imported dictionary) or
 * `userScheme:<packageID>`; its bundled schemes are `userScheme:songyan-builtin`. Android writes
 * the same keys, so a backup's origin reads the same on both platforms.
 *
 * Merging is allowed into the namespace the backup's source learns in on Android: the built-in
 * input methods and the imported dictionary share one, each scheme package has its own. iOS
 * identifies an imported package by its own package id, which Android cannot map to its content
 * hash, so a backup of an iOS-imported package is offered to whichever scheme package is in use;
 * the engine then accepts it only when every dictionary in it is one that package learns into.
 * The iOS check that the resource revision is identical is not kept: the revision changes with
 * every update of the source, which would make a backup unusable after any dictionary import,
 * and what decides whether learned entries fit is the dictionary and its coding, which the engine
 * checks.
 */
object LearningBackupIdentity {
    private const val USER_DICTIONARY = "userDictionary"
    private const val USER_SCHEME_PREFIX = "userScheme:"

    fun archiveSourceId(sourceId: String): String =
        when (sourceId) {
            SourceIds.DICTIONARY -> USER_DICTIONARY
            else -> USER_SCHEME_PREFIX + sourceId
        }

    /** Whether a backup made for [archiveSourceId] may merge into the learning data of [currentSourceId]. */
    fun accepts(
        archiveSourceId: String,
        currentSourceId: String,
    ): Boolean {
        val current = LearningData.namespace(currentSourceId)
        return when (val origin = origin(archiveSourceId)) {
            Origin.Unknown -> false
            is Origin.Namespace -> origin.namespace == current
            Origin.ForeignScheme -> current != LearningData.PINYIN
        }
    }

    private sealed interface Origin {
        data class Namespace(
            val namespace: String,
        ) : Origin

        /** A scheme package named by a package id that is not an Android source id (an iOS import). */
        data object ForeignScheme : Origin

        data object Unknown : Origin
    }

    private fun origin(archiveSourceId: String): Origin {
        if (archiveSourceId == USER_DICTIONARY) return Origin.Namespace(LearningData.PINYIN)
        if (!archiveSourceId.startsWith(USER_SCHEME_PREFIX)) return Origin.Unknown
        val packageId = archiveSourceId.removePrefix(USER_SCHEME_PREFIX)
        if (packageId.isEmpty()) return Origin.Unknown
        if (packageId == SourceIds.BUILTIN) return Origin.Namespace(LearningData.PINYIN)
        if (SourceIds.isValid(packageId) && SourceIds.isScheme(packageId)) {
            return Origin.Namespace(LearningData.namespace(packageId))
        }
        return Origin.ForeignScheme
    }
}
