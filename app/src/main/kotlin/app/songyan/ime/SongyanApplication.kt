package app.songyan.ime

import android.app.Application
import android.os.StrictMode
import app.songyan.ime.appearance.AppearanceRepository
import app.songyan.ime.content.DiagnosticsGatewayImpl
import app.songyan.ime.content.DictionaryImportGatewayImpl
import app.songyan.ime.content.EngineFeaturesGatewayImpl
import app.songyan.ime.content.EngineOptionsGatewayImpl
import app.songyan.ime.content.LearningBackupGatewayImpl
import app.songyan.ime.content.LinkDownloadGatewayImpl
import app.songyan.ime.content.SchemeImportGatewayImpl
import app.songyan.ime.content.StoredSourcesGatewayImpl
import app.songyan.ime.deploy.RimeDeployRunner
import app.songyan.ime.engine.EngineOwnership
import app.songyan.ime.engine.PublishedEngineFactory
import app.songyan.ime.learning.LearningBackupService
import app.songyan.ime.learning.LearningSnapshots
import app.songyan.ime.settings.state.DiagnosticsHolder
import app.songyan.ime.settings.state.DictionaryImportHolder
import app.songyan.ime.settings.state.EngineFeaturesHolder
import app.songyan.ime.settings.state.EngineOptionsHolder
import app.songyan.ime.settings.state.LearningBackupHolder
import app.songyan.ime.settings.state.LinkDownloadHolder
import app.songyan.ime.settings.state.RimeConfigHolder
import app.songyan.ime.settings.state.SchemeImportHolder
import app.songyan.ime.settings.state.SettingsAppStateHolder
import app.songyan.ime.settings.state.SettingsPreferencesHolder
import app.songyan.ime.settings.state.StoredSourcesHolder
import app.songyan.ime.state.AppSettingsPreferences
import app.songyan.ime.state.AppSettingsStateSource
import app.songyan.ime.state.localizedContext
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.userconfig.RimeConfigGatewayImpl

class SongyanApplication : Application() {
    lateinit var storage: AppStorage
        private set

    /** librime's `app_name`: must start with "rime." so old logs are recycled. */
    val rimeAppName: String = "rime.songyan"

    /** Applied keyboard appearance; owns loading so no Context ever lives in a static field. */
    val appearanceRepository: AppearanceRepository by lazy { AppearanceRepository.create(this, storage) }

    /** The layout catalog: settings edit it, the keyboard draws from it. */
    val layoutRepository: app.songyan.ime.layout.LayoutRepository by lazy {
        app.songyan.ime.layout.LayoutRepository
            .create(this, storage)
    }

    /** Preferences shared by the keyboard and settings screen. */
    val keyboardPreferences: app.songyan.ime.prefs.KeyboardPreferences by lazy {
        app.songyan.ime.prefs
            .KeyboardPreferences(this)
    }

    /**
     * Full-deploy runner used by the :deploy process. Installed during
     * [onCreate] once the engine library is linked into the app.
     */
    var rimeDeployRunner: RimeDeployRunner? = null
        private set

    /**
     * Who may have a librime engine open in this (the input method's) process: the keyboard's
     * runtime, or learning-backup work that sets the keyboard's engine aside meanwhile.
     */
    val engineOwnership: EngineOwnership = EngineOwnership()

    /** Learning backup, import and merge; runs in this process with [engineOwnership]. */
    val learningBackupService: LearningBackupService by lazy {
        LearningBackupService(
            storage = storage,
            publisher = GenerationPublisher(storage),
            ownership = engineOwnership,
            engine =
                app.songyan.ime.rime.RimeBindings
                    .learningEngine(rimeAppName, BuildConfig.VERSION_NAME),
            sourceName = ::sourceDisplayName,
        )
    }

    /** Rotating automatic copies of the learning data; the keyboard takes one at a quiet moment. */
    val learningSnapshots: LearningSnapshots by lazy { LearningSnapshots(storage, learningBackupService) }

    /** IME-process engine factory for the published generation; null until bindings exist. */
    var publishedEngineFactory: PublishedEngineFactory? = null
        private set

    override fun onCreate() {
        super.onCreate()
        // Directory creation happens on the IO paths that own each directory (deploy,
        // publication, repositories); doing it here would write to disk on the main thread.
        storage = AppStorage(filesDir)
        rimeDeployRunner = createRimeDeployRunner()
        publishedEngineFactory = createPublishedEngineFactory()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy
                    .Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
            StrictMode.setVmPolicy(
                StrictMode.VmPolicy
                    .Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .penaltyLog()
                    .build(),
            )
        }
        // AndroidX DataStore is not multi-process safe and the :deploy process needs neither the
        // appearance nor the settings graph, so only the main process builds it. The appearance
        // repository is touched here so the asynchronous load starts before the IME service exists.
        if (isMainProcess()) {
            appearanceRepository
            layoutRepository
            SettingsAppStateHolder.install(AppSettingsStateSource(this, storage))
            SettingsPreferencesHolder.install(AppSettingsPreferences(this))
            // Imports and source switches are settings actions only; the work itself (including any
            // download) runs in the :deploy process.
            DictionaryImportHolder.install(DictionaryImportGatewayImpl(this, storage))
            SchemeImportHolder.install(
                SchemeImportGatewayImpl(this, storage, learningService = { learningBackupService }),
            )
            StoredSourcesHolder.install(StoredSourcesGatewayImpl(this, storage))
            EngineFeaturesHolder.install(EngineFeaturesGatewayImpl(this, storage))
            EngineOptionsHolder.install(EngineOptionsGatewayImpl(storage))
            LinkDownloadHolder.install(LinkDownloadGatewayImpl(this, storage))
            RimeConfigHolder.install(RimeConfigGatewayImpl(this, storage))
            DiagnosticsHolder.install(DiagnosticsGatewayImpl(this, storage))
            LearningBackupHolder.install(
                LearningBackupGatewayImpl(
                    this,
                    learningBackupService,
                    GenerationPublisher(storage),
                    ::sourceDisplayName,
                    learningSnapshots,
                ),
            )
        }
    }

    /** The name settings shows for a stored source; read from its manifest, so call off the main thread. */
    private fun sourceDisplayName(sourceId: String): String {
        val manifest = SourceCatalog(storage).read(sourceId)
        return when {
            manifest?.kind == SourceKind.BUILTIN || sourceId == SourceIds.BUILTIN ->
                localizedContext(this).getString(R.string.source_builtin_name)
            manifest != null && manifest.displayName.isNotBlank() -> manifest.displayName
            else -> manifest?.schemes?.firstOrNull()?.schemaId ?: sourceId
        }
    }

    /** Whether this process is the app's main process (the one that hosts the IME and the UI). */
    private fun isMainProcess(): Boolean = currentProcessName() == packageName

    private fun currentProcessName(): String = getProcessName()

    /**
     * Returns null until the native engine is part of the build; the deploy
     * service reports the failure instead of crashing the process.
     */
    private fun createRimeDeployRunner(): RimeDeployRunner? =
        app.songyan.ime.rime.RimeBindings
            .deployRunner()

    /** Wired to the prebuilt engine once :core:rime is linkable in this build. */
    private fun createPublishedEngineFactory(): PublishedEngineFactory? =
        app.songyan.ime.rime.RimeBindings
            .engineFactory(storage.optionsDir)
}
