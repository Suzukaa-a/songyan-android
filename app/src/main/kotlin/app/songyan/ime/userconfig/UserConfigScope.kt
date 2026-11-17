package app.songyan.ime.userconfig

import android.util.Log
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import java.io.File
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * Which source's configuration scope ([AppStorage.configScopeDir]) the settings screen and the Files
 * app edit, and applying deploys: the source in use, or the built-in one when nothing usable is in
 * use. Kept identical to what [app.songyan.ime.deploy.DeploymentCoordinator.applyUserConfig]
 * rebuilds, so the files the user edits are the ones that source gets. Reads the disk.
 */
object UserConfigScope {
    fun active(storage: AppStorage): String {
        val active = GenerationPublisher(storage).readActive()?.sourceId
        return active?.takeIf { it == SourceIds.BUILTIN || SourceCatalog(storage).read(it) != null }
            ?: SourceIds.BUILTIN
    }
}

/**
 * Moves the single configuration folder of earlier versions (and of backups made by them) into the
 * per-source scopes without changing what any source deploys with: every source received that folder,
 * so each gets a copy: every stored source, the built-in one, and a source in the trash (it can be
 * restored). A file already in a scope is the user's newer work and is never overwritten. Each copy
 * is a temporary file (or folder, for the applied copy, which only counts as a whole) renamed into
 * place, and the old folder goes only after every copy succeeded, so a run that dies half-way is
 * simply run again. Runs under a cross-process lock: the settings process, the provider and the
 * deploy process all reach the configuration. Reads and writes the disk: never on the main thread.
 */
object UserConfigMigration {
    private const val TAG = "UserConfigMigration"
    private const val COPY_PREFIX = ".copy-migrate-"
    private const val APPLIED_TEMP_PREFIX = ".applied-migrate-"
    private val LEGACY_PREVIOUS = Regex("^\\.previous-[0-9]+$")
    private val LEGACY_STAGING = Regex("^\\.staging-[0-9]+$")
    private val LOCK = Any()

    /** Migrates when there is anything left from the single folder; true when nothing is left. */
    fun run(storage: AppStorage): Boolean {
        if (!pending(storage)) return true
        return try {
            synchronized(LOCK) {
                storage.configRoot.mkdirs()
                FileChannel
                    .open(storage.configMigrationLockFile.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
                    .use { channel ->
                        channel.lock().use {
                            if (pending(storage)) migrate(storage)
                        }
                    }
            }
            true
        } catch (e: IOException) {
            // The old folder stays; the next use tries again.
            Log.w(TAG, "cannot move the configuration into per-source folders yet", e)
            false
        }
    }

    private fun legacyPaths(storage: AppStorage): List<File> =
        listOf(
            storage.legacyUserConfigDir,
            storage.legacyAppliedConfigDir,
            storage.legacyOverrideMarksFile,
            storage.legacyConfigStatusFile,
        )

    private fun pending(storage: AppStorage): Boolean =
        legacyPaths(storage).any { it.exists() } ||
            storage.configRoot
                .list()
                .orEmpty()
                .any { LEGACY_PREVIOUS.matches(it) || LEGACY_STAGING.matches(it) }

    private fun migrate(storage: AppStorage) {
        finishLegacyApplication(storage)
        for (sourceId in receivingSources(storage)) copyInto(storage, sourceId)
        // Only now: until every scope has its copy, the old folder is what a second run copies from.
        for (path in legacyPaths(storage)) {
            if (path.exists() && !path.deleteRecursively()) throw IOException("cannot remove $path")
        }
    }

    /**
     * An application an earlier version left unfinished, settled the way that version would have:
     * the new copy stays only when the generation in use was published with it.
     */
    private fun finishLegacyApplication(storage: AppStorage) {
        val entries = storage.configRoot.listFiles().orEmpty()
        for (entry in entries.filter { LEGACY_STAGING.matches(it.name) }) entry.deleteRecursively()
        val previous = entries.filter { LEGACY_PREVIOUS.matches(it.name) }
        val newest = previous.maxByOrNull { it.name.removePrefix(".previous-").toLongOrNull() ?: -1L } ?: return
        val applied = storage.legacyAppliedConfigDir
        val digest = UserConfigFiles.digest(applied)
        val committed =
            applied.isDirectory && GenerationPublisher(storage).usablePublication()?.configDigest == (digest ?: "")
        if (!committed) {
            if (applied.exists() && !applied.deleteRecursively()) throw IOException("cannot remove $applied")
            Files.move(newest.toPath(), applied.toPath(), StandardCopyOption.ATOMIC_MOVE)
        }
        for (entry in previous) entry.deleteRecursively()
    }

    /** Every source that deployed with the single folder until now. */
    private fun receivingSources(storage: AppStorage): Set<String> {
        val ids = linkedSetOf(SourceIds.BUILTIN)
        for (root in listOf(storage.sourcesDir, storage.trashDir)) {
            val dirs = root.listFiles() ?: if (root.exists()) throw IOException("cannot list $root") else emptyArray()
            for (dir in dirs) if (dir.isDirectory && SourceIds.isValid(dir.name)) ids += dir.name
        }
        return ids
    }

    private fun copyInto(
        storage: AppStorage,
        sourceId: String,
    ) {
        val scope = storage.configScopeDir(sourceId)
        scope
            .listFiles()
            .orEmpty()
            .filter {
                it.name.startsWith(
                    APPLIED_TEMP_PREFIX,
                )
            }.forEach { it.deleteRecursively() }
        val edit = storage.legacyUserConfigDir
        if (edit.isDirectory) {
            val target = storage.userConfigDir(sourceId)
            for (file in regularFiles(edit)) copyFile(file, File(target, file.relativeTo(edit).path))
        }
        val applied = storage.legacyAppliedConfigDir
        val appliedTarget = storage.appliedConfigDir(sourceId)
        if (applied.isDirectory && !appliedTarget.exists()) {
            val temp = File(scope, APPLIED_TEMP_PREFIX + System.nanoTime())
            try {
                for (file in regularFiles(applied)) copyFile(file, File(temp, file.relativeTo(applied).path))
                temp.mkdirs()
                Files.move(temp.toPath(), appliedTarget.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                temp.deleteRecursively()
            }
        }
        if (storage.legacyOverrideMarksFile.isFile) {
            copyFile(storage.legacyOverrideMarksFile, storage.overrideMarksFile(sourceId))
        }
        if (storage.legacyConfigStatusFile.isFile) {
            copyFile(storage.legacyConfigStatusFile, storage.configStatusFile(sourceId))
        }
    }

    /** Every file below [dir], except links and the temporary files of interrupted writes. */
    private fun regularFiles(dir: File): List<File> =
        dir
            .walkTopDown()
            .onEnter { !Files.isSymbolicLink(it.toPath()) }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) }
            .filterNot { it.name.startsWith(WriteTemps.PREFIX) || it.name.startsWith(".copy-") }
            .toList()

    /** Copies [from] to [to] unless [to] exists; a reader sees either nothing or the whole file. */
    private fun copyFile(
        from: File,
        to: File,
    ) {
        if (to.exists()) return
        val parent = to.parentFile ?: throw IOException("no folder for $to")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create $parent")
        val temp = File(parent, COPY_PREFIX + System.nanoTime())
        try {
            Files.copy(from.toPath(), temp.toPath(), StandardCopyOption.REPLACE_EXISTING)
            temp.setLastModified(from.lastModified())
            Files.move(temp.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
        } finally {
            temp.delete()
        }
    }
}
