package app.songyan.ime.engine

import android.util.Log
import app.songyan.ime.deploy.DeployState
import app.songyan.ime.deploy.DeployStatusStore
import app.songyan.ime.keyboard.engine.EngineBackend
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.BuildStamp
import app.songyan.ime.storage.GenerationLease
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.InputSchemeSetup
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.LearningMerger
import app.songyan.ime.storage.PublicationRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/** What the input method can do right now. */
enum class EngineRuntimeState {
    /** A fresh install waits for the user to download an input scheme in settings. */
    NEEDS_SCHEME,

    /** No published generation yet; recovery deployment is requested. */
    NO_PUBLICATION,

    /** Deployment or engine start is in progress. */
    PREPARING,

    /** A prebuilt engine is running and can take input. */
    READY,

    /** Last attempt failed; the keyboard shows an honest error state. */
    FAILED,
}

/**
 * Owns the single IME-process engine: loads the published generation with a
 * prebuilt engine, keeps it across keyboard shows, and reloads it when a new
 * generation is published.
 *
 * All disk reads and engine work run on [ioDispatcher]; every callback is
 * delivered on [mainDispatcher] so the service can touch views directly. The
 * runtime owns its own scope, so callers only trigger work and observe state.
 *
 * Every start/reload decision is serialized on the process's [EngineOwnership] lock: a keyboard show and a
 * deploy-finished broadcast can arrive together, and two concurrent starts would
 * make the second engine creation fail on the native single-engine guard
 * ("another RimeEngine is active in this process") and report a spurious failure
 * while tearing down the engine that just started.
 */
class ImeEngineRuntime(
    private val storage: AppStorage,
    private val publisher: GenerationPublisher,
    private val deployStatus: DeployStatusStore,
    private val factory: PublishedEngineFactory,
    private val appName: String,
    private val appVersion: String,
    private val callbacks: Callbacks,
    /**
     * Requests a deployment that leaves a current generation active (first run, a stale or broken
     * generation); injected so this class never holds a `Context`.
     */
    private val requestFirstDeploy: () -> Unit,
    /** The stamp a current generation carries; null skips the check (tests without package info). */
    private val currentStamp: () -> BuildStamp? = { null },
    /** Runs before an engine for [PublicationRecord] is handed out (aligns the schema preference). */
    private val beforeEngine: suspend (PublicationRecord) -> Unit = {},
    /** Merges legacy learning data with an engine over [PublicationRecord]'s generation; null skips merging. */
    private val learningMerger: (PublicationRecord) -> LearningMerger? = { null },
    /** The process's engine ownership; shared with learning backup work that needs its own engine. */
    private val ownership: EngineOwnership = EngineOwnership(),
    /** Crash-loop breaker around engine starts; null skips it (tests that do not exercise it). */
    private val startGuard: EngineStartGuard? = null,
    /** Asks the deploy process to make the built-in source active (after a quarantine). */
    private val requestBuiltin: () -> Unit = {},
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
) : EngineOwnership.Holder {
    interface Callbacks {
        /** Called on the main thread. */
        fun onState(state: EngineRuntimeState)

        /** Called on the main thread when a backend becomes available. */
        fun onEngine(backend: EngineBackend)

        /** Called on the main thread before the current engine is replaced. */
        fun onEngineReleased()

        /**
         * Called on the main thread before [onEngineReleased]; returns once every key press the
         * keyboard already queued has run, so none is typed into (or dropped by) a released engine.
         */
        suspend fun awaitInputIdle() = Unit
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var handle: EngineHandle? = null

    @Volatile
    private var loadedGeneration: String? = null

    @Volatile
    private var closed = false

    private val lease = GenerationLease(storage)
    private var learningMigrated = false
    private var migrationAttemptedFor: String? = null

    /** The generation a stale-stamp rebuild was already requested for; asked once per generation. */
    private var redeployRequestedFor: String? = null

    /** The generation whose quarantine already asked for the built-in source; asked once per generation. */
    private var builtinRequestedFor: String? = null

    /** Whether the input view is on screen, where the engine must not change; written on the main thread. */
    @Volatile
    private var inputViewShown = false

    /** A newer generation is published but waits until the input view is gone. */
    @Volatile
    private var swapDeferred = false
    private var swapWhileShownOnce = false

    val isRunning: Boolean get() = handle != null

    init {
        ownership.register(this)
    }

    /** Starts or reuses the engine for the active publication. */
    fun ensureStarted() {
        reloadLater()
    }

    /** Cheap generation check; called when the keyboard becomes visible and when a deployment finished. */
    fun refreshIfChanged() {
        reloadLater()
    }

    /**
     * Main thread only. While the input view is shown a running engine is never replaced: the swap
     * would discard the composition under the user's fingers and hide the keys mid-word. A newer
     * generation published meanwhile is loaded as soon as the view is gone. With no engine running
     * nothing is deferred.
     */
    fun setInputViewShown(shown: Boolean) {
        inputViewShown = shown
        if (!shown && swapDeferred) reloadLater()
    }

    /**
     * Main thread only. The user asked for another package from the keyboard menu: the next swap
     * proceeds while the input view is shown, however long the package takes to get ready: the
     * keyboard already shows that it is preparing, and nothing can be typed under that notice.
     * One-shot; pass false to take it back when the switch did not happen.
     */
    fun allowNextSwapWhileShown(allow: Boolean = true) {
        swapWhileShownOnce = allow
    }

    /** True while a published generation waits for the input view to close; for tests. */
    val isSwapDeferred: Boolean get() = swapDeferred

    private fun reloadLater() {
        if (closed) return
        scope.launch {
            try {
                ownership.withLock { reload() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "engine start failed", e)
                withContext(mainDispatcher) { callbacks.onState(EngineRuntimeState.FAILED) }
            }
        }
    }

    /** Waits for any in-flight start, then starts only when the running engine is out of date. */
    private suspend fun reload() {
        if (closed) return
        val active = withContext(ioDispatcher) { publisher.readActive() }
        if (handle != null && active != null && active.generation == loadedGeneration) return
        startIfNeeded(active)
    }

    private suspend fun startIfNeeded(active: PublicationRecord?) {
        if (closed) return
        if (active == null) {
            val status = withContext(ioDispatcher) { deployStatus.read() }
            if (withContext(ioDispatcher) { InputSchemeSetup.needsDownload(storage) }) {
                withContext(mainDispatcher) { callbacks.onState(EngineRuntimeState.NEEDS_SCHEME) }
            } else if (status == DeployState.FAILED) {
                withContext(mainDispatcher) { callbacks.onState(EngineRuntimeState.FAILED) }
            } else {
                withContext(mainDispatcher) {
                    callbacks.onState(EngineRuntimeState.NO_PUBLICATION)
                    callbacks.onState(EngineRuntimeState.PREPARING)
                }
                requestFirstDeploy()
            }
            return
        }
        if (handle != null && active.generation == loadedGeneration) {
            withContext(mainDispatcher) { callbacks.onState(EngineRuntimeState.READY) }
            return
        }
        // Validate before releasing anything: an incomplete new generation must not cost the user
        // the engine that is still working.
        val complete = withContext(ioDispatcher) { publisher.isComplete(active) }
        val stale = withContext(ioDispatcher) { currentStamp()?.let { it != active.stamp } == true }
        if (stale || !complete) requestRebuildOnce(active)
        if (!complete) {
            Log.e(TAG, "published generation is incomplete: ${storage.generationDir(active.generation).absolutePath}")
            if (handle == null) withContext(mainDispatcher) { callbacks.onState(EngineRuntimeState.PREPARING) }
            return
        }
        // Decided on the main thread, where the input view is shown and hidden: once PREPARING is
        // rendered the keys are gone, so no press can start a composition the swap would lose.
        val swapping = handle != null
        val proceed =
            withContext(mainDispatcher) {
                if (swapping && inputViewShown && !swapWhileShownOnce) {
                    // Set here, not after the hop: [setInputViewShown] runs on this thread too, so a
                    // hide that follows always sees the deferral and reloads.
                    swapDeferred = true
                    false
                } else {
                    swapWhileShownOnce = false
                    callbacks.onState(EngineRuntimeState.PREPARING)
                    true
                }
            }
        if (!proceed) return
        swapDeferred = false
        val previous = loadedGeneration
        // Lease both while switching: the old one is still mapped until it is released.
        withContext(ioDispatcher) { runCatching { lease.hold(listOfNotNull(previous, active.generation)) } }
        releaseCurrent(notify = true)
        if (closed) return
        if (!admitStart(active)) return
        val created =
            try {
                openEngine(active, stale) ?: return
            } catch (e: CancellationException) {
                withContext(NonCancellable + ioDispatcher) { startGuard?.finished() }
                throw e
            } catch (e: Exception) {
                // An exception is not a crash: the process survived, so it does not count as one.
                withContext(ioDispatcher) { startGuard?.finished() }
                throw e
            }
        if (closed) {
            // Shutdown won the race while the engine was starting: never leave it running.
            runCatching { created.close() }
            // The start did finish; the scope may already be cancelled, so no dispatcher hop.
            runCatching { startGuard?.started() }
            return
        }
        handle = created
        loadedGeneration = active.generation
        withContext(ioDispatcher) { runCatching { lease.hold(listOf(active.generation)) } }
        withContext(mainDispatcher) {
            callbacks.onEngine(created.backend)
            callbacks.onState(EngineRuntimeState.READY)
        }
        // After the keyboard has its engine, so readiness never waits for this write.
        withContext(ioDispatcher) { startGuard?.started() }
    }

    /**
     * Everything a start runs while the crash marker is down: learning data recovery and merges
     * (they open engines too) and the engine itself. Null when a stale generation did not load.
     */
    private suspend fun openEngine(
        active: PublicationRecord,
        stale: Boolean,
    ): EngineHandle? {
        withContext(ioDispatcher) { LearningData.recoverInterruptedMerges(storage) }
        migrateLearningData(active)
        beforeEngine(active)
        val generationDir = storage.generationDir(active.generation)
        return try {
            factory.create(
                sharedDataDir = File(generationDir, "resources"),
                userDataDir = LearningData.directory(storage, active.sourceId),
                compiledDir = File(generationDir, "build"),
                appName = appName,
                appVersion = appVersion,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!stale) throw e
            // Tables built by an older librime may not load; the rebuild already requested
            // replaces them, so this is preparation, not a failure.
            Log.w(TAG, "stale generation did not load; waiting for the rebuild", e)
            withContext(ioDispatcher) { startGuard?.finished() }
            withContext(mainDispatcher) { callbacks.onState(EngineRuntimeState.PREPARING) }
            null
        }
    }

    /**
     * Asks [startGuard] whether [active] may start. A generation whose starts kept dying is not
     * started again: an imported source makes way for the built-in one, and a built-in generation
     * that still fails leaves the keyboard in its failed state until a new generation is published.
     */
    private suspend fun admitStart(active: PublicationRecord): Boolean {
        val guard = startGuard ?: return true
        return when (withContext(ioDispatcher) { guard.beforeStart(active) }) {
            EngineStartGuard.Decision.Start -> true
            EngineStartGuard.Decision.SwitchToBuiltin -> {
                if (builtinRequestedFor != active.generation) {
                    builtinRequestedFor = active.generation
                    requestBuiltin()
                }
                withContext(mainDispatcher) { callbacks.onState(EngineRuntimeState.PREPARING) }
                false
            }
            EngineStartGuard.Decision.Blocked -> {
                withContext(mainDispatcher) { callbacks.onState(EngineRuntimeState.FAILED) }
                false
            }
        }
    }

    /**
     * Merges the legacy learning directories while no engine of this process is open (the current
     * one was just released and the next is not created yet). A merge that failed is tried again
     * with the next generation or the next process start, at most once per generation.
     */
    private suspend fun migrateLearningData(active: PublicationRecord) {
        if (learningMigrated || migrationAttemptedFor == active.generation) return
        migrationAttemptedFor = active.generation
        learningMigrated =
            withContext(ioDispatcher) {
                LearningData.migrate(storage, active.sourceId, learningMerger(active))
            }
    }

    /** The keyboard shows its preparing state until [exclusiveUseEnded] starts the engine again. */
    override suspend fun releaseForExclusiveUse() {
        if (handle == null) return
        releaseCurrent(notify = true)
        withContext(mainDispatcher) { callbacks.onState(EngineRuntimeState.PREPARING) }
    }

    override fun exclusiveUseEnded() {
        reloadLater()
    }

    private fun requestRebuildOnce(active: PublicationRecord) {
        if (redeployRequestedFor == active.generation) return
        redeployRequestedFor = active.generation
        requestFirstDeploy()
    }

    private suspend fun releaseCurrent(notify: Boolean) {
        val current = handle ?: return
        if (notify) {
            withContext(mainDispatcher) {
                // Presses made just before the swap run on the engine they were made on.
                withTimeoutOrNull(INPUT_IDLE_TIMEOUT_MS) { callbacks.awaitInputIdle() }
            }
        }
        handle = null
        loadedGeneration = null
        swapDeferred = false
        if (notify) {
            withContext(mainDispatcher) { callbacks.onEngineReleased() }
        }
        runCatching { current.close() }.onFailure { Log.w(TAG, "engine close failed", it) }
    }

    /**
     * Stops the engine when the input method service is destroyed. Teardown runs
     * on the runtime's own scope and does not notify callbacks, so a destroyed
     * service is never touched.
     */
    fun shutdown() {
        if (closed) return
        closed = true
        ownership.unregister(this)
        scope.launch {
            try {
                ownership.withLock { releaseCurrent(notify = false) }
            } finally {
                scope.cancel()
            }
        }
    }

    /** Suspending variant for tests and orderly shutdown paths. */
    suspend fun close() {
        if (closed) return
        closed = true
        ownership.unregister(this)
        ownership.withLock { releaseCurrent(notify = false) }
    }

    companion object {
        private const val TAG = "ImeEngineRuntime"

        /** Longest wait for queued key presses before a swap; an engine call is far shorter. */
        private const val INPUT_IDLE_TIMEOUT_MS = 5_000L
    }
}
