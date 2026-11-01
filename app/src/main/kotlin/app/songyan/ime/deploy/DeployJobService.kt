package app.songyan.ime.deploy

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import app.songyan.ime.SongyanApplication
import app.songyan.ime.storage.GenerationPublisher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Decisions about who keeps the :deploy process running; pure so they are unit-testable. */
object DeployHandoff {
    /**
     * Whether the deploy job must take over: the service may no longer run in the foreground (the
     * daily dataSync limit is used up, or Android refused the start) while work remains. A plain
     * started service in the background gets stopped or frozen by the system, so the work would
     * otherwise wait for the next time the keyboard or the settings open.
     */
    fun needsJob(
        foreground: Boolean,
        busy: Boolean,
    ): Boolean = !foreground && busy

    /**
     * What the job finds to do from the published state alone, when the worker is idle (the
     * process was restarted and the queue lived in the old one): what opening the keyboard would
     * ask for.
     */
    fun recoveredWork(
        ready: Boolean,
        followUpPending: Boolean,
    ): DeployMode? =
        when {
            !ready -> DeployMode.BUILTIN
            followUpPending -> DeployMode.COMPLETE_COMPANIONS
            else -> null
        }
}

/**
 * Keeps the :deploy process running while its [DeployWorker] has work and [DeployService] may not
 * run in the foreground. The work itself still runs on the process's one worker, one request at a
 * time, so the engine singleton and the publication rules are those of every other deployment.
 *
 * A job rather than a binding from the keyboard: the keyboard's process exists only while it is
 * the current input method, and a follow-up started by an import in the settings has nobody else
 * to keep it alive. See https://developer.android.com/develop/background-work/services/fgs/timeout.
 */
class DeployJobService : JobService() {
    private val listener = AtomicReference<DeployWorker.IdleListener?>()

    override fun onStartJob(params: JobParameters): Boolean {
        val app = application as SongyanApplication
        val worker = DeployWorker.of(app)
        val done = DeployWorker.IdleListener { finished(params) }
        listener.set(done)
        worker.addIdleListener(done)
        if (worker.busy) return true
        // Nothing queued in this process: read what the published state still lacks, off the main thread.
        scope.launch {
            val work =
                DeployHandoff.recoveredWork(
                    ready = DeploymentStarter.isReady(app),
                    followUpPending =
                        runCatching { GenerationPublisher(app.storage).usablePublication()?.companionsPending == true }
                            .getOrDefault(false),
                )
            if (work == null) {
                if (!worker.busy) finished(params)
            } else {
                Log.i(TAG, "completing " + work.wireName + " in a job")
                worker.submit(DeployRequest(DeployContract.newRequestId(), work))
            }
        }
        return true
    }

    private fun finished(params: JobParameters) {
        val done = listener.getAndSet(null) ?: return
        DeployWorker.of(application as SongyanApplication).removeIdleListener(done)
        jobFinished(params, false)
    }

    /**
     * Android stopped the job early (its time ran out). The request in flight keeps running while
     * the process does; the job comes back later for whatever is left.
     */
    override fun onStopJob(params: JobParameters): Boolean {
        val worker = DeployWorker.of(application as SongyanApplication)
        listener.getAndSet(null)?.let(worker::removeIdleListener)
        return worker.busy
    }

    companion object {
        private const val TAG = "DeployJob"
        private const val JOB_ID = 0x50e9
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Whether the job is scheduled or running, and so carries the worker's queue. */
        fun pending(context: Context): Boolean =
            runCatching { context.getSystemService(JobScheduler::class.java)?.getPendingJob(JOB_ID) != null }
                .getOrDefault(false)

        /**
         * Schedules the job unless it is already scheduled or running: scheduling over a running
         * job would stop it. Returns whether the job now carries the work.
         */
        fun schedule(context: Context): Boolean {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return false
            if (pending(context)) return true
            val component = ComponentName(context, DeployJobService::class.java)
            // Expedited where Android has it (12+): it starts at once and is not a foreground
            // service, so the dataSync budget does not apply; once the app's expedited quota is
            // spent Android runs it as a regular job. Before Android 12 no foreground service
            // limit exists, so this is only the fallback for a refused start.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                submit(scheduler, JobInfo.Builder(JOB_ID, component).setExpedited(true))
            ) {
                return true
            }
            // A regular job needs some constraint; no latency is the least one.
            return submit(scheduler, JobInfo.Builder(JOB_ID, component).setMinimumLatency(0))
        }

        private fun submit(
            scheduler: JobScheduler,
            builder: JobInfo.Builder,
        ): Boolean =
            runCatching { scheduler.schedule(builder.build()) == JobScheduler.RESULT_SUCCESS }
                .onFailure { Log.w(TAG, "cannot schedule the deploy job", it) }
                .getOrDefault(false)
    }
}
