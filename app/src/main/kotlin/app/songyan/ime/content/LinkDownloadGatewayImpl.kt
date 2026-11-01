package app.songyan.ime.content

import android.content.Context
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.settings.state.LinkDownloadGateway
import app.songyan.ime.settings.state.LinkDownloadResult
import app.songyan.ime.storage.AppStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Small downloads for settings screens (a 双拼提示 preset): the :deploy process fetches the file
 * under the caller's size limit, this process reads it and deletes it.
 */
internal class LinkDownloadGatewayImpl(
    context: Context,
    private val storage: AppStorage,
    private val client: ImportDeployClient = ImportDeployClient(context.applicationContext, storage),
) : LinkDownloadGateway {
    private val appContext = context.applicationContext

    override val available = true

    override suspend fun download(
        url: String,
        maxBytes: Long,
    ): LinkDownloadResult =
        client.detached {
            val request =
                DeployRequest(DeployContract.newRequestId(), DeployMode.DOWNLOAD_FILE, url = url, maxBytes = maxBytes)
            val file = storage.downloadFile(request.requestId)
            try {
                when (val outcome = client.await(request)) {
                    is DeployWait.Finished ->
                        if (outcome.status.state == RequestState.SUCCEEDED) {
                            withContext(Dispatchers.IO) {
                                runCatching { LinkDownloadResult.Downloaded(file.readBytes()) }
                                    .getOrElse { failure(DictionaryImportProblemKind.DOWNLOAD_FAILED, emptyList()) }
                            }
                        } else {
                            val problem =
                                DictionaryImportProblems.kindNamed(outcome.status.problem)
                                    ?: DictionaryImportProblemKind.DOWNLOAD_FAILED
                            failure(problem, outcome.status.paths)
                        }

                    DeployWait.TimedOut -> failure(DictionaryImportProblemKind.DEPLOY_TIMEOUT, emptyList())
                    DeployWait.Unavailable -> failure(DictionaryImportProblemKind.DEPLOY_UNAVAILABLE, emptyList())
                }
            } finally {
                withContext(Dispatchers.IO) { file.delete() }
            }
        }

    private fun failure(
        problem: DictionaryImportProblemKind,
        paths: List<String>,
    ): LinkDownloadResult.Failure =
        if (problem == DictionaryImportProblemKind.FILE_TOO_LARGE) {
            LinkDownloadResult.Failure(null, tooLarge = true)
        } else {
            LinkDownloadResult.Failure(
                DictionaryImportMessages.format(appContext, problem, paths),
                retryable = DictionaryImportProblems.retryable(problem),
            )
        }

    override fun cancelRunning() = client.cancelCurrent()
}
