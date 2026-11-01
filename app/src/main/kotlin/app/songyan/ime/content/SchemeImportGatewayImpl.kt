package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import app.songyan.ime.R
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployPipeline
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.learning.LearningBackupService
import app.songyan.ime.learning.PackageLearning
import app.songyan.ime.learning.PackageLearningMerge
import app.songyan.ime.learning.PackageLearningOutcome
import app.songyan.ime.packages.SchemeGrammarRequirements
import app.songyan.ime.packages.SchemePackageLimits
import app.songyan.ime.settings.state.ImportedSchemeInfo
import app.songyan.ime.settings.state.PackageLearningResult
import app.songyan.ime.settings.state.SchemeImportGateway
import app.songyan.ime.settings.state.SchemeImportResult
import app.songyan.ime.settings.state.TransferProgress
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceCatalog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The settings side of a scheme package import: stream the picked ZIP to the request's incoming
 * directory and let the :deploy process check, extract and publish it as its own source. The
 * active input method follows active.json afterwards; this gateway never writes it.
 */
internal class SchemeImportGatewayImpl(
    context: Context,
    /**
     * Passed in rather than derived here: resolving Context.filesDir touches the file system, and
     * the gateway is constructed from Application.onCreate on the main thread.
     */
    private val storage: AppStorage,
    private val client: ImportDeployClient = ImportDeployClient(context.applicationContext, storage),
    /** The learning service of the input method process; resolved lazily because it builds an engine binding. */
    private val learningService: () -> LearningBackupService = { error("no learning service") },
) : SchemeImportGateway {
    private val appContext = context.applicationContext
    private val packageLearning = PackageLearning(storage)

    override val available: Boolean = true

    override suspend fun importFromUri(uri: Uri): SchemeImportResult =
        stagedImport { target -> copyPackage(uri, target) }

    override suspend fun importFromTree(uri: Uri): SchemeImportResult =
        stagedImport { target -> zipFolder(uri, target) }

    /** The download runs in :deploy; this process only hands the link over. */
    override suspend fun importFromLink(
        url: String,
        progress: (TransferProgress) -> Unit,
    ): SchemeImportResult =
        client.detached {
            val request = DeployRequest(DeployContract.newRequestId(), DeployMode.IMPORT_SCHEME_URL, url = url)
            finish(client.await(request, progress))
        }

    override suspend fun update(
        sourceId: String,
        progress: (TransferProgress) -> Unit,
    ): SchemeImportResult =
        client.detached {
            val origin =
                withContext(Dispatchers.IO) { SourceCatalog(storage).read(sourceId)?.origin }
                    ?: return@detached failure(SchemeImportProblemKind.UPDATE_TARGET_MISSING)
            val request =
                DeployRequest(
                    DeployContract.newRequestId(),
                    DeployMode.IMPORT_SCHEME_URL,
                    sourceId = sourceId,
                    url = origin,
                )
            finish(client.await(request, progress))
        }

    /** Copies the package into the request's incoming directory with [copy], then deploys it. */
    private suspend fun stagedImport(copy: (File) -> Unit): SchemeImportResult =
        client.detached {
            val requestId = DeployContract.newRequestId()
            val incoming = storage.incomingDir(requestId)
            try {
                copy(File(incoming, DeployPipeline.PACKAGE_FILE))
            } catch (e: CancellationException) {
                incoming.deleteRecursively()
                throw e
            } catch (e: SchemeImportProblemException) {
                incoming.deleteRecursively()
                return@detached failure(e.kind)
            } catch (e: Exception) {
                Log.w(TAG, "cannot read the scheme package", e)
                incoming.deleteRecursively()
                return@detached failure(SchemeImportProblemKind.READ_FAILED)
            }
            // From here on the incoming directory belongs to the deploy process.
            finish(client.await(DeployRequest(requestId, DeployMode.IMPORT_SCHEME)))
        }

    private suspend fun finish(outcome: DeployWait): SchemeImportResult =
        when (outcome) {
            is DeployWait.Finished -> {
                val status = outcome.status
                if (status.state == RequestState.SUCCEEDED) {
                    val manifest =
                        status.sourceId?.let { id ->
                            withContext(Dispatchers.IO) { SourceCatalog(storage).read(id) }
                        }
                    val inUse =
                        manifest != null &&
                            withContext(Dispatchers.IO) { GenerationPublisher(storage).readActive()?.sourceId } ==
                            manifest.sourceId
                    if (status.problem == DeployPipeline.UP_TO_DATE) {
                        SchemeImportResult.UpToDate(
                            manifest?.let { ImportedSchemeInfo(it.displayName, it.schemaIds, inUse, it.sourceId) },
                        )
                    } else {
                        val waiting =
                            manifest
                                ?.let {
                                    withContext(Dispatchers.IO) {
                                        packageLearning.pending(it.sourceId).map { snapshot ->
                                            snapshot.snapshot.dictionary
                                        }
                                    }
                                }.orEmpty()
                        val missingModels =
                            manifest
                                ?.let {
                                    withContext(Dispatchers.IO) {
                                        SchemeGrammarRequirements.missingModels(storage.sourceDir(it.sourceId))
                                    }
                                }.orEmpty()
                        val info =
                            manifest?.let {
                                ImportedSchemeInfo(
                                    it.displayName,
                                    it.schemaIds,
                                    inUse,
                                    it.sourceId,
                                    waiting.toSet().size,
                                    missingModels,
                                )
                            }
                        SchemeImportResult.Success(info, updated = status.problem == DeployPipeline.REPLACED)
                    }
                } else {
                    failureOf(status.problem, status.paths)
                }
            }

            DeployWait.TimedOut -> failure(SchemeImportProblemKind.DEPLOY_TIMEOUT)
            DeployWait.Unavailable -> failure(SchemeImportProblemKind.DEPLOY_UNAVAILABLE)
        }

    /** A scheme problem, or one of the download problems a link import can end with. */
    private fun failureOf(
        problem: String?,
        paths: List<String>,
    ): SchemeImportResult {
        SchemeImportProblems.kindNamed(problem)?.let { return failure(it, paths) }
        val download = DictionaryImportProblems.kindNamed(problem)
        if (download == null) return failure(SchemeImportProblemKind.DEPLOY_FAILED, paths)
        return SchemeImportResult.Failure(
            DictionaryImportMessages.format(appContext, download, paths),
            retryable = DictionaryImportProblems.retryable(download),
        )
    }

    private fun zipFolder(
        uri: Uri,
        target: File,
    ) {
        val root =
            DocumentFile.fromTreeUri(appContext, uri)?.takeIf { it.isDirectory }
                ?: throw SchemeImportProblemException(SchemeImportProblemKind.READ_FAILED)
        FolderPackage.zip(
            root = DocumentNode(root) { appContext.contentResolver.openInputStream(it.uri) },
            target = target,
            byteLimit = SchemePackageLimits().archiveBytes,
        )
    }

    /** Streams the picked document to [target], bounded by the archive cap. */
    private fun copyPackage(
        uri: Uri,
        target: File,
    ) {
        val limit = SchemePackageLimits().archiveBytes
        if (reportedSize(uri) > limit) throw SchemeImportProblemException(SchemeImportProblemKind.FILE_TOO_LARGE)
        target.parentFile?.mkdirs()
        val input =
            appContext.contentResolver.openInputStream(uri)
                ?: throw SchemeImportProblemException(SchemeImportProblemKind.READ_FAILED)
        input.use { stream ->
            FileOutputStream(target).use { output ->
                try {
                    DictionaryImportFiles.copyCapped(stream, output, limit)
                } catch (e: DictionaryImportProblemException) {
                    throw SchemeImportProblemException(SchemeImportProblemKind.FILE_TOO_LARGE, e)
                }
            }
        }
    }

    /** The size the provider reports, or -1 when it does not know; only a fast-path rejection. */
    private fun reportedSize(uri: Uri): Long =
        runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else -1L }
        }.getOrNull() ?: -1L

    private fun failure(
        kind: SchemeImportProblemKind,
        paths: List<String> = emptyList(),
    ): SchemeImportResult {
        val res = SchemeImportProblems.messageRes(kind)
        val message =
            when (kind) {
                SchemeImportProblemKind.FILE_TOO_LARGE ->
                    appContext.getString(
                        res,
                        SchemeImportProblems.SIZE_LIMIT_LABEL,
                    )
                SchemeImportProblemKind.MISSING_DEPENDENCY ->
                    appContext.getString(
                        res,
                        ImportPaths.list(paths, appContext.getString(R.string.import_path_separator)),
                    )

                else -> appContext.getString(res)
            }
        return SchemeImportResult.Failure(message, retryable = SchemeImportProblems.retryable(kind))
    }

    override suspend fun mergePackageLearning(sourceId: String): PackageLearningResult =
        withContext(Dispatchers.IO) {
            val service = learningService()
            val flow =
                PackageLearningMerge(
                    packageLearning,
                    { GenerationPublisher(storage).readActive()?.sourceId },
                    { id -> activate(id) },
                    { snapshots -> service.mergeRimeSnapshots(snapshots) },
                )
            when (val outcome = flow.accept(sourceId)) {
                is PackageLearningOutcome.Merged ->
                    PackageLearningResult.Merged(
                        LearningFilesText.merged(
                            appContext,
                            outcome.report.dictionaryCount,
                            outcome.report.entryCount,
                        ),
                    )
                PackageLearningOutcome.NothingToMerge ->
                    PackageLearningResult.Failure(appContext.getString(R.string.package_learning_nothing))
                PackageLearningOutcome.NotInUse ->
                    PackageLearningResult.Failure(appContext.getString(R.string.package_learning_not_in_use))
                PackageLearningOutcome.Gone ->
                    PackageLearningResult.Failure(appContext.getString(R.string.package_learning_gone))
                PackageLearningOutcome.Failed ->
                    PackageLearningResult.Failure(appContext.getString(R.string.package_learning_failed))
            }
        }

    override fun discardPackageLearning(sourceId: String) = packageLearning.discard(sourceId)

    private suspend fun activate(sourceId: String): Boolean =
        client.detached {
            val outcome =
                client.await(
                    DeployRequest(DeployContract.newRequestId(), DeployMode.ACTIVATE_SOURCE, sourceId = sourceId),
                )
            outcome is DeployWait.Finished && outcome.status.state == RequestState.SUCCEEDED
        }

    override fun cancelRunning() = client.cancelCurrent()

    companion object {
        private const val TAG = "SchemeImport"
    }
}
