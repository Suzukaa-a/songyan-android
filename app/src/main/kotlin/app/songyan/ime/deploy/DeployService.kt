package app.songyan.ime.deploy

import android.app.ForegroundServiceStartNotAllowedException
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import app.songyan.ime.BuildConfig
import app.songyan.ime.R
import app.songyan.ime.SongyanApplication
import java.util.concurrent.Executors

/**
 * Runs Rime deployments and imports in the private :deploy process, so the input method process
 * never blocks on maintenance and never touches the network. The requests run on the process's
 * [DeployWorker]; this service keeps the process running in the foreground meanwhile, and hands
 * that over to [DeployJobService] once Android no longer lets it.
 */
class DeployService : Service() {
    private val worker by lazy { DeployWorker.of(application as SongyanApplication) }
    private val stopWhenIdle = DeployWorker.IdleListener { stopIfIdle(it) }

    /** Whether the latest start made this a foreground service; main thread only. */
    private var foreground = false

    /**
     * Runs the job hand-off and the abandon decision in order, off the main thread: both are
     * binder calls, and a slow onStartCommand widens the window in which a process that dies
     * gets its start command delivered again.
     */
    private val handoff = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        DeployNotifications.ensureChannel(this)
        worker.addIdleListener(stopWhenIdle)
    }

    /**
     * Binding carries no calls; it only lets a waiter in another process learn that this process
     * died (a native crash in the engine cannot be caught here, so no terminal status gets written).
     */
    private val deathToken = Binder()

    override fun onBind(intent: Intent?): IBinder = deathToken

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val request = DeployContract.requestFrom(intent)
        if (intent?.action == DeployContract.ACTION_CANCEL) {
            intent.getStringExtra(DeployContract.EXTRA_REQUEST_ID)?.let(worker::cancel)
        }
        // Android requires startForeground after every startForegroundService, even for a request
        // that is only queued or malformed.
        foreground = startForeground(request)
        worker.started(startId, request.takeIf { intent?.action == DeployContract.ACTION_RUN_DEPLOY })
        if (BuildConfig.DEBUG && intent?.getBooleanExtra(DeployContract.EXTRA_TEST_LIMIT_USED_UP, false) == true) {
            limitReached()
        } else {
            handOffIfNeeded()
        }
        if (!worker.busy) stopIfIdle(startId)
        return START_NOT_STICKY
    }

    /** Lets the deploy job keep the process running when this service may not. */
    private fun handOffIfNeeded() {
        val inForeground = foreground
        handoff.execute {
            if (DeployHandoff.needsJob(inForeground, worker.busy)) DeployJobService.schedule(this)
        }
    }

    /**
     * Shows the progress notification as a dataSync foreground service. Android 15+ caps dataSync at
     * 6 hours a day (https://developer.android.com/develop/background-work/services/fgs/timeout);
     * once that is used up the start throws. The request then still runs, without the notification,
     * and the deploy job keeps the process running instead. Returns whether this is now a
     * foreground service.
     */
    private fun startForeground(request: DeployRequest?): Boolean {
        val (title, text) =
            when (request?.mode) {
                DeployMode.IMPORT_DICTIONARY, DeployMode.IMPORT_DICTIONARY_URL ->
                    R.string.deploy_notification_dictionary_title to R.string.deploy_notification_dictionary_text

                DeployMode.IMPORT_SCHEME ->
                    R.string.deploy_notification_scheme_title to R.string.deploy_notification_scheme_text

                DeployMode.DOWNLOAD_FILE ->
                    R.string.deploy_notification_download_title to R.string.deploy_notification_download_text

                else -> R.string.deploy_notification_title to R.string.deploy_notification_text
            }
        try {
            ServiceCompat.startForeground(
                this,
                DeployNotifications.SERVICE_NOTIFICATION_ID,
                DeployNotifications.progress(this, title, text),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            return true
        } catch (e: IllegalStateException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                e !is ForegroundServiceStartNotAllowedException
            ) {
                throw e
            }
            Log.w(TAG, "running without a foreground notification", e)
            return false
        }
    }

    /**
     * The daily dataSync budget ran out while running (Android 15+). The system needs stopSelf
     * within seconds; the deploy job takes the queue over.
     */
    override fun onTimeout(
        startId: Int,
        fgsType: Int,
    ) {
        Log.w(TAG, "dataSync time limit reached")
        limitReached()
    }

    private fun limitReached() {
        foreground = false
        handOffIfNeeded()
        stopForeground(Service.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopIfIdle(startId: Int) {
        // A request that arrived after the last check keeps the service alive; stopSelfResult
        // refuses to stop for a newer start id.
        if (stopSelfResult(startId)) stopForeground(Service.STOP_FOREGROUND_DETACH)
    }

    /**
     * The system can stop this service with work left, for example a request from the input
     * method that runs without a foreground notification once the app is in the background.
     * When the deploy job carries the queue, the work goes on. Otherwise the requests fail closed
     * with a terminal status and a finished broadcast, so the keyboard shows the failure and can
     * retry later, and nothing waits on a request that will never run.
     */
    override fun onDestroy() {
        worker.removeIdleListener(stopWhenIdle)
        handoff.execute { if (worker.busy && !DeployJobService.pending(this)) worker.abandon() }
        handoff.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "DeployService"
    }
}
