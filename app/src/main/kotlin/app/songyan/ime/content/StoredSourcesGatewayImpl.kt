package app.songyan.ime.content

import android.content.Context
import app.songyan.ime.R
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.settings.state.StoredSourceResult
import app.songyan.ime.settings.state.StoredSourcesGateway
import app.songyan.ime.storage.AppStorage

/** Switching to and deleting stored sources; both run as requests in the :deploy process. */
internal class StoredSourcesGatewayImpl(
    context: Context,
    storage: AppStorage,
    private val client: ImportDeployClient = ImportDeployClient(context.applicationContext, storage),
) : StoredSourcesGateway {
    private val appContext = context.applicationContext

    override val available: Boolean = true

    override suspend fun use(sourceId: String): StoredSourceResult =
        run(DeployMode.ACTIVATE_SOURCE, sourceId, R.string.source_error_activate)

    override suspend fun delete(sourceId: String): StoredSourceResult =
        run(DeployMode.DELETE_SOURCE, sourceId, R.string.source_error_delete)

    override suspend fun restore(sourceId: String): StoredSourceResult =
        run(DeployMode.RESTORE_SOURCE, sourceId, R.string.source_error_restore)

    private suspend fun run(
        mode: DeployMode,
        sourceId: String,
        failure: Int,
    ): StoredSourceResult =
        client.detached {
            val outcome = client.await(DeployRequest(DeployContract.newRequestId(), mode, sourceId = sourceId))
            when {
                outcome is DeployWait.Finished && outcome.status.state == RequestState.SUCCEEDED ->
                    StoredSourceResult.Success
                outcome is DeployWait.Unavailable ->
                    StoredSourceResult.Failure(appContext.getString(R.string.scheme_import_error_deploy_unavailable))

                else -> StoredSourceResult.Failure(appContext.getString(failure))
            }
        }
}
