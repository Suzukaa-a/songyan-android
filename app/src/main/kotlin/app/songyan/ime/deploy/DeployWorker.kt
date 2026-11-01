package app.songyan.ime.deploy

import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import app.songyan.ime.SongyanApplication
import app.songyan.ime.rime.AndroidAssetTree
import app.songyan.ime.storage.GenerationPublisher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import kotlin.concurrent.thread

/**
 * The :deploy process's one deploy worker. Requests run one at a time in arrival order
 * ([DeployQueue]); only identical rebuilds merge. Every request ends with its own terminal status
 * in [DeployRequestStore] and its own finished broadcast, so a waiter learns the outcome of exactly
 * the request it sent.
 *
 * The worker belongs to the process, not to a component: [DeployService] (while it may run in the
 * foreground) and [DeployJobService] (once Android no longer lets it) only keep the process running
 * while the worker has work, so the queue survives the service being stopped for the daily
 * dataSync limit.
 */
class DeployWorker private constructor(
    private val app: SongyanApplication,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Guards [state], [current] and [drainJob] between the callers and the worker. */
    private val lock = Any()
    private val state = DeployWorkerState()
    private val queue get() = state.queue
    private var current: QueuedDeploy? = null
    private var drainJob: Job? = null
    private val cancelled = ConcurrentHashMap.newKeySet<String>()
    private val idleListeners = CopyOnWriteArraySet<IdleListener>()

    /** Hears that the queue ran dry; [lastStartId] is the service's newest start command. */
    fun interface IdleListener {
        fun onIdle(lastStartId: Int)
    }

    fun addIdleListener(listener: IdleListener) {
        idleListeners += listener
    }

    fun removeIdleListener(listener: IdleListener) {
        idleListeners -= listener
    }

    val busy: Boolean get() = synchronized(lock) { state.busy }

    fun cancel(requestId: String) {
        cancelled += requestId
    }

    /** Records the service's start command [startId] and queues [request], if any. */
    fun started(
        startId: Int,
        request: DeployRequest?,
    ) {
        synchronized(lock) {
            request?.let(::offer)
            if (state.started(startId)) launchDrain()
        }
    }

    /** Queues [request] without a start command (the deploy job). */
    fun submit(request: DeployRequest) {
        synchronized(lock) {
            offer(request)
            if (state.woken()) launchDrain()
        }
    }

    /**
     * Fails everything queued and the request in flight, for when no component keeps this process
     * running any more: nothing would ever run them, and their waiters must not wait forever.
     */
    fun abandon() {
        val abandoned =
            synchronized(lock) {
                current?.let { cancelled += it.request.requestId }
                drainJob?.cancel()
                listOfNotNull(current) + generateSequence { queue.poll() }.toList()
            }
        if (abandoned.isEmpty()) return
        val failed = RequestStatus(RequestState.FAILED, problem = DeployPipeline.DEPLOY_FAILED)
        // Off the main thread: these are file writes.
        thread(name = "songyan-deploy-abandon") { abandoned.forEach { finish(it, failed) } }
    }

    /** Under [lock]. */
    private fun offer(request: DeployRequest) {
        if (queue.offer(request)) markQueued(request.requestId)
    }

    /** Under [lock]. */
    private fun launchDrain() {
        drainJob = scope.launch { drain() }
    }

    /**
     * Writes the queued status off the main thread. Skipped once the worker took the request, so it
     * can never land on top of the running status or the result.
     */
    private fun markQueued(requestId: String) {
        val store = DeployRequestStore(app.storage)
        scope.launch(Dispatchers.IO) {
            synchronized(lock) {
                if (queue.contains(requestId)) {
                    runCatching { store.write(requestId, RequestStatus(RequestState.QUEUED)) }
                }
            }
        }
    }

    /** Runs queued requests oldest first until the queue is empty. */
    private suspend fun drain() {
        var lastSuccess = true
        var emptied = false
        try {
            while (true) {
                val next = synchronized(lock) { state.next() } ?: break
                lastSuccess = runOne(next)
                if (lastSuccess) queueCompanionsIfPending(next.request)
            }
            emptied = true
        } finally {
            // Cancelled by abandon: anything queued since gets a worker of its own.
            if (!emptied) synchronized(lock) { if (state.interrupted()) launchDrain() }
        }
        // A request that arrived after the queue ran dry has its own worker by now; it reports and
        // lets the service stop when it is done.
        val idle = synchronized(lock) { state.idleStartId() } ?: return
        DeployNotifications.notifyFinished(app, lastSuccess)
        idleListeners.forEach { it.onIdle(idle) }
    }

    /**
     * A deployment that built only the layouts of the input method in use, or left the feature
     * settings out, is completed right away, in the same run, so the keyboard picks up the rest
     * without anyone asking.
     */
    private fun queueCompanionsIfPending(finished: DeployRequest) {
        if (finished.mode == DeployMode.COMPLETE_COMPANIONS) return
        val pending = runCatching { GenerationPublisher(app.storage).usablePublication()?.companionsPending == true }
        if (pending.getOrDefault(false)) {
            synchronized(lock) {
                queue.offer(DeployRequest(DeployContract.newRequestId(), DeployMode.COMPLETE_COMPANIONS))
            }
        }
    }

    private suspend fun runOne(queued: QueuedDeploy): Boolean {
        val requests = DeployRequestStore(app.storage)
        val coarse = DeployStatusStore(app.storage)
        val request = queued.request
        synchronized(lock) { current = queued }
        val runStart = SystemClock.elapsedRealtime()
        val status =
            try {
                if (request.requestId in cancelled) {
                    RequestStatus(RequestState.FAILED, problem = DeployPipeline.CANCELLED)
                } else {
                    queued.requestIds.forEach { requests.write(it, RequestStatus(RequestState.RUNNING)) }
                    coarse.write(DeployState.PREPARING, request.requestId)
                    val runner = app.rimeDeployRunner
                    if (runner == null) {
                        Log.e(TAG, "no Rime deploy runner available")
                        RequestStatus(RequestState.FAILED, problem = DeployPipeline.DEPLOY_FAILED)
                    } else {
                        pipeline(runner).run(
                            request,
                            cancelled = { request.requestId in cancelled },
                            progress = DownloadStatusWriter(requests, queued.requestIds),
                        )
                    }
                }
            } catch (e: CancellationException) {
                synchronized(lock) { current = null }
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "deployment failed", e)
                RequestStatus(RequestState.FAILED, problem = DeployPipeline.DEPLOY_FAILED)
            }
        Log.i(
            DeployTimer.TAG,
            "request " + request.mode.wireName + " " + status.state + " in " +
                (SystemClock.elapsedRealtime() - runStart) +
                " ms",
        )
        cancelled -= request.requestId
        synchronized(lock) { current = null }
        finish(queued, status)
        return status.state == RequestState.SUCCEEDED
    }

    /** Writes every merged request's terminal status and broadcasts it. */
    private fun finish(
        queued: QueuedDeploy,
        status: RequestStatus,
    ) {
        val requests = DeployRequestStore(app.storage)
        val success = status.state == RequestState.SUCCEEDED
        for (id in queued.requestIds) {
            runCatching { requests.write(id, status) }
            broadcastFinished(id, queued.request.mode, status)
        }
        runCatching {
            DeployStatusStore(
                app.storage,
            ).write(if (success) DeployState.READY else DeployState.FAILED, queued.request.requestId)
            requests.purgeOlderThan(STATUS_RETENTION_MS)
        }
    }

    private fun pipeline(runner: RimeDeployRunner): DeployPipeline =
        DeployPipeline(
            storage = app.storage,
            coordinator = DeploymentCoordinator.create(app, runner),
            builder = SourceBuilder(AndroidAssetTree(app.assets, "rime")),
            online = ::isOnline,
        )

    /** Whether the device has a validated network, so a failed lookup can be explained correctly. */
    private fun isOnline(): Boolean {
        val manager = app.getSystemService(ConnectivityManager::class.java) ?: return true
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun broadcastFinished(
        requestId: String,
        mode: DeployMode,
        status: RequestStatus,
    ) {
        app.sendBroadcast(
            Intent(DeployContract.ACTION_DEPLOY_FINISHED)
                .setPackage(app.packageName)
                .putExtra(DeployContract.EXTRA_REQUEST_ID, requestId)
                .putExtra(DeployContract.EXTRA_MODE, mode.wireName)
                .putExtra(DeployContract.EXTRA_SUCCESS, status.state == RequestState.SUCCEEDED)
                .putExtra(DeployContract.EXTRA_PROBLEM, status.problem),
        )
    }

    /**
     * Writes a running download's progress into the request's status file, which the waiting
     * screen already polls. At most a few writes a second, and the last chunk always, so the
     * screen never shows a download as unfinished once it is done.
     */
    private class DownloadStatusWriter(
        private val requests: DeployRequestStore,
        private val requestIds: List<String>,
    ) : DownloadProgress {
        private var lastWrite = 0L

        override fun update(
            bytes: Long,
            total: Long?,
        ) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWrite < PROGRESS_INTERVAL_MS && bytes != total) return
            lastWrite = now
            val status = RequestStatus(RequestState.RUNNING, downloadedBytes = bytes, totalBytes = total)
            requestIds.forEach { id -> runCatching { requests.write(id, status) } }
        }
    }

    companion object {
        private const val TAG = "DeployWorker"
        private const val PROGRESS_INTERVAL_MS = 300L

        /** Status files outlive every waiter by far; older ones are swept. */
        private const val STATUS_RETENTION_MS = 24L * 60 * 60 * 1000

        @Volatile
        private var instance: DeployWorker? = null

        /** The worker of this process; only the :deploy components call it. */
        fun of(app: SongyanApplication): DeployWorker =
            instance ?: synchronized(this) { instance ?: DeployWorker(app).also { instance = it } }
    }
}
