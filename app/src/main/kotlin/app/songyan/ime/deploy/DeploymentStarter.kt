package app.songyan.ime.deploy

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import app.songyan.ime.BuildConfig
import app.songyan.ime.SongyanApplication
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.InputSchemeSetup
import app.songyan.ime.storage.SourceIds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Starts the deploy process when no current, usable published generation exists. */
object DeploymentStarter {
    private const val TAG = "DeploymentStarter"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Whether the active generation is complete and was built by this app, engine and data. Off the main thread. */
    fun isReady(context: Context): Boolean {
        val app = context.applicationContext as? SongyanApplication ?: return false
        return try {
            val usable = GenerationPublisher(app.storage).usablePublication() ?: return false
            BuildStamps.isCurrent(usable, BuildStamps.current(app))
        } catch (e: Exception) {
            Log.w(TAG, "cannot read publication state", e)
            false
        }
    }

    /**
     * Makes some current generation active: the deploy process reuses one that is, rebuilds the
     * active source when it is stale. Fresh installs wait for a user-chosen scheme.
     */
    fun requestEnsureDeployed(context: Context) {
        val app = context.applicationContext as SongyanApplication
        scope.launch {
            val needsDownload = InputSchemeSetup.needsDownload(app.storage)
            // Performance journeys use the same offline fixture on every clean emulator.
            val benchmark =
                BuildConfig.BUILD_TYPE == "benchmarkRelease" || BuildConfig.BUILD_TYPE == "nonMinifiedRelease"
            if (!needsDownload || benchmark) {
                start(app, DeployContract.builtinIntent(app, force = needsDownload && benchmark), jobFallback = true)
            }
        }
    }

    /** Rebuilds whatever source is active (the keyboard's retry; never silently the built-in one). */
    fun requestRedeployActive(context: Context) {
        val app = context.applicationContext as SongyanApplication
        scope.launch {
            if (!InputSchemeSetup.needsDownload(app.storage)) {
                start(app, DeployContract.redeployActiveIntent(app))
            }
        }
    }

    /** Makes the built-in source active, reusing its generation when one is current (crash-loop fallback). */
    fun requestActivateBuiltin(context: Context) =
        start(
            context,
            DeployContract.intent(
                context,
                DeployRequest(DeployContract.newRequestId(), DeployMode.ACTIVATE_SOURCE, sourceId = SourceIds.BUILTIN),
            ),
        )

    /**
     * With [jobFallback], a start Android refuses (the app is in the background on Android 12+)
     * goes to the deploy job, which finds the same work in the published state.
     */
    private fun start(
        context: Context,
        intent: Intent,
        jobFallback: Boolean = false,
    ) {
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure {
            Log.e(TAG, "cannot start deploy service", it)
            if (jobFallback) DeployJobService.schedule(context)
        }
    }

    /**
     * Safe to call from the main thread: the readiness check runs on IO. A generation still missing
     * some layouts (the deploy process stopped before completing it) is completed.
     */
    fun ensureDeployed(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            if (!isReady(appContext)) {
                requestEnsureDeployed(appContext)
            } else if (companionsPending(appContext)) {
                start(appContext, DeployContract.completeCompanionsIntent(appContext), jobFallback = true)
            }
        }
    }

    /** Safe to call from the main thread: completes a generation still missing some layouts. */
    fun completeCompanionsIfPending(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            if (isReady(appContext) && companionsPending(appContext)) {
                start(appContext, DeployContract.completeCompanionsIntent(appContext), jobFallback = true)
            }
        }
    }

    private fun companionsPending(context: Context): Boolean {
        val app = context as? SongyanApplication ?: return false
        return runCatching { GenerationPublisher(app.storage).usablePublication()?.companionsPending == true }
            .getOrDefault(false)
    }
}

/**
 * An app update replaces the code that built every generation; rebuild the active source right
 * away instead of when the keyboard next opens. Android lets an app start a foreground service
 * from this broadcast (https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start#background-start-restriction-exemptions).
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent,
    ) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        DeploymentStarter.requestEnsureDeployed(context)
    }
}
