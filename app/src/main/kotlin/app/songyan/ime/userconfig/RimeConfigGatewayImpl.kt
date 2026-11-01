package app.songyan.ime.userconfig

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import app.songyan.ime.R
import app.songyan.ime.content.DeployWait
import app.songyan.ime.content.ImportDeployClient
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployMode
import app.songyan.ime.deploy.DeployPipeline
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.DeploymentCoordinator
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.settings.state.RimeConfigApplyResult
import app.songyan.ime.settings.state.RimeConfigGateway
import app.songyan.ime.settings.state.RimeConfigInfo
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The settings side of the user's Rime configuration: the folder's state, read off the main
 * thread, and applying it through the :deploy process ([DeployMode.APPLY_USER_CONFIG]).
 */
internal class RimeConfigGatewayImpl(
    context: Context,
    private val storage: AppStorage,
    private val client: ImportDeployClient = ImportDeployClient(context.applicationContext, storage),
) : RimeConfigGateway {
    private val appContext = context.applicationContext
    private val statusStore = UserConfigStatus.Store(storage)

    override val available: Boolean = true

    /**
     * The Files app opens a provider's root for `vnd.android.document/root`; some versions only
     * open a folder document. Both are tried in this order by the screen.
     */
    override fun openFolderIntents(): List<Intent> =
        listOf(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(RimeConfigProvider.rootUri(appContext), DocumentsContract.Root.MIME_TYPE_ITEM)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(RimeConfigProvider.editFolderUri(appContext), DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )

    override suspend fun info(): RimeConfigInfo =
        withContext(Dispatchers.IO) {
            val files = UserConfigFiles.files(storage.userConfigDir)
            val edited = UserConfigFiles.digest(storage.userConfigDir)
            val applied = UserConfigFiles.digest(storage.appliedConfigDir)
            val status = statusStore.read()
            val active = GenerationPublisher(storage).readActive()?.sourceId
            RimeConfigInfo(
                fileCount = files.size,
                pending = edited != applied,
                // A failure only matters while the files that caused it are still the edited ones.
                failure =
                    status?.takeIf { it.state == UserConfigStatus.State.FAILED && edited != applied }?.let(
                        ::message,
                    ),
                notice =
                    status
                        ?.takeIf { it.state == UserConfigStatus.State.SKIPPED && it.sourceId == active }
                        ?.let { appContext.getString(R.string.rime_config_skipped) },
            )
        }

    override suspend fun apply(): RimeConfigApplyResult =
        client.detached {
            val request = DeployRequest(DeployContract.newRequestId(), DeployMode.APPLY_USER_CONFIG)
            when (val outcome = client.await(request)) {
                is DeployWait.Finished ->
                    if (outcome.status.state == RequestState.SUCCEEDED) {
                        RimeConfigApplyResult.Applied
                    } else {
                        val problem = outcome.status.problem?.removePrefix(DeployPipeline.CONFIG_PREFIX)
                        RimeConfigApplyResult.Failure(message(problem, outcome.status.paths.firstOrNull()))
                    }
                DeployWait.TimedOut, DeployWait.Unavailable ->
                    RimeConfigApplyResult.Failure(appContext.getString(R.string.rime_config_error_unavailable))
            }
        }

    private fun message(status: UserConfigStatus): String = message(status.problem, status.file)

    private fun message(
        problem: String?,
        file: String?,
    ): String {
        val kind = UserConfigProblem.entries.firstOrNull { it.name == problem }
        return when {
            kind == UserConfigProblem.INVALID_YAML && file != null ->
                appContext.getString(
                    R.string.rime_config_error_yaml,
                    file,
                )
            kind == UserConfigProblem.NOT_TEXT && file != null ->
                appContext.getString(
                    R.string.rime_config_error_text,
                    file,
                )
            kind == UserConfigProblem.TOO_LARGE && file != null ->
                appContext.getString(
                    R.string.rime_config_error_large,
                    file,
                )
            kind == UserConfigProblem.TOO_MANY -> appContext.getString(R.string.rime_config_error_many)
            problem == DeploymentCoordinator.DEPLOY_PROBLEM || kind != null ->
                appContext.getString(
                    R.string.rime_config_error_deploy,
                )
            else -> appContext.getString(R.string.rime_config_error_unavailable)
        }
    }
}
