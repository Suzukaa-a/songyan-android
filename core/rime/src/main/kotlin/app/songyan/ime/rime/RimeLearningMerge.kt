package app.songyan.ime.rime

import java.io.File

/**
 * Merges the learning data of one user directory into another with librime's userdb export and
 * import (levers `backup_user_dict` / `restore_user_dict`).
 *
 * Two LevelDB userdbs cannot be combined file by file, so the source directory's dictionaries are
 * exported as text snapshots by an engine opened on it, and an engine opened on the target merges
 * them. Both engines open the same immutable prebuilt build and run one after the other: librime
 * allows one engine per process, so the caller must not hold another engine meanwhile.
 *
 * The source directory is only read (librime may refresh its metadata and writes the snapshots
 * into its own `sync/`); deleting it is the caller's decision once this returned true.
 */
object RimeLearningMerge {
    /**
     * True when every user dictionary found in [from] was exported and merged into [into]. False
     * leaves [from] usable for another attempt; [into] may then hold part of the merge, which a
     * repeated merge completes without counting anything twice.
     */
    suspend fun merge(
        sharedDataDir: File,
        compiledDir: File,
        from: File,
        into: File,
        appName: String = RimeEngine.DEFAULT_APP_NAME,
        appVersion: String? = null,
    ): Boolean {
        val onDisk = userDictionariesOnDisk(from)
        if (onDisk.isEmpty()) return true
        val snapshots =
            open(sharedDataDir, compiledDir, from, appName, appVersion).use { engine ->
                engine.exportUserDictSnapshots()
            } ?: return false
        // Every dictionary directory must have been exported: one the engine did not list (or
        // could not read) would otherwise be deleted with its directory.
        val exported = snapshots.map { it.name.removeSuffix(RimeEngine.USERDB_SNAPSHOT_EXTENSION) }.toSet()
        if (!exported.containsAll(onDisk)) return false
        return open(sharedDataDir, compiledDir, into, appName, appVersion).use { engine ->
            engine.mergeUserDictSnapshots(snapshots)
        }
    }

    /** Names of the userdb directories in [directory], without librime's temporary `.temp`. */
    fun userDictionariesOnDisk(directory: File): Set<String> =
        directory
            .listFiles()
            .orEmpty()
            .filter { it.name.endsWith(RimeEngine.USERDB_EXTENSION) && !it.name.startsWith(".") }
            .map { it.name.removeSuffix(RimeEngine.USERDB_EXTENSION) }
            .toSet()

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
