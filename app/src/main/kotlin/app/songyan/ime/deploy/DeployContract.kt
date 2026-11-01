package app.songyan.ime.deploy

import android.content.Context
import android.content.Intent
import androidx.annotation.VisibleForTesting
import java.util.UUID

/** Actions and extras shared by the settings UI, the IME service and the :deploy process. */
object DeployContract {
    const val ACTION_RUN_DEPLOY = "app.songyan.ime.action.RUN_DEPLOY"
    const val ACTION_DEPLOY_FINISHED = "app.songyan.ime.action.DEPLOY_FINISHED"

    /** Stops the request named by [EXTRA_REQUEST_ID] at its next check. */
    const val ACTION_CANCEL = "app.songyan.ime.action.CANCEL_DEPLOY"

    const val EXTRA_REQUEST_ID = "requestId"
    const val EXTRA_FORCE = "force"
    const val EXTRA_SUCCESS = "success"
    const val EXTRA_PROBLEM = "problem"

    /** See [DeployMode]. */
    const val EXTRA_MODE = "mode"
    const val EXTRA_SOURCE_ID = "sourceId"
    const val EXTRA_URL = "url"

    /** A decimal string, so the request decoder only reads string extras. */
    const val EXTRA_MAX_BYTES = "maxBytes"

    /**
     * Debug builds only: the deploy service acts as if the daily dataSync limit ran out right after
     * it started, so device tests reach that path without changing device settings.
     */
    const val EXTRA_TEST_LIMIT_USED_UP = "testLimitUsedUp"

    /** Set by device tests in the settings process; adds [EXTRA_TEST_LIMIT_USED_UP] to every request. */
    @VisibleForTesting
    @Volatile
    var limitUsedUpForTests = false

    fun newRequestId(): String = UUID.randomUUID().toString().replace("-", "")

    /** A request's intent; the incoming directory of an import is derived from [DeployRequest.requestId]. */
    fun intent(
        context: Context,
        request: DeployRequest,
    ): Intent =
        Intent(context, DeployService::class.java)
            .setAction(ACTION_RUN_DEPLOY)
            .putExtra(EXTRA_REQUEST_ID, request.requestId)
            .putExtra(EXTRA_MODE, request.mode.wireName)
            .putExtra(EXTRA_FORCE, request.force)
            .apply {
                request.sourceId?.let { putExtra(EXTRA_SOURCE_ID, it) }
                request.url?.let { putExtra(EXTRA_URL, it) }
                request.maxBytes?.let { putExtra(EXTRA_MAX_BYTES, it.toString()) }
                if (limitUsedUpForTests) putExtra(EXTRA_TEST_LIMIT_USED_UP, true)
            }

    fun builtinIntent(
        context: Context,
        force: Boolean,
    ): Intent = intent(context, DeployRequest(newRequestId(), DeployMode.BUILTIN, force = force))

    fun cancelIntent(
        context: Context,
        requestId: String,
    ): Intent =
        Intent(context, DeployService::class.java)
            .setAction(ACTION_CANCEL)
            .putExtra(EXTRA_REQUEST_ID, requestId)

    fun redeployActiveIntent(context: Context): Intent =
        intent(context, DeployRequest(newRequestId(), DeployMode.REDEPLOY_ACTIVE))

    fun completeCompanionsIntent(context: Context): Intent =
        intent(context, DeployRequest(newRequestId(), DeployMode.COMPLETE_COMPANIONS))

    /** Reads a request out of [intent]; null when it is malformed. */
    fun requestFrom(intent: Intent?): DeployRequest? =
        intent?.let {
            DeployRequest.from(
                requestId = it.getStringExtra(EXTRA_REQUEST_ID),
                mode = it.getStringExtra(EXTRA_MODE),
                force = it.getBooleanExtra(EXTRA_FORCE, false),
                stringExtra = it::getStringExtra,
            )
        }
}
