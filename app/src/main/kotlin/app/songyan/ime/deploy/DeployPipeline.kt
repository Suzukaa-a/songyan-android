package app.songyan.ime.deploy

import android.os.SystemClock
import android.util.Log
import app.songyan.ime.content.DictionaryImportProblemException
import app.songyan.ime.content.DictionaryImportProblemKind
import app.songyan.ime.content.DictionaryImportProblems
import app.songyan.ime.content.SchemeImportProblemKind
import app.songyan.ime.content.SchemeImportProblems
import app.songyan.ime.deploy.net.DictionaryLinkFetcher
import app.songyan.ime.learning.PackageLearning
import app.songyan.ime.packages.DictionaryImportError
import app.songyan.ime.packages.DictionarySourceFile
import app.songyan.ime.packages.DictionaryStacking
import app.songyan.ime.packages.RecipeResourceBudget
import app.songyan.ime.packages.SchemePackageImportError
import app.songyan.ime.packages.SchemePackageLimits
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceManifest
import app.songyan.ime.userconfig.UserConfigStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Runs one deploy request to its terminal status in the :deploy process: the import steps
 * (download, check, build the source tree) and the coordinator operation behind them.
 *
 * The incoming directory of a request belongs to this process from the moment the request is
 * queued: whatever happens, it is deleted when the request ends.
 */
class DeployPipeline(
    private val storage: AppStorage,
    private val coordinator: DeploymentCoordinator,
    private val builder: SourceBuilder,
    /** Whether the device has a network; lets the downloader explain a failed lookup. */
    online: () -> Boolean = { true },
    private val fetcher: DictionaryLinkFetcher = DictionaryLinkFetcher(online = online),
    private val schemeArchiveBytes: Long = SchemePackageLimits().archiveBytes,
) {
    private val catalog = SourceCatalog(storage)
    private val packageLearning = PackageLearning(storage)

    /** [progress] hears how much of a scheme package or model file has arrived while it downloads. */
    suspend fun run(
        request: DeployRequest,
        progress: DownloadProgress = DownloadProgress.NONE,
        cancelled: () -> Boolean = { false },
    ): RequestStatus = withContext(DeployCancellation(cancelled)) { runRequest(request, progress, cancelled) }

    private suspend fun runRequest(
        request: DeployRequest,
        progress: DownloadProgress,
        cancelled: () -> Boolean,
    ): RequestStatus {
        val incoming = storage.incomingDir(request.requestId)
        val staged = File(storage.importStagingDir, STAGED_PREFIX + request.requestId)
        return try {
            if (cancelled()) return failed(CANCELLED)
            when (request.mode) {
                DeployMode.BUILTIN ->
                    published(
                        if (request.force) coordinator.deployBuiltin(force = true) else coordinator.ensureDeployed(),
                    )

                DeployMode.REDEPLOY_ACTIVE -> published(coordinator.redeployActive())
                DeployMode.APPLY_USER_CONFIG ->
                    coordinator.applyUserConfig().fold(
                        onSuccess = { RequestStatus(RequestState.SUCCEEDED, sourceId = it.sourceId) },
                        onFailure = { error ->
                            if (error is DeployCancelledException) return@fold failed(CANCELLED)
                            if (error is CancellationException) throw error
                            Log.i(TAG, "user configuration not applied: " + error.message)
                            val failure = UserConfigStatus.failed(error)
                            failed(CONFIG_PREFIX + failure.problem, listOfNotNull(failure.file), failure.detail)
                        },
                    )
                DeployMode.COMPLETE_COMPANIONS ->
                    coordinator.completeCompanions().fold(
                        onSuccess = { RequestStatus(RequestState.SUCCEEDED, sourceId = it?.sourceId) },
                        onFailure = { failure(it) },
                    )
                DeployMode.IMPORT_DICTIONARY ->
                    importDictionary(dictionaryFiles(incoming), staged, request.sourceId, cancelled)
                DeployMode.IMPORT_DICTIONARY_URL -> {
                    val url = request.url ?: return failed(DictionaryImportProblemKind.UNSUPPORTED_URL.name)
                    val files = fetcher.fetch(url, File(incoming, DOWNLOADS), cancelled)
                    importDictionary(files, staged, request.sourceId, cancelled)
                }

                DeployMode.DOWNLOAD_FILE -> {
                    val url = request.url ?: return failed(DictionaryImportProblemKind.UNSUPPORTED_URL.name)
                    val target = storage.downloadFile(request.requestId)
                    try {
                        val limit = RecipeResourceBudget.MODEL_FILE.toLong()
                        fetcher.fetchFile(
                            url,
                            target,
                            request.maxBytes?.coerceAtMost(limit) ?: limit,
                            cancelled,
                            progress,
                        )
                        RequestStatus(RequestState.SUCCEEDED)
                    } catch (e: Throwable) {
                        target.delete()
                        throw e
                    }
                }

                DeployMode.IMPORT_SCHEME ->
                    importScheme(
                        stagedArchives(request, incoming),
                        staged,
                        cancelled,
                        request.requestId,
                        advertisedSchemas = request.advertisedSchemas,
                    )
                DeployMode.IMPORT_SCHEME_URL -> {
                    val update = request.sourceId
                    if (update != null && !(SourceIds.isScheme(update) && catalog.read(update) != null)) {
                        return failed(SchemeImportProblemKind.UPDATE_TARGET_MISSING.name)
                    }
                    if (request.archives.isEmpty()) {
                        val url = request.url ?: return failed(DictionaryImportProblemKind.UNSUPPORTED_URL.name)
                        val archive = File(incoming, PACKAGE_FILE)
                        val size =
                            fetcher.fetchArchive(
                                url,
                                archive,
                                SchemePackageLimits().archiveBytes,
                                cancelled,
                                progress,
                            )
                        // A finished download reports a full total even when the server announced none.
                        progress.update(size, size)
                        val origin = DictionaryLinkFetcher.normalizeArchive(url)?.toString()
                        importScheme(
                            listOf(archive),
                            staged,
                            cancelled,
                            request.requestId,
                            update,
                            origin,
                            request.advertisedSchemas,
                        )
                    } else {
                        val archives = downloadPinnedArchives(request.archives, incoming, cancelled, progress)
                        val origin = DictionaryLinkFetcher.normalizeArchive(request.archives.first().url)?.toString()
                        importScheme(
                            archives,
                            staged,
                            cancelled,
                            request.requestId,
                            update,
                            origin,
                            request.advertisedSchemas,
                        )
                    }
                }
                DeployMode.ACTIVATE_SOURCE -> published(coordinator.activateSource(request.sourceId.orEmpty()))
                DeployMode.DELETE_SOURCE ->
                    coordinator.deleteSource(request.sourceId.orEmpty()).fold(
                        onSuccess = { RequestStatus(RequestState.SUCCEEDED, sourceId = it?.sourceId) },
                        onFailure = { failure(it) },
                    )

                DeployMode.RESTORE_SOURCE ->
                    coordinator.restoreSource(request.sourceId.orEmpty()).fold(
                        onSuccess = { RequestStatus(RequestState.SUCCEEDED, sourceId = request.sourceId) },
                        onFailure = { failure(it) },
                    )

                DeployMode.REMOVE_DICTIONARY ->
                    coordinator.removeStackedDictionary(request.sourceId.orEmpty()).fold(
                        onSuccess = { RequestStatus(RequestState.SUCCEEDED, sourceId = it?.sourceId) },
                        onFailure = { failure(it, DictionaryImportProblemKind.REMOVE_FAILED.name) },
                    )
            }
        } catch (e: DeployCancelledException) {
            failed(CANCELLED)
        } catch (e: CancellationException) {
            throw e
        } catch (e: DictionaryImportProblemException) {
            failed(e.kind.name, listOfNotNull(e.detail))
        } catch (e: DictionaryImportError) {
            Log.i(TAG, "dictionary rejected: " + e.message)
            failed(DictionaryImportProblems.kindOf(e.reason).name, e.statusPaths())
        } catch (e: DictionaryStacking.StackingError) {
            Log.i(TAG, "dictionary cannot be stacked: " + e.message)
            failed(stackingProblem(request.mode))
        } catch (e: SchemePackageImportError) {
            Log.i(TAG, "scheme package rejected: " + e.message)
            failed(SchemeImportProblems.kindOf(e.reason).name, e.paths)
        } catch (e: InterruptedException) {
            failed(CANCELLED)
        } catch (e: Exception) {
            Log.e(TAG, "request " + request.mode.wireName + " failed", e)
            failed(stagingProblem(request.mode))
        } finally {
            incoming.deleteRecursively()
            staged.deleteRecursively()
            packageLearning.stagingDir(request.requestId).deleteRecursively()
        }
    }

    /**
     * [target] null (or the built-in sources) layers the dictionary over the built-in input
     * methods as the dictionary source; a scheme package id stacks it onto that package.
     */
    private suspend fun importDictionary(
        files: List<DictionarySourceFile>,
        staged: File,
        target: String?,
        cancelled: () -> Boolean,
    ): RequestStatus {
        if (files.isEmpty()) return failed(DictionaryImportProblemKind.READ_FAILED.name)
        if (target != null && SourceIds.isScheme(target)) {
            val stack = builder.buildStack(files, staged, cancelled)
            if (cancelled()) return failed(CANCELLED)
            return published(
                coordinator.stackDictionary(target, stack),
                DictionaryImportProblemKind.DEPLOY_FAILED.name,
                DeployMode.IMPORT_DICTIONARY,
            )
        }
        val built = builder.buildDictionary(files, staged, cancelled)
        if (cancelled()) return failed(CANCELLED)
        return published(coordinator.installSource(built), DictionaryImportProblemKind.DEPLOY_FAILED.name)
    }

    /**
     * [archives] are the ZIPs of one package, the entry first: a picked file, or the pinned archives
     * of a recommended entry (already downloaded and checked by the caller). [update] names the
     * stored package this is a new version of; [origin] is the link it was downloaded from. A file
     * import of a package that is already stored keeps its link.
     */
    private suspend fun importScheme(
        archives: List<File>,
        staged: File,
        cancelled: () -> Boolean,
        requestId: String,
        update: String? = null,
        origin: String? = null,
        advertisedSchemas: List<String> = emptyList(),
    ): RequestStatus {
        if (archives.isEmpty() || archives.any { !it.isFile }) return failed(SchemeImportProblemKind.READ_FAILED.name)
        val checkStart = SystemClock.elapsedRealtime()
        val prepared =
            builder.buildScheme(
                archives,
                staged,
                cancelled,
                sourceId = update,
                origin = origin,
                advertisedSchemas = advertisedSchemas,
            )
        Log.i(DeployTimer.TAG, "scheme package checked in " + (SystemClock.elapsedRealtime() - checkStart) + " ms")
        // Learned-word snapshots only wait for the user's answer; the import never depends on them.
        val snapshots = packageLearning.stage(archives.first(), requestId)
        // The archives are no longer needed; free the space before the deploy copies the tree.
        archives.forEach { it.delete() }
        val status =
            try {
                if (cancelled()) failed(CANCELLED) else installScheme(prepared, cancelled, update)
            } catch (e: Throwable) {
                snapshots?.deleteRecursively()
                throw e
            }
        if (snapshots != null) {
            val sourceId = status.sourceId
            if (status.state == RequestState.SUCCEEDED && status.problem != UP_TO_DATE && sourceId != null) {
                packageLearning.keep(snapshots, sourceId)
            } else {
                snapshots.deleteRecursively()
            }
        }
        packageLearning.sweep((storedPackages().map { it.sourceId } + SourceIds.BUILTIN).toSet())
        return status
    }

    private suspend fun installScheme(
        prepared: BuiltSource,
        cancelled: () -> Boolean,
        update: String?,
    ): RequestStatus {
        // A new version of a stored package, picked as a file or a new link: it takes the stored
        // package's place instead of being stored beside it.
        val replaced =
            if (update == null && catalog.read(prepared.manifest.sourceId) == null) {
                PackageUpdate.replaced(prepared.manifest, storedPackages(), coordinator.activeGeneration()?.sourceId)
            } else {
                null
            }
        // A package deleted moments ago comes back first, so its stacked dictionary stays with it.
        if (replaced != null && catalog.read(replaced.sourceId) == null) {
            coordinator.restoreSource(replaced.sourceId).getOrThrow()
        }
        val targetId = update ?: replaced?.sourceId ?: prepared.manifest.sourceId
        val stored = catalog.read(targetId)
        val carried =
            if (stored != null) {
                PackageUpdate.carryGrammarModels(storage.sourceDir(targetId), prepared.directory)
            } else {
                emptyList()
            }
        val built =
            BuiltSource(
                prepared.directory,
                prepared.manifest.copy(
                    sourceId = targetId,
                    origin = prepared.manifest.origin ?: stored?.origin,
                    revision =
                        PackageUpdate.revisionWithCarried(
                            prepared.manifest.revision,
                            prepared.directory,
                            carried,
                        ),
                ),
            )
        if (carried.isNotEmpty()) Log.i(TAG, "kept the grammar models " + carried + " of " + targetId)
        if (replaced != null) {
            return published(
                coordinator.installSource(built),
                SchemeImportProblemKind.DEPLOY_FAILED.name,
                DeployMode.IMPORT_SCHEME,
            ).let { if (it.state == RequestState.SUCCEEDED) it.copy(problem = REPLACED) else it }
        }
        if (update != null) {
            if (stored?.revision == built.manifest.revision && stored.origin == built.manifest.origin) {
                return RequestStatus(RequestState.SUCCEEDED, problem = UP_TO_DATE, sourceId = update)
            }
            return published(
                coordinator.updateSource(built),
                SchemeImportProblemKind.DEPLOY_FAILED.name,
                DeployMode.IMPORT_SCHEME,
            ).let { it.copy(sourceId = update) }
        }
        return published(
            coordinator.installSource(built),
            SchemeImportProblemKind.DEPLOY_FAILED.name,
            DeployMode.IMPORT_SCHEME,
        )
    }

    /** Every stored scheme package, and the one deleted last while it can still be restored. */
    private fun storedPackages(): List<SourceManifest> {
        val trashed =
            storage.trashDir.listFiles().orEmpty().firstNotNullOfOrNull { dir ->
                val file = File(File(dir, DeploymentCoordinator.TRASH_TREE), SourceCatalog.MANIFEST)
                if (!file.isFile || file.length() > MAX_MANIFEST_BYTES) return@firstNotNullOfOrNull null
                runCatching { SourceManifest.parse(file.readText()) }.getOrNull()?.takeIf { it.sourceId == dir.name }
            }
        return catalog.list() + listOfNotNull(trashed)
    }

    /**
     * Downloads the pinned archives of a recommended entry, entry first. Every archive must match
     * the digest the catalogue names: a different byte is refused here, so a package can never mix
     * in another upstream's file. [progress] counts the archives as one download, because that is
     * what the user is waiting for.
     */
    private fun downloadPinnedArchives(
        archives: List<PinnedArchive>,
        incoming: File,
        cancelled: () -> Boolean,
        progress: DownloadProgress,
    ): List<File> {
        val total = archives.sumOf { it.bytes }.takeIf { it > 0 }
        val limit = schemeArchiveBytes
        var offset = 0L
        return archives.mapIndexed { index, archive ->
            val target = File(incoming, PINNED_PREFIX + index + ".zip")
            val size =
                fetcher.fetchArchive(
                    archive.url,
                    target,
                    limit - offset,
                    cancelled,
                    DownloadProgress { bytes, _ -> progress.update(offset + bytes, total) },
                    archive.sha256,
                )
            offset += size
            progress.update(offset, total)
            target
        }
    }

    /**
     * The staged ZIPs of an import, entry first: the picked file names the settings process wrote,
     * or the one fixed name a single-file import uses.
     */
    private fun stagedArchives(
        request: DeployRequest,
        incoming: File,
    ): List<File> = request.packageFiles.ifEmpty { listOf(PACKAGE_FILE) }.map { File(incoming, it) }

    /** The picked files, named by the path the settings process gave them under `files/`. */
    private fun dictionaryFiles(incoming: File): List<DictionarySourceFile> {
        val root = File(incoming, FILES)
        return root
            .walkTopDown()
            .filter { it.isFile }
            .map { DictionarySourceFile(it.relativeTo(root).invariantSeparatorsPath, it) }
            .sortedBy { it.path }
            .toList()
    }

    private fun published(
        result: Result<PublicationRecord>,
        problem: String = DEPLOY_FAILED,
        mode: DeployMode? = null,
    ): RequestStatus =
        result.fold(
            onSuccess = { RequestStatus(RequestState.SUCCEEDED, sourceId = it.sourceId) },
            onFailure = { failure(it, problem, mode) },
        )

    private fun failure(
        error: Throwable,
        problem: String = DEPLOY_FAILED,
        mode: DeployMode? = null,
    ): RequestStatus {
        if (error is DeployCancelledException) return failed(CANCELLED)
        if (error is CancellationException) throw error
        Log.e(TAG, "deploy failed: " + error.message, error)
        return when (error) {
            is DictionaryImportError -> failed(DictionaryImportProblems.kindOf(error.reason).name, error.statusPaths())
            is DictionaryStacking.StackingError -> failed(mode?.let(::stackingProblem) ?: problem)
            is KeyboardCompanionException ->
                failed(
                    if (mode ==
                        DeployMode.IMPORT_SCHEME
                    ) {
                        SchemeImportProblemKind.KEYBOARD_COMPANION.name
                    } else {
                        problem
                    },
                )
            else -> failed(problem)
        }
    }

    /** A dictionary that cannot be stacked: refused on import, or blocking a package update. */
    private fun stackingProblem(mode: DeployMode): String =
        when (mode) {
            DeployMode.IMPORT_SCHEME -> SchemeImportProblemKind.DICTIONARY_STACK.name
            else -> DictionaryImportProblemKind.TARGET_UNSUPPORTED.name
        }

    /** Row errors carry their `path:line` detail through the status so the copy can name the line. */
    private fun DictionaryImportError.statusPaths(): List<String> =
        if (DictionaryImportProblems.namesLine(DictionaryImportProblems.kindOf(reason))) {
            listOfNotNull(detail)
        } else {
            paths
        }

    private fun failed(
        problem: String,
        paths: List<String> = emptyList(),
        detail: String? = null,
    ) = RequestStatus(RequestState.FAILED, problem = problem, paths = paths, detail = detail)

    private fun stagingProblem(mode: DeployMode): String =
        when (mode) {
            DeployMode.IMPORT_DICTIONARY,
            DeployMode.IMPORT_DICTIONARY_URL,
            -> DictionaryImportProblemKind.STAGING_FAILED.name
            DeployMode.IMPORT_SCHEME,
            DeployMode.IMPORT_SCHEME_URL,
            -> SchemeImportProblemKind.STAGING_FAILED.name
            else -> DEPLOY_FAILED
        }

    companion object {
        private const val TAG = "DeployPipeline"

        /** Generic failure of a request without an import (rebuild, activate, delete). */
        const val DEPLOY_FAILED = "DEPLOY_FAILED"
        const val CANCELLED = "CANCELLED"

        /** Recorded with a successful update that found nothing new; nothing was installed. */
        const val UP_TO_DATE = "UP_TO_DATE"

        /** Prefix of the problems of [DeployMode.APPLY_USER_CONFIG]: a [app.songyan.ime.userconfig.UserConfigProblem] name or the deploy failure. */
        const val CONFIG_PREFIX = "CONFIG_"

        /** Recorded with a successful import that became the new version of a stored package. */
        const val REPLACED = "REPLACED"
        private const val MAX_MANIFEST_BYTES = 256L * 1024

        /** Under a request's incoming directory: picked dictionary files. */
        const val FILES = "files"

        /** Under a request's incoming directory: the picked scheme package. */
        const val PACKAGE_FILE = "package.zip"

        /** Under a request's incoming directory: the downloaded archives of a recommended entry. */
        private const val PINNED_PREFIX = "pinned-"
        private const val DOWNLOADS = "downloads"
        const val STAGED_PREFIX = "staged-"
    }
}

/** Request cancellation waits for native work to finish before the publication boundary. */
internal class DeployCancellation(
    private val cancelled: () -> Boolean,
) : AbstractCoroutineContextElement(Key) {
    val isCancelled: Boolean get() = cancelled()

    fun check() {
        if (cancelled()) throw DeployCancelledException()
    }

    companion object Key : CoroutineContext.Key<DeployCancellation>
}

internal class DeployCancelledException : CancellationException("deployment cancelled")
