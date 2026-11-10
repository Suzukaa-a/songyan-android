package app.songyan.ime.state

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import app.songyan.ime.R
import app.songyan.ime.content.ImportedDictionaryMetadataReader
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.deploy.DeployState
import app.songyan.ime.deploy.DeployStatusStore
import app.songyan.ime.ime.KeyboardResourceSets
import app.songyan.ime.keyboard.engine.EncodingAdapterRegistry
import app.songyan.ime.keyboard.engine.InputLayoutPolicy
import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.packages.SchemeGrammarRequirements
import app.songyan.ime.prefs.KeyboardPreferences
import app.songyan.ime.prefs.LogAndContinue
import app.songyan.ime.prefs.reconcileWith
import app.songyan.ime.rime.SchemaOptionCapabilities
import app.songyan.ime.settings.state.AppKeyboardInfo
import app.songyan.ime.settings.state.EngineStatus
import app.songyan.ime.settings.state.ImportedDictionaryInfo
import app.songyan.ime.settings.state.InstalledScheme
import app.songyan.ime.settings.state.LayoutLibraryHolder
import app.songyan.ime.settings.state.SettingsAppState
import app.songyan.ime.settings.state.SettingsAppStateSource
import app.songyan.ime.settings.state.StoredSourceInfo
import app.songyan.ime.settings.state.StoredSourceKind
import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.EngineFeatureStore
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.PublicationRecord
import app.songyan.ime.storage.SourceCatalog
import app.songyan.ime.storage.SourceIds
import app.songyan.ime.storage.SourceKind
import app.songyan.ime.storage.SourceManifest
import app.songyan.ime.storage.SwitchTargets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.net.URI

/**
 * Maps the app's publication, deployment and preference state onto the settings
 * UI model. Reads happen on a background dispatcher, so [refresh] is safe from
 * the main thread and StrictMode stays clean.
 */
class AppSettingsStateSource(
    context: Context,
    /**
     * Passed in rather than derived from the context: resolving Context.filesDir reads the file
     * system, and this source is constructed from Application.onCreate on the main thread.
     */
    private val storage: AppStorage,
) : SettingsAppStateSource {
    private val appContext = context.applicationContext

    /**
     * Content context with the app locale applied. The Application context follows the
     * system locale, so settings would otherwise show English names inside a Chinese UI.
     */
    private val displayContext: Context = localizedContext(appContext)
    private val publisher = GenerationPublisher(storage)
    private val deployStatus = DeployStatusStore(storage)
    private val preferences = KeyboardPreferences(appContext)
    private val catalog = SourceCatalog(storage)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + LogAndContinue)
    private val mutable = MutableStateFlow(SettingsAppState())
    override val state: StateFlow<SettingsAppState> = mutable.asStateFlow()

    /**
     * Lives as long as the process, like the source itself: the deploy process announces every
     * finished deploy, and an import or removal must show its outcome without leaving the screen.
     */
    private val deployReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action == DeployContract.ACTION_DEPLOY_FINISHED) refresh()
            }
        }

    init {
        ContextCompat.registerReceiver(
            appContext,
            deployReceiver,
            IntentFilter(DeployContract.ACTION_DEPLOY_FINISHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    override fun refresh() {
        scope.launch {
            mutable.value = read()
        }
    }

    private suspend fun read(): SettingsAppState {
        val active = publisher.readActive()
        val usable = active?.takeIf { publisher.isComplete(it) }
        // The preference follows active.json; align it before showing which one is in use.
        usable?.let { preferences.reconcileWith(it) }
        val activeSchemaId = preferences.activeSchemaId.first()
        val stored = catalog.list()
        // A source that lists fine but lost files its manifest names is as unusable as one without manifest.
        val damaged = catalog.damaged()
        val schemes = usable?.let { schemesOf(it, stored) }.orEmpty()
        val deployState = deployStatus.read()
        val engine =
            when {
                // Ready only when the IME could actually start this generation: the record alone is
                // not proof (an interrupted publish leaves a generation directory behind).
                usable != null -> EngineStatus.Ready
                deployState == DeployState.PREPARING -> EngineStatus.Preparing(percent = 0)
                deployState == DeployState.FAILED -> EngineStatus.Failed
                active != null -> EngineStatus.Failed
                else -> EngineStatus.Unknown
            }
        val inUseSchemeId = schemes.firstOrNull { it.id == activeSchemaId }?.id ?: schemes.firstOrNull()?.id
        val inUseScheme = schemes.firstOrNull { it.id == inUseSchemeId }
        val layoutName =
            currentLayoutName(
                inputMethodId = inUseSchemeId,
                shapeId = preferences.layoutId.first(),
                family = inUseScheme?.family,
            )
        return SettingsAppState(
            engine = engine,
            sourcesLoaded = true,
            schemes = schemes,
            inUseSchemeId = inUseSchemeId,
            keyboards =
                listOf(
                    AppKeyboardInfo(
                        id = DEFAULT_KEYBOARD_ID,
                        inputMethod =
                            inUseScheme?.name ?: displayContext.getString(R.string.keyboard_input_method_pinyin),
                        layout = layoutName,
                    ),
                ),
            currentKeyboardId = DEFAULT_KEYBOARD_ID,
            importedDictionary = importedDictionary(stored, usable),
            stackedDictionaries = stackedDictionaries(stored, usable),
            sources =
                stored
                    .filter { it.kind != SourceKind.BUILTIN || active?.sourceId == it.sourceId }
                    .map { sourceInfo(it, if (it.kind == SourceKind.BUILTIN) active else usable, activeSchemaId) } +
                    damaged.map(::damagedInfo),
            otherSources =
                SwitchTargets
                    .of(stored, active?.sourceId)
                    .map { sourceInfo(it, usable, activeSchemaId) }
                    .filter { it.usable },
        )
    }

    /**
     * The input methods the active generation offers: only those are loadable, so offering any
     * other row would be a dead option. Names come from the source's manifest, never from the
     * tree's files, which also hold generated companions.
     */
    private fun schemesOf(
        active: PublicationRecord,
        stored: List<SourceManifest>,
    ): List<InstalledScheme> {
        val manifest = stored.firstOrNull { it.sourceId == active.sourceId }
        val ids = active.schemaIds.ifEmpty { listOf(active.schemaId) }
        val build = File(storage.generationDir(active.generation), "build")
        val adapters = EncodingAdapterRegistry.read(build, ids.toSet())
        // The deployed schema yaml carries the declared speller alphabet: the fact that places an
        // input method the language table cannot name by its id. Read here on the IO dispatcher,
        // once per refresh, so neither the keyboard nor layout selection ever asks again.
        val schemas =
            ids.map {
                KeyboardSchemaDescriptor(it, it, SchemaOptionCapabilities.spellerAlphabet(build, it))
            }
        val resources = KeyboardResourceSets.forEngineSchemas(schemas, adapters)
        return schemesNamed(manifest, ids).map { scheme ->
            scheme.copy(
                supportedLayouts =
                    KeyboardLayoutKind.entries
                        .filter { resources != null && resources.incompatibilityReason(it, scheme.id) == null }
                        .map { it.wire }
                        .toSet(),
                family = resources?.familyOf(scheme.id) ?: InputSchemeFamily.GENERIC,
            )
        }
    }

    /**
     * The name of the layout the keyboard in use draws: a physical board this build words by id, the
     * user's own layout under the name it was saved with, else the shape's existing name. The saved
     * pairing is read from the catalog the keyboard itself reads, never guessed from the UI language.
     */
    private fun currentLayoutName(
        inputMethodId: String?,
        shapeId: String,
        family: InputSchemeFamily?,
    ): String {
        val shape = LayoutInput.Builtin.fromWire(shapeId)
        val registry = LayoutLibraryHolder.current.registry.value
        val definition =
            if (inputMethodId != null && shape != null && registry != null) {
                val families =
                    family?.let { LayoutFamilyContext.of(inputMethodId to it) } ?: LayoutFamilyContext.UNKNOWN
                registry.resolvedLayout(
                    registry.composition(KeyboardCompositionKey(inputMethodId, shape), families).mainLayoutID,
                )
            } else {
                null
            }
        if (definition != null) {
            // An edited bundled board is the user's own; its saved name wins over the shipped one.
            if (definition != BuiltinLayouts.byId(definition.id)) return definition.name
            knownLayoutNameRes(definition.id)?.let { return displayContext.getString(it) }
        }
        return displayContext.getString(shapeNameRes(shapeId))
    }

    /** The resource that words a bundled physical board by id; null keeps the name the caller has. */
    private fun knownLayoutNameRes(layoutId: String?): Int? =
        when (layoutId) {
            InputLayoutPolicy.ZHUYIN_LAYOUT -> R.string.keyboard_layout_zhuyin41
            InputLayoutPolicy.CANGJIE_LAYOUT -> R.string.keyboard_layout_cangjie26
            InputLayoutPolicy.ROMAJI_LAYOUT -> R.string.keyboard_layout_romaji26
            InputLayoutPolicy.KANA_LAYOUT -> R.string.keyboard_layout_kana12
            else -> null
        }

    private fun shapeNameRes(shapeId: String): Int =
        when (shapeId) {
            KeyboardLayoutKind.MERGED14.wire -> R.string.keyboard_layout_14
            KeyboardLayoutKind.MERGED18.wire -> R.string.keyboard_layout_18
            KeyboardLayoutKind.PHONE9.wire -> R.string.keyboard_layout_9
            else -> R.string.keyboard_layout_26
        }

    private fun schemesNamed(
        manifest: SourceManifest?,
        ids: List<String>,
    ): List<InstalledScheme> =
        ids.map { id ->
            val builtinName = BUILTIN_SCHEME_NAMES[id]
            val name =
                when {
                    builtinName != null && manifest?.kind != SourceKind.SCHEME -> displayContext.getString(builtinName)
                    else -> manifest?.schemes?.firstOrNull { it.schemaId == id }?.name ?: id
                }
            InstalledScheme(id = id, name = name)
        }

    private fun sourceInfo(
        manifest: SourceManifest,
        active: PublicationRecord?,
        activeSchemaId: String?,
    ): StoredSourceInfo {
        val schemes = schemesNamed(manifest, manifest.schemaIds)
        val inUse = active?.sourceId == manifest.sourceId
        return StoredSourceInfo(
            id = manifest.sourceId,
            name =
                when {
                    manifest.kind == SourceKind.BUILTIN -> displayContext.getString(R.string.source_builtin_name)
                    manifest.displayName.isNotBlank() -> manifest.displayName
                    else -> manifest.primarySchemaId
                },
            kind =
                when (manifest.kind) {
                    SourceKind.BUILTIN -> StoredSourceKind.BUILTIN
                    SourceKind.DICTIONARY -> StoredSourceKind.DICTIONARY
                    SourceKind.SCHEME -> StoredSourceKind.SCHEME
                },
            inUse = inUse,
            status = statusOf(manifest, active),
            updatable = manifest.kind == SourceKind.SCHEME && manifest.origin != null,
            originHost = manifest.origin?.let { runCatching { URI(it).host }.getOrNull() },
            schemes = schemes,
            requiredModels =
                if (manifest.kind == SourceKind.SCHEME && catalog.isIntact(manifest)) {
                    missingGrammarModels(storage, storage.sourceDir(manifest.sourceId))
                } else {
                    emptyList()
                },
            inUseSchemeId =
                if (inUse) {
                    schemes
                        .firstOrNull {
                            it.id == activeSchemaId
                        }?.id ?: schemes.firstOrNull()?.id
                } else {
                    null
                },
        )
    }

    private fun statusOf(
        manifest: SourceManifest,
        active: PublicationRecord?,
    ): StoredSourceStatus =
        storedSourceStatus(
            kind = manifest.kind,
            intact = catalog.isIntact(manifest),
            storedRevision = manifest.revision,
            publishedRevision = active?.takeIf { it.sourceId == manifest.sourceId }?.revision,
        )

    private fun damagedInfo(sourceId: String): StoredSourceInfo =
        StoredSourceInfo(
            id = sourceId,
            name = displayContext.getString(R.string.source_damaged_name),
            kind = StoredSourceKind.SCHEME,
            inUse = false,
            status = StoredSourceStatus.DAMAGED,
        )

    /** The imported dictionary card; [ImportedDictionaryInfo.inUse] follows the published generation. */
    private fun importedDictionary(
        stored: List<SourceManifest>,
        active: PublicationRecord?,
    ): ImportedDictionaryInfo? {
        val manifest = stored.firstOrNull { it.sourceId == SourceIds.DICTIONARY } ?: return null
        return ImportedDictionaryInfo(
            name = manifest.displayName,
            entryCount = manifest.entryCount,
            inUse = active?.sourceId == SourceIds.DICTIONARY,
        )
    }

    /** Dictionaries stacked onto stored scheme packages, in the catalog's order. */
    private fun stackedDictionaries(
        stored: List<SourceManifest>,
        active: PublicationRecord?,
    ): List<ImportedDictionaryInfo> =
        stored
            .filter { it.kind == SourceKind.SCHEME }
            .mapNotNull { manifest ->
                val metadata =
                    ImportedDictionaryMetadataReader.read(storage.stackedDictionaryDir(manifest.sourceId))
                        ?: return@mapNotNull null
                ImportedDictionaryInfo(
                    name = metadata.displayName,
                    entryCount = metadata.entryCount,
                    inUse = active?.sourceId == manifest.sourceId,
                    targetSourceId = manifest.sourceId,
                    targetName = manifest.displayName.ifBlank { manifest.primarySchemaId },
                )
            }

    companion object {
        private const val DEFAULT_KEYBOARD_ID = "default"
    }
}

/** Localized names of the built-in package's input methods, whose manifest names them by id. */
internal val BUILTIN_SCHEME_NAMES =
    mapOf(
        KeyboardPreferences.DEFAULT_SCHEMA_ID to R.string.scheme_builtin_quanpin,
        "songyan_flypy" to R.string.scheme_builtin_flypy,
    )

internal fun missingGrammarModels(
    storage: AppStorage,
    source: File,
): List<String> {
    val features = EngineFeatureStore(storage)
    val settings = features.read()
    val storedModels = settings.grammarModels.filter { features.grammarModel(settings, it.name) != null }
    return SchemeGrammarRequirements
        .missingModels(source)
        .filterNot { path -> storedModels.any { it.displayName == path || it.name == path } }
}

/**
 * Where a stored source stands. [publishedRevision] is the revision the keyboard's generation was
 * built from (null: this source is not the one in use); it starts with the stored revision when the
 * generation was built from the files that are stored now, so any other value means the files are
 * newer and "use new version" has to rebuild.
 */
internal fun storedSourceStatus(
    kind: SourceKind,
    intact: Boolean,
    storedRevision: String,
    publishedRevision: String?,
): StoredSourceStatus =
    when {
        kind != SourceKind.BUILTIN && !intact -> StoredSourceStatus.DAMAGED
        publishedRevision == null -> StoredSourceStatus.NOT_IN_USE
        kind != SourceKind.BUILTIN &&
            publishedRevision != storedRevision &&
            !publishedRevision.startsWith("$storedRevision+") -> StoredSourceStatus.UPDATE_AVAILABLE
        else -> StoredSourceStatus.IN_USE
    }
