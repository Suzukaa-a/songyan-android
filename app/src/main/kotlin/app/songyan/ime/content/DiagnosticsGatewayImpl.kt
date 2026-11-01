package app.songyan.ime.content

import android.content.Context
import android.os.Build
import app.songyan.ime.BuildConfig
import app.songyan.ime.deploy.DeployRequestStore
import app.songyan.ime.deploy.DeployStatusStore
import app.songyan.ime.diagnostics.DeleteKeyFact
import app.songyan.ime.diagnostics.DiagnosticFacts
import app.songyan.ime.diagnostics.DiagnosticReport
import app.songyan.ime.diagnostics.RecoveryFact
import app.songyan.ime.diagnostics.RequestFact
import app.songyan.ime.engine.EngineStartGuard
import app.songyan.ime.ime.BackspaceTrace
import app.songyan.ime.settings.state.DiagnosticsGateway
import app.songyan.ime.settings.state.EngineRecoveryNotice
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Collects the non-personal facts for [DiagnosticReport]; reads only files this app owns. */
internal class DiagnosticsGatewayImpl(
    context: Context,
    private val storage: AppStorage,
) : DiagnosticsGateway {
    private val appContext = context.applicationContext

    override val available: Boolean = true

    override suspend fun report(): String =
        withContext(Dispatchers.IO) {
            val active = GenerationPublisher(storage).readActive()
            val catalog = SourceCatalog(storage)
            val sources = catalog.list()
            val now = System.currentTimeMillis()
            DiagnosticReport.render(
                DiagnosticFacts(
                    appVersion = BuildConfig.VERSION_NAME,
                    buildType = BuildConfig.BUILD_TYPE,
                    androidRelease = Build.VERSION.RELEASE,
                    sdkInt = Build.VERSION.SDK_INT,
                    device = Build.MANUFACTURER + " " + Build.MODEL,
                    abis = Build.SUPPORTED_ABIS.toList(),
                    engineVersion = active?.stamp?.engine,
                    dataVersion = active?.stamp?.data,
                    activeKind = active?.let { sourceKind(it.sourceId) },
                    activeUsable = active?.let { GenerationPublisher(storage).isComplete(it) } == true,
                    activeSchemaCount = active?.schemaIds?.size ?: 0,
                    builtinSources = sources.count { it.kind == SourceKind.BUILTIN },
                    dictionarySources = sources.count { it.kind == SourceKind.DICTIONARY },
                    schemeSources = sources.count { it.kind == SourceKind.SCHEME },
                    damagedSources = catalog.damaged().size,
                    deployState = DeployStatusStore(storage).read().name,
                    freeStorageMb = storage.rimeRoot.usableSpace / (1024 * 1024),
                    recentRequests =
                        DeployRequestStore(storage).recent(RECENT_REQUESTS).map {
                            RequestFact(it.state, it.problem, minutesSince(now, it.updatedAtEpochMs))
                        },
                    engineRecoveries =
                        EngineStartGuard.quarantines(storage).take(RECENT_RECOVERIES).map {
                            RecoveryFact(
                                sourceKind = sourceKind(it.sourceId),
                                movedLearningData = it.movedLearningData,
                                switchedToBuiltin = it.switchedToBuiltin,
                                minutesAgo = minutesSince(now, it.timeEpochMs),
                            )
                        },
                    // The keyboard runs in this process, so its in-memory trace is read directly.
                    deleteKeyEvents =
                        BackspaceTrace.snapshot().map {
                            DeleteKeyFact(
                                event = it.event.name,
                                composing = it.composing,
                                cachedCaret = it.cachedCaret,
                                editorPackage = it.editorPackage,
                                msAgo = (now - it.timeEpochMs).coerceAtLeast(0),
                            )
                        },
                ),
            )
        }

    override suspend fun engineRecovery(): EngineRecoveryNotice? =
        withContext(Dispatchers.IO) {
            EngineStartGuard.quarantines(storage).firstOrNull()?.let {
                EngineRecoveryNotice(it.timeEpochMs, it.switchedToBuiltin, it.movedLearningData)
            }
        }

    /** The source's kind only: a scheme's id is derived from the package and stays out of reports. */
    private fun sourceKind(sourceId: String): String =
        when (sourceId) {
            SourceIds.BUILTIN -> "builtin"
            SourceIds.DICTIONARY -> "dictionary"
            else -> "scheme"
        }

    private fun minutesSince(
        now: Long,
        then: Long,
    ): Long = ((now - then) / 60_000).coerceAtLeast(0)

    private companion object {
        const val RECENT_REQUESTS = 10
        const val RECENT_RECOVERIES = 5
    }
}
