package app.songyan.ime.content

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployPipeline
import app.songyan.ime.deploy.DeployRequest
import app.songyan.ime.deploy.DeployRequestStore
import app.songyan.ime.deploy.DeployService
import app.songyan.ime.deploy.DeployState
import app.songyan.ime.deploy.DeployStatusStore
import app.songyan.ime.deploy.RequestState
import app.songyan.ime.deploy.RequestStatus
import app.songyan.ime.settings.state.TransferProgress
import app.songyan.ime.storage.AppStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** How one request ended, seen from the settings process. */
internal sealed interface DeployWait {
    data class Finished(
        val status: RequestStatus,
    ) : DeployWait

    /** Android refused to start the deploy service. */
    data object Unavailable : DeployWait

    /** The request did not finish in time and was cancelled. */
    data object TimedOut : DeployWait
}

/**
 * Hands requests to the :deploy process and waits for their own terminal status.
 *
 * Every wait runs in a scope that lives as long as the process, not the screen: leaving settings
 * cancels only the screen's await, while the request keeps running and its outcome still reaches
 * the settings state.
 */
internal class ImportDeployClient(
    private val appContext: Context,
    private val storage: AppStorage,
    private val pollIntervalMs: Long = 250L,
    private val timeoutMs: Long = 20L * 60 * 1000,
) {
    private val requests = DeployRequestStore(storage)

    private val cancellation =
        ImportRequestCancellation { id ->
            runCatching { appContext.startService(DeployContract.cancelIntent(appContext, id)) }
        }

    /** Asks the :deploy process to stop the request this client is waiting for, if any. */
    fun cancelCurrent() = cancellation.cancel()

    /** Runs [block] to completion even when the caller (a screen) is cancelled meanwhile. */
    suspend fun <T> detached(block: suspend () -> T): T {
        cancellation.beginTask()
        return processScope
            .async {
                try {
                    block()
                } finally {
                    cancellation.endTask()
                }
            }.await()
    }

    /** Starts [request] and waits for its terminal status; [progress] hears a download's progress. */
    suspend fun await(
        request: DeployRequest,
        progress: (TransferProgress) -> Unit = {},
    ): DeployWait = startAndWait(request, progress)

    private suspend fun startAndWait(
        request: DeployRequest,
        progress: (TransferProgress) -> Unit,
    ): DeployWait {
        val started =
            try {
                ContextCompat.startForegroundService(appContext, DeployContract.intent(appContext, request))
                true
            } catch (e: Exception) {
                // Android 12+ refuses a foreground service when the app is not visible.
                Log.w(TAG, "cannot start the deploy service", e)
                false
            }
        if (!started) return DeployWait.Unavailable
        return await(request.requestId, progress)
    }

    /**
     * Polls the request's status file, woken early by its finished broadcast. A binding to the
     * deploy service reports the death of the :deploy process: a native crash there writes no
     * terminal status, and without the binding the request would look running until the timeout.
     */
    private suspend fun await(
        requestId: String,
        progress: (TransferProgress) -> Unit,
    ): DeployWait {
        cancellation.attachRequest(requestId)
        val announced = AtomicBoolean(false)
        val died = AtomicBoolean(false)
        val watch =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) = Unit

                override fun onServiceDisconnected(name: ComponentName) = died.set(true)

                override fun onBindingDied(name: ComponentName) = died.set(true)
            }
        // BIND_AUTO_CREATE keeps the service alive while bound, so a disconnect means the process
        // died rather than that the service stopped after its queue drained.
        val bound =
            runCatching {
                appContext.bindService(
                    Intent(appContext, DeployService::class.java),
                    watch,
                    Context.BIND_AUTO_CREATE,
                )
            }.getOrDefault(false)
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(
                    context: Context,
                    intent: Intent,
                ) {
                    if (intent.getStringExtra(DeployContract.EXTRA_REQUEST_ID) == requestId) announced.set(true)
                }
            }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(DeployContract.ACTION_DEPLOY_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            while (SystemClock.elapsedRealtime() < deadline) {
                val status = withContext(Dispatchers.IO) { requests.read(requestId) }
                if (status != null && status.state.isTerminal) return DeployWait.Finished(status)
                status?.downloadedBytes?.let { bytes -> progress(TransferProgress(bytes, status.totalBytes)) }
                if (died.get()) {
                    // The process may have written the result just before it died.
                    val last = withContext(Dispatchers.IO) { requests.read(requestId) }
                    return DeployWait.Finished(last?.takeIf { it.state.isTerminal } ?: abandon(requestId))
                }
                if (!announced.get()) delay(pollIntervalMs) else delay(POLL_AFTER_BROADCAST_MS)
            }
            runCatching { appContext.startService(DeployContract.cancelIntent(appContext, requestId)) }
            return DeployWait.TimedOut
        } finally {
            runCatching { appContext.unregisterReceiver(receiver) }
            if (bound) runCatching { appContext.unbindService(watch) }
        }
    }

    /**
     * Records the failure the dead process could not write, so every reader of the request and of
     * the coarse status sees it ended, and drops the files only that request would have used. The
     * queue lived in the dead process, so nothing will pick the request up again.
     */
    private suspend fun abandon(requestId: String): RequestStatus =
        withContext(Dispatchers.IO) {
            Log.w(TAG, "the deploy process died while running request " + requestId)
            val failed = RequestStatus(RequestState.FAILED, problem = DeployPipeline.DEPLOY_FAILED)
            runCatching { requests.write(requestId, failed) }
            runCatching {
                val coarse = DeployStatusStore(storage)
                val snapshot = coarse.readSnapshot()
                if (snapshot.state == DeployState.PREPARING && snapshot.tag == requestId) {
                    coarse.write(DeployState.FAILED, requestId)
                }
            }
            storage.incomingDir(requestId).deleteRecursively()
            File(storage.importStagingDir, DeployPipeline.STAGED_PREFIX + requestId).deleteRecursively()
            failed
        }

    companion object {
        private const val TAG = "ImportDeploy"
        private const val POLL_AFTER_BROADCAST_MS = 20L

        /** Outlives every screen; requests are user actions, so there are only ever a few. */
        private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

/** Keeps a cancellation made during file preparation attached to that operation. */
internal class ImportRequestCancellation(
    private val send: (String) -> Unit,
) {
    private var running = false
    private var requested = false
    private var requestId: String? = null

    @Synchronized
    fun beginTask() {
        running = true
    }

    @Synchronized
    fun attachRequest(id: String) {
        requestId = id
        if (requested) send(id)
    }

    @Synchronized
    fun cancel() {
        if (!running) return
        requested = true
        requestId?.let(send)
    }

    @Synchronized
    fun endTask() {
        running = false
        requested = false
        requestId = null
    }
}
