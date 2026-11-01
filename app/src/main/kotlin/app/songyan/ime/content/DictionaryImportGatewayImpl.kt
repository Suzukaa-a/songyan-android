package app.songyan.ime.content

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import app.songyan.ime.R
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployPipeline
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.packages.DictionaryImportBuilder
import app.songyan.ime.settings.state.DictionaryImportGateway
import app.songyan.ime.settings.state.DictionaryImportResult
import app.songyan.ime.settings.state.ImportedDictionaryInfo
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * The settings side of a dictionary import: copy the picked files into the request's incoming
 * directory and let the :deploy process check, build and publish them. A link import hands only
 * the link over; the download happens in :deploy, never in this process.
 */
internal class DictionaryImportGatewayImpl(
    context: Context,
    /**
     * Passed in rather than derived here: resolving Context.filesDir touches the file system, and
     * the gateway is constructed from Application.onCreate on the main thread.
     */
    private val storage: AppStorage,
    private val client: ImportDeployClient = ImportDeployClient(context.applicationContext, storage),
) : DictionaryImportGateway {
    private val appContext = context.applicationContext

    override val available: Boolean = true

    override suspend fun importFromUris(
        uris: List<Uri>,
        target: String?,
    ): DictionaryImportResult =
        client.detached {
            val requestId = DeployContract.newRequestId()
            val incoming = storage.incomingDir(requestId)
            try {
                copyPicked(uris, File(incoming, DeployPipeline.FILES))
            } catch (e: CancellationException) {
                incoming.deleteRecursively()
                throw e
            } catch (e: DictionaryImportProblemException) {
                incoming.deleteRecursively()
                return@detached failure(e.kind)
            } catch (e: Exception) {
                Log.w(TAG, "cannot read the picked dictionary", e)
                incoming.deleteRecursively()
                return@detached failure(DictionaryImportProblemKind.READ_FAILED)
            }
            // From here on the incoming directory belongs to the deploy process.
            finish(DeployRequest(requestId, DeployMode.IMPORT_DICTIONARY, sourceId = stackTarget(target)), target)
        }

    override suspend fun importFromUrl(
        url: String,
        target: String?,
    ): DictionaryImportResult =
        client.detached {
            finish(
                DeployRequest(
                    DeployContract.newRequestId(),
                    DeployMode.IMPORT_DICTIONARY_URL,
                    sourceId = stackTarget(target),
                    url = url,
                ),
                target,
            )
        }

    override suspend fun removeImportedDictionary(target: String?): DictionaryImportResult =
        client.detached {
            val stacked = stackTarget(target)
            val exists =
                withContext(Dispatchers.IO) {
                    if (stacked != null) {
                        storage.stackedDictionaryDir(stacked).exists()
                    } else {
                        storage.sourceDir(SourceIds.DICTIONARY).exists()
                    }
                }
            // Nothing was imported: answering success keeps the screen honest without a deploy.
            if (!exists) return@detached DictionaryImportResult.Success(info = null)
            val request =
                if (stacked != null) {
                    DeployRequest(DeployContract.newRequestId(), DeployMode.REMOVE_DICTIONARY, sourceId = stacked)
                } else {
                    DeployRequest(
                        DeployContract.newRequestId(),
                        DeployMode.DELETE_SOURCE,
                        sourceId = SourceIds.DICTIONARY,
                    )
                }
            when (val outcome = client.await(request)) {
                is DeployWait.Finished ->
                    if (outcome.status.state == RequestState.SUCCEEDED) {
                        DictionaryImportResult.Success(info = null)
                    } else {
                        failure(DictionaryImportProblemKind.REMOVE_FAILED)
                    }

                DeployWait.TimedOut -> failure(DictionaryImportProblemKind.DEPLOY_TIMEOUT)
                DeployWait.Unavailable -> failure(DictionaryImportProblemKind.DEPLOY_UNAVAILABLE)
            }
        }

    /** Only a stored scheme package is a stacking target; anything else means the built-in input methods. */
    private fun stackTarget(target: String?): String? =
        target?.takeIf { SourceIds.isScheme(it) && SourceIds.isValid(it) }

    private suspend fun finish(
        request: DeployRequest,
        target: String?,
    ): DictionaryImportResult =
        when (val outcome = client.await(request)) {
            is DeployWait.Finished -> {
                val status = outcome.status
                if (status.state == RequestState.SUCCEEDED) {
                    val stacked = stackTarget(target)
                    val info =
                        withContext(Dispatchers.IO) {
                            if (stacked != null) {
                                ImportedDictionaryMetadataReader.read(storage.stackedDictionaryDir(stacked))?.let {
                                    ImportedDictionaryInfo(
                                        it.displayName,
                                        it.entryCount,
                                        inUse = true,
                                        targetSourceId = stacked,
                                    )
                                }
                            } else {
                                SourceCatalog(storage).read(SourceIds.DICTIONARY)?.let {
                                    ImportedDictionaryInfo(it.displayName, it.entryCount, inUse = true)
                                }
                            }
                        }
                    DictionaryImportResult.Success(info)
                } else {
                    val kind =
                        DictionaryImportProblems.kindNamed(status.problem) ?: DictionaryImportProblemKind.DEPLOY_FAILED
                    failure(kind, status.paths)
                }
            }

            DeployWait.TimedOut -> failure(DictionaryImportProblemKind.DEPLOY_TIMEOUT)
            DeployWait.Unavailable -> failure(DictionaryImportProblemKind.DEPLOY_UNAVAILABLE)
        }

    /**
     * Streams every picked document into [target] under the name its provider reports, bounded by
     * the importer's total cap, so a wrong pick never fills memory or the disk.
     */
    private fun copyPicked(
        uris: List<Uri>,
        target: File,
    ) {
        if (uris.isEmpty()) throw DictionaryImportProblemException(DictionaryImportProblemKind.READ_FAILED)
        if (uris.size > DictionaryImportBuilder.MAX_FILE_COUNT) {
            throw DictionaryImportProblemException(DictionaryImportProblemKind.TOO_MANY_FILES)
        }
        target.mkdirs()
        var budget = DictionaryImportBuilder.MAX_BYTE_COUNT
        val used = HashSet<String>()
        for (uri in uris) {
            val candidate = contentFileName(uri)
            if (!DictionaryImportFiles.isDictionaryName(candidate)) {
                throw DictionaryImportProblemException(DictionaryImportProblemKind.UNSUPPORTED_FILE)
            }
            val name = DictionaryImportFiles.uniqueStagingName(candidate, used)
            val input =
                appContext.contentResolver.openInputStream(uri)
                    ?: throw DictionaryImportProblemException(DictionaryImportProblemKind.READ_FAILED)
            input.use { stream ->
                FileOutputStream(File(target, name)).use { output ->
                    budget -= DictionaryImportFiles.copyCapped(stream, output, budget)
                }
            }
        }
    }

    /** The file name the picker reports; providers may expose only a display name. */
    private fun contentFileName(uri: Uri): String {
        val displayName =
            runCatching {
                appContext.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
            }.getOrNull()
        return displayName ?: uri.lastPathSegment.orEmpty()
    }

    private fun failure(
        kind: DictionaryImportProblemKind,
        paths: List<String> = emptyList(),
    ): DictionaryImportResult =
        DictionaryImportResult.Failure(
            message = DictionaryImportMessages.format(appContext, kind, paths),
            retryable = DictionaryImportProblems.retryable(kind),
        )

    override fun cancelRunning() = client.cancelCurrent()

    companion object {
        private const val TAG = "DictionaryImport"
    }
}

/** Formats a problem's copy, filling in the size limit or the files a request still needs. */
internal object DictionaryImportMessages {
    fun format(
        context: Context,
        kind: DictionaryImportProblemKind,
        paths: List<String>,
    ): String {
        val res = DictionaryImportProblems.messageRes(kind)
        return when (kind) {
            DictionaryImportProblemKind.FILE_TOO_LARGE ->
                context.getString(
                    res,
                    DictionaryImportProblems.SIZE_LIMIT_LABEL,
                )
            DictionaryImportProblemKind.MISSING_DEPENDENCIES,
            DictionaryImportProblemKind.UNREFERENCED_FILE,
            -> context.getString(res, ImportPaths.list(paths, context.getString(R.string.import_path_separator)))

            DictionaryImportProblemKind.INVALID_ROW,
            DictionaryImportProblemKind.INVALID_WORD,
            DictionaryImportProblemKind.SYLLABLE_COUNT_MISMATCH,
            DictionaryImportProblemKind.UNKNOWN_SYLLABLE,
            DictionaryImportProblemKind.INVALID_WEIGHT,
            -> {
                val location = DictionaryImportProblems.parseLocation(paths.firstOrNull())
                if (location == null) {
                    context.getString(R.string.dictionary_import_error_row_unknown)
                } else {
                    context.getString(res, location.first, location.second)
                }
            }

            DictionaryImportProblemKind.DOWNLOAD_SERVER ->
                context.getString(res, paths.firstOrNull().orEmpty())

            else -> context.getString(res)
        }
    }
}

/** A short, readable list of file names for an error message. */
internal object ImportPaths {
    private const val MAX_SHOWN = 3

    fun list(
        paths: List<String>,
        separator: String,
    ): String {
        val names = paths.map { it.substringAfterLast('/') }.distinct()
        val shown = names.take(MAX_SHOWN).joinToString(separator)
        return if (names.size > MAX_SHOWN) "$shown…" else shown
    }
}
