package app.songyan.ime.rime

import java.io.File

/**
 * Learning backup and merge with librime's own userdb export and import (levers
 * `backup_user_dict` / `restore_user_dict`), with the identity checks of the iOS bridge.
 *
 * Each call opens one prebuilt engine on [userDataDir], does one operation and closes it. librime
 * allows one engine per process, so the caller must hold the process's engine ownership (no other
 * engine open) for the duration.
 */
object RimeLearningBackup {
    /** The learning data of one user directory, limited to the dictionaries the input methods declare. */
    class Export(
        val engineVersion: String,
        val snapshots: List<RimeLearningSnapshot>,
    )

    /** Exports every declared user dictionary that has learning data; an empty list when none has. */
    suspend fun export(
        sharedDataDir: File,
        compiledDir: File,
        userDataDir: File,
        appName: String = RimeEngine.DEFAULT_APP_NAME,
        appVersion: String? = null,
    ): Export =
        open(sharedDataDir, compiledDir, userDataDir, appName, appVersion).use { engine ->
            engine.exportLearningData()
        }

    /**
     * Merges [snapshots] into [userDataDir]. Every snapshot is checked first (format, a dictionary
     * the current input methods declare, the same librime version), so a refused batch writes
     * nothing; [RimeLearningException.Kind.MERGE_UNVERIFIED] means a write was attempted and failed.
     */
    suspend fun merge(
        sharedDataDir: File,
        compiledDir: File,
        userDataDir: File,
        snapshots: List<RimeLearningSnapshot>,
        appName: String = RimeEngine.DEFAULT_APP_NAME,
        appVersion: String? = null,
    ) {
        val documents = RimeUserDbSnapshot.parseBatch(snapshots)
        open(sharedDataDir, compiledDir, userDataDir, appName, appVersion).use { engine ->
            engine.mergeLearningData(snapshots, documents)
        }
    }

    /**
     * The user dictionaries the input methods of the generation learn into, all of them whether
     * in use or not. [userDataDir] may be an empty scratch directory: nothing is learned or merged.
     */
    suspend fun declaredDictionaries(
        sharedDataDir: File,
        compiledDir: File,
        userDataDir: File,
        appName: String = RimeEngine.DEFAULT_APP_NAME,
        appVersion: String? = null,
    ): Set<String> =
        open(sharedDataDir, compiledDir, userDataDir, appName, appVersion).use { engine ->
            engine.declaredUserDictionaries()
        }

    /**
     * Merges snapshots from any Rime front-end ([RimeUserDbText]) into [userDataDir] with librime's
     * restore, the merge a sync between devices does: every entry keeps the higher commit count
     * and weight, nothing already learned is replaced or removed. Every snapshot must be for a
     * dictionary [declaredDictionaries] lists ([RimeLearningException.Kind.IDENTITY_MISMATCH]
     * otherwise, before anything is written); unlike [merge], the librime version and the user may
     * differ, as they do between front-ends.
     */
    suspend fun mergeSnapshotFiles(
        sharedDataDir: File,
        compiledDir: File,
        userDataDir: File,
        snapshots: List<RimeSnapshotFile>,
        appName: String = RimeEngine.DEFAULT_APP_NAME,
        appVersion: String? = null,
    ) {
        open(sharedDataDir, compiledDir, userDataDir, appName, appVersion).use { engine ->
            engine.mergeSnapshotFiles(snapshots)
        }
    }

    private suspend fun open(
        sharedDataDir: File,
        compiledDir: File,
        userDataDir: File,
        appName: String,
        appVersion: String?,
    ): RimeEngine =
        RimeEngine.createPrebuilt(
            sharedDataDir = sharedDataDir,
            userDataDir = userDataDir,
            appName = appName,
            appVersion = appVersion,
            compiledDir = compiledDir,
        )
}

private const val MAXIMUM_SCHEMAS = 64
private const val MAXIMUM_TRANSLATORS = 128
private const val STAGING_DIRECTORY = "learning-backup-work"

/** librime's version string; learning snapshots carry it and must match it. */
private fun RimeEngine.engineVersionOnEngine(): String {
    val version = RimeNative.nativeEngineVersion(engine)
    if (version.isNullOrEmpty() || version != RimeEngine.EXPECTED_RIME_VERSION) unsupported("engine version")
    return version
}

/**
 * The user dictionaries the deployed input methods (and their dependencies) learn into: every
 * script or table translator with a user dictionary enabled, named by `user_dict` or else by its
 * dictionary. A schema that cannot be read fails closed.
 */
internal fun RimeEngine.declaredUserDictionariesOnEngine(): Set<String> {
    val listed =
        RimeNative
            .nativeSchemaList()
            ?.toList()
            ?.chunked(2)
            ?.mapNotNull { it.getOrNull(0)?.ifEmpty { null } }
            ?: unsupported("schema list unavailable")
    if (listed.isEmpty() || listed.size > MAXIMUM_SCHEMAS) unsupported("schema list size")
    val pending = ArrayDeque(listed)
    val seen = HashSet<String>()
    val names = HashSet<String>()
    while (pending.isNotEmpty()) {
        val id = pending.removeLast()
        if (!seen.add(id)) continue
        if (seen.size > MAXIMUM_SCHEMAS) unsupported("too many schemas")
        readSchemaValues(id, listOf("schema/schema_id")) ?: unsupported("schema unreadable")
        pending.addAll(readSchemaStringList(id, "schema/dependencies").orEmpty())
        val translators = readSchemaStringList(id, "engine/translators").orEmpty()
        if (translators.size > MAXIMUM_TRANSLATORS) unsupported("too many translators")
        for (translator in translators) {
            val pieces = translator.split('@')
            val component = pieces.first()
            if (component != "script_translator" && component != "table_translator") continue
            if (pieces.size > 2 || pieces.last().isEmpty()) unsupported("translator declaration")
            val namespace = if (pieces.size == 2) pieces[1] else "translator"
            val keys = listOf("enable_user_dict", "db_class", "user_dict", "dictionary").map { "$namespace/$it" }
            val values = readSchemaValues(id, keys) ?: unsupported("schema unreadable")
            if (values["$namespace/enable_user_dict"] == "false") continue
            val dbClass = values["$namespace/db_class"]
            if (dbClass != null && dbClass != "userdb") continue
            val name =
                values["$namespace/user_dict"]
                    ?: values["$namespace/dictionary"]?.substringBefore('.')
                    ?: unsupported("translator without dictionary")
            try {
                RimeUserDbSnapshot.validateName(name)
            } catch (_: RimeLearningException) {
                unsupported("declared dictionary name")
            }
            names += name
        }
    }
    if (names.size > RimeUserDbSnapshot.MAXIMUM_COUNT) unsupported("too many user dictionaries")
    return names
}

private fun RimeEngine.prepareLevers(): File {
    releaseSession()
    if (!RimeNative.nativeLeversAvailable()) unsupported("levers unavailable")
    if (!RimeNative.nativeLeversPrepareInstallation()) backupFailed("installation not prepared")
    val sync = RimeNative.nativeUserDataSyncDir()?.let(::File) ?: backupFailed("no sync directory")
    if (!sync.isAbsolute || !(sync.isDirectory || sync.mkdirs())) backupFailed("sync directory")
    return sync
}

internal fun RimeEngine.exportLearningOnEngine(): RimeLearningBackup.Export {
    requireEngine()
    val version = engineVersionOnEngine()
    val declared = declaredUserDictionariesOnEngine()
    val sync = prepareLevers()
    val onDisk =
        RimeNative
            .nativeUserDictList()
            ?.filterNotNull()
            ?.filter { it in declared }
            ?.sorted()
            ?: backupFailed("user dictionaries unlisted")
    var total = 0L
    val snapshots =
        onDisk.map { name ->
            if (!RimeNative.nativeBackupUserDict(name)) backupFailed("export refused")
            val file = File(sync, name + RimeEngine.USERDB_SNAPSHOT_EXTENSION)
            val size = file.length()
            total += size
            if (!file.isFile ||
                size <= 0 ||
                size > RimeUserDbSnapshot.MAXIMUM_BYTES ||
                total > RimeUserDbSnapshot.MAXIMUM_TOTAL_BYTES
            ) {
                backupFailed("exported snapshot size")
            }
            val snapshot = RimeLearningSnapshot(name, file.readBytes())
            val document =
                try {
                    RimeUserDbSnapshot.parse(snapshot)
                } catch (_: RimeLearningException) {
                    backupFailed("exported snapshot unreadable")
                }
            if (document.rimeVersion != version) backupFailed("exported snapshot version")
            snapshot
        }
    return RimeLearningBackup.Export(version, snapshots)
}

internal fun RimeEngine.mergeLearningOnEngine(
    snapshots: List<RimeLearningSnapshot>,
    documents: List<RimeUserDbDocument>,
) {
    requireEngine()
    val version = engineVersionOnEngine()
    val declared = declaredUserDictionariesOnEngine()
    for (document in documents) {
        if (document.name !in declared || document.rimeVersion != version) {
            throw RimeLearningException(
                RimeLearningException.Kind.IDENTITY_MISMATCH,
                "snapshot not for this input method",
            )
        }
    }
    prepareLevers()
    val staging = File(configuration.userDataDir, STAGING_DIRECTORY)
    staging.deleteRecursively()
    if (!staging.mkdirs()) backupFailed("staging directory")
    try {
        val staged =
            snapshots.withIndex().filter { documents[it.index].entryCount > 0 }.map { (index, snapshot) ->
                File(
                    staging,
                    "incoming-$index" + RimeEngine.USERDB_SNAPSHOT_EXTENSION,
                ).also { it.writeBytes(snapshot.data) }
            }
        for (file in staged) {
            if (!RimeNative.nativeRestoreUserDict(file.path)) {
                throw RimeLearningException(RimeLearningException.Kind.MERGE_UNVERIFIED, "restore refused")
            }
        }
    } finally {
        staging.deleteRecursively()
    }
}

internal fun RimeEngine.mergeSnapshotFilesOnEngine(snapshots: List<RimeSnapshotFile>) {
    requireEngine()
    val declared = declaredUserDictionariesOnEngine()
    if (snapshots.any { it.dictionary !in declared }) {
        throw RimeLearningException(RimeLearningException.Kind.IDENTITY_MISMATCH, "snapshot not for this input method")
    }
    prepareLevers()
    for (snapshot in snapshots) {
        if (snapshot.entryCount == 0) continue
        if (!RimeNative.nativeRestoreUserDict(snapshot.file.path)) {
            throw RimeLearningException(RimeLearningException.Kind.MERGE_UNVERIFIED, "restore refused")
        }
    }
}

private fun unsupported(detail: String): Nothing =
    throw RimeLearningException(RimeLearningException.Kind.UNSUPPORTED, detail)

private fun backupFailed(detail: String): Nothing =
    throw RimeLearningException(RimeLearningException.Kind.BACKUP_FAILED, detail)
