// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.content.Context
import app.songyan.ime.addon.AddonHandshake
import app.songyan.ime.addon.AddonHostInfo
import app.songyan.ime.addon.AddonProtocol
import app.songyan.ime.addon.AddonToolDescriptor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The extension kinds the keyboard records for the 扩展 page's "last call" line. */
internal object AddonCallKinds {
    const val TEXT: String = "text"
    const val VOICE: String = "voice"
    const val PANEL: String = "panel"
}

/** One installed extension with everything the app knows about it right now. */
internal data class AddonEntry(
    val facts: AddonPackageFacts,
    val enabled: Boolean,
    /** Last answered handshake; null until one succeeded (or comes from the cache at process start). */
    val handshake: AddonHandshake?,
    /** Why the last negotiation failed; null when it answered. */
    val negotiationFailure: String?,
    val lastCall: StoredAddonCall?,
) {
    val packageName: String get() = facts.packageName

    val isCompatible: Boolean get() = handshake?.isCompatible == true

    val tools: List<AddonToolDescriptor> get() = handshake?.tools.orEmpty()
}

/**
 * The app's view of the installed extensions. Discovery and the enabled switch are cheap reads;
 * a handshake binds to the extension's service, so it only happens on the 扩展 page's scans (and
 * whenever the user enables one). The keyboard's catalog reads this in-memory snapshot instead of
 * binding anything; a fresh process starts from the cached handshakes in [AddonStore].
 */
internal class AddonRegistry(
    private val store: AddonStore,
    private val discovery: AddonDiscovery,
    private val negotiator: AddonNegotiator,
    private val hostInfo: () -> AddonHostInfo,
    private val scope: CoroutineScope,
    /** The state file is read and written here; callers may be on the main thread. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    data class State(
        val loading: Boolean = true,
        val entries: List<AddonEntry> = emptyList(),
    )

    private val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable.asStateFlow()

    private val mutex = Mutex()
    private var warmJob: Job? = null

    /** Loads the cached handshakes in the background; call once per process. */
    fun warm() {
        if (warmJob?.isActive == true) return
        warmJob = scope.launch { runCatching { scan(negotiate = false) } }
    }

    /**
     * Reads the installed extensions. With [negotiate] every extension is bound and asked for its
     * handshake (settings page); without it the cached handshake from the previous run is used, so
     * a keyboard start binds nothing.
     */
    suspend fun scan(negotiate: Boolean = true): List<AddonEntry> =
        mutex.withLock {
            val stored = withContext(ioDispatcher) { store.read() }
            val discovered = discovery.discover()
            val previous = mutable.value.entries.associateBy { it.packageName }
            val entries =
                discovered.map { facts ->
                    val known = previous[facts.packageName]
                    var handshake = known?.handshake ?: cachedHandshake(facts.packageName, stored)
                    var failure = known?.negotiationFailure
                    if (negotiate) {
                        when (val negotiation = negotiator.negotiate(facts, hostInfo())) {
                            is AddonNegotiation.Answered -> {
                                handshake = negotiation.handshake
                                failure = null
                            }
                            is AddonNegotiation.Unavailable -> {
                                handshake = null
                                failure = negotiation.reason
                            }
                        }
                    }
                    AddonEntry(
                        facts = facts,
                        enabled = facts.packageName in stored.enabled,
                        handshake = handshake,
                        negotiationFailure = failure,
                        lastCall = stored.calls[facts.packageName],
                    )
                }
            if (negotiate) {
                // A cache that cannot be written only costs a bind on the next start.
                withContext(ioDispatcher) {
                    store.write(
                        stored.copy(
                            cached =
                                entries
                                    .mapNotNull { entry ->
                                        val compatible =
                                            entry.handshake?.takeIf { it.isCompatible } ?: return@mapNotNull null
                                        entry.packageName to
                                            StoredAddonTools(
                                                tools = compatible.tools.map { StoredAddonTool(it.id, it.title) },
                                                supportsPanel = compatible.supportsPanel,
                                                supportsVoice = compatible.supportsVoice,
                                            )
                                    }.toMap(),
                        ),
                    )
                }
            }
            mutable.value = State(loading = false, entries = entries)
            entries
        }

    /**
     * Turns one extension on or off and persists the switch. False when the state file could not be
     * written: the in-memory state is left as it was, so the page can roll its switch back.
     */
    suspend fun setEnabled(
        packageName: String,
        enabled: Boolean,
    ): Boolean =
        mutex.withLock {
            val stored = withContext(ioDispatcher) { store.read() }
            val written =
                withContext(ioDispatcher) {
                    store.write(
                        stored.copy(
                            enabled = if (enabled) stored.enabled + packageName else stored.enabled - packageName,
                        ),
                    )
                }
            if (!written) return@withLock false
            mutable.value =
                mutable.value.copy(
                    entries =
                        mutable.value.entries.map {
                            if (it.packageName == packageName) it.copy(enabled = enabled) else it
                        },
                )
            true
        }

    /** The in-memory snapshot; no IO, no binding. The keyboard toolbox reads this per render. */
    fun entries(): List<AddonEntry> = mutable.value.entries

    fun enabledCompatible(): List<AddonEntry> = entries().filter { it.enabled && it.isCompatible }

    /** Records one finished call for the 扩展 page; persisted on the IO scope. */
    fun recordCall(
        packageName: String,
        kind: String,
        durationMs: Long,
        error: String?,
    ) {
        val call = StoredAddonCall(kind, System.currentTimeMillis(), durationMs, error)
        mutable.value =
            mutable.value.copy(
                entries =
                    mutable.value.entries.map {
                        if (it.packageName == packageName) it.copy(lastCall = call) else it
                    },
            )
        scope.launch(ioDispatcher) {
            mutex.withLock {
                val stored = store.read()
                store.write(stored.copy(calls = stored.calls + (packageName to call)))
            }
        }
    }

    private fun cachedHandshake(
        packageName: String,
        stored: StoredAddonState,
    ): AddonHandshake? {
        val cached = stored.cached[packageName] ?: return null
        return AddonHandshake(
            protocolVersion = AddonProtocol.VERSION,
            tools = cached.tools.map { AddonToolDescriptor(it.id, it.title) },
            supportsPanel = cached.supportsPanel,
            supportsVoice = cached.supportsVoice,
        )
    }
}

/** Process-wide registry; installed by the application in the main process. */
internal object AddonRegistryHolder {
    @Volatile
    private var installed: AddonRegistry? = null

    val current: AddonRegistry?
        get() = installed

    fun install(registry: AddonRegistry) {
        installed = registry
    }
}

/** Builds the production registry: real package discovery and a real bind-and-negotiate client. */
internal fun createAddonRegistry(context: Context): AddonRegistry {
    val appContext = context.applicationContext
    return AddonRegistry(
        store = AddonStore(File(appContext.filesDir, "addons")),
        discovery = AndroidAddonDiscovery(appContext),
        negotiator = AddonHostNegotiator(appContext),
        hostInfo = { AddonHostInfo.forHost(appContext) },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        ioDispatcher = Dispatchers.IO,
    )
}
