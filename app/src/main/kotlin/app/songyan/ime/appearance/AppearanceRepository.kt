package app.songyan.ime.appearance

import android.content.Context
import android.content.Intent
import app.songyan.ime.ime.KeyboardPalette
import app.songyan.ime.ime.palette.KeyboardAppearanceRegistry
import app.songyan.ime.ime.palette.KeyboardAppearanceSettings
import app.songyan.ime.ime.palette.KeyboardPalettes
import app.songyan.ime.prefs.LogAndContinue
import app.songyan.ime.settings.state.AppearanceSettingsHolder
import app.songyan.ime.settings.ui.theme.ThemeMode
import app.songyan.ime.settings.ui.theme.ThemePreferencesHolder
import app.songyan.ime.storage.AppStorage
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/** The keyboard palette for both variants, resolved once per appearance change. */
data class AppearancePalettes(
    val light: KeyboardPalette,
    val dark: KeyboardPalette,
)

/**
 * The app process' appearance: which appearance is applied, whether the keyboard follows the
 * wallpaper palette, and which light/dark variant to paint.
 *
 * The keyboard never reads appearance storage itself: every change is installed into
 * [KeyboardAppearanceRegistry], so a keyboard view built afterwards resolves the new palette without
 * touching disk. The settings screen reads and writes through [AppearanceScreenSettings] and
 * [AppearanceThemeSettings], which keep one stored value shared by the app and the keyboard.
 *
 * All file and resource work happens on an IO dispatcher; [create] is safe to call from
 * `Application.onCreate` and returns immediately.
 */
class AppearanceRepository internal constructor(
    private val context: Context,
    private val preferences: AppearancePreferences,
    private val controller: AppearanceStoreController,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + io + LogAndContinue)
    private val started = AtomicBoolean(false)
    private val mutex = Mutex()

    private val mutableState =
        MutableStateFlow(
            AppearanceState(
                document = AppearancePresets.androidDefault,
                dynamicColor = AppearancePreferences.defaultDynamicColor(),
            ),
        )

    /** The applied appearance and the wallpaper-palette and light/dark choices. */
    val state: StateFlow<AppearanceState> = mutableState.asStateFlow()

    private val mutableAppearances = MutableStateFlow(AppearancePresets.ALL_BUILT_IN)

    /** Every selectable appearance: the built-ins first, then the imported ones. */
    val appearances: StateFlow<List<AppearanceDocument>> = mutableAppearances.asStateFlow()

    private val mutablePalettes =
        MutableStateFlow(
            AppearancePalettes(
                light = KeyboardPalette.resolve(context, dark = false),
                dark = KeyboardPalette.resolve(context, dark = true),
            ),
        )

    /** The keyboard palette for both variants, resolved exactly as the keyboard resolves it. */
    val palettes: StateFlow<AppearancePalettes> = mutablePalettes.asStateFlow()

    /** Whether this device can paint the wallpaper palette at all. */
    val dynamicColorAvailable: Boolean get() = KeyboardPalettes.dynamicColorAvailable(context)

    init {
        // A keyboard view may be built before the first read completes; hand it the default
        // appearance immediately instead of leaving it without a palette source.
        installRegistry(state.value)
    }

    /** Loads the stored appearance and starts following the stored choices. */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            combine(
                // A corrupt preference store must not leave the keyboard without an appearance.
                preferences.dynamicColor.catch { emit(AppearancePreferences.defaultDynamicColor()) },
                preferences.themeMode.catch { emit(ThemeMode.SYSTEM) },
            ) { dynamicColor, themeMode -> dynamicColor to themeMode }
                .distinctUntilChanged()
                .collect { (dynamicColor, themeMode) -> publish(controller.read(dynamicColor, themeMode)) }
        }
    }

    /** Re-reads the stored appearance, e.g. after an import wrote a new document. */
    suspend fun refresh() {
        val current = state.value
        publish(withContext(io) { controller.read(current.dynamicColor, current.themeMode) })
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        preferences.setDynamicColor(enabled)
        publish(state.value.copy(dynamicColor = enabled))
    }

    /** Stores the light/dark choice; the keyboard picks it up on its next palette resolution. */
    suspend fun setThemeMode(mode: ThemeMode) {
        preferences.setThemeMode(mode)
        publish(state.value.copy(themeMode = mode))
    }

    /** Applies a stored appearance by id; throws [AppearanceError] when the id is unknown. */
    suspend fun select(id: String) {
        val current = state.value
        publish(withContext(io) { controller.select(id, current.dynamicColor, current.themeMode) })
    }

    /** Stores [bar] on the applied appearance (a user copy when it is a built-in) and applies it. */
    suspend fun editCandidateBar(
        bar: app.songyan.ime.candidate.CandidateBarDefinition,
        copySuffix: String,
    ) {
        val current = state.value
        val updated = withContext(io) { controller.editCandidateBar(current.document, bar, copySuffix) }
        publish(current.copy(document = updated))
    }

    /** Stores an imported appearance file as a user appearance; the list refreshes with it. */
    suspend fun import(text: String): AppearanceDocument {
        val document = withContext(io) { controller.import(text) }
        publish(state.value)
        return document
    }

    suspend fun export(id: String): String = withContext(io) { controller.export(id) }

    /** Removes a user appearance; the default appearance takes over when it was the applied one. */
    suspend fun delete(id: String) {
        withContext(io) { controller.remove(id) }
        val current = state.value
        if (current.document.id == id) {
            select(AppearancePresets.androidDefault.id)
        } else {
            publish(current)
        }
    }

    /** Reads the stored appearance list; blocking, used by the settings bridge on its own scope. */
    suspend fun loadAppearances(): List<AppearanceDocument> = withContext(io) { controller.list() }

    /** The palette the keyboard paints with for [dark], resolved exactly as the keyboard resolves it. */
    fun keyboardPalette(dark: Boolean): KeyboardPalette = KeyboardPalette.resolve(context, dark)

    private suspend fun publish(next: AppearanceState) {
        mutex.withLock {
            mutableState.value = next
            installRegistry(next)
            mutablePalettes.value =
                withContext(io) {
                    AppearancePalettes(
                        light = KeyboardPalette.resolve(context, dark = false),
                        dark = KeyboardPalette.resolve(context, dark = true),
                    )
                }
            // A user file that fails to decode must not blank the list the screen already shows.
            runCatching { withContext(io) { controller.list() } }.getOrNull()?.let { mutableAppearances.value = it }
        }
        context.sendBroadcast(Intent(ACTION_APPEARANCE_CHANGED).setPackage(context.packageName))
    }

    private fun installRegistry(next: AppearanceState) {
        KeyboardAppearanceRegistry.install(
            KeyboardAppearanceSettings(
                document = next.document,
                dynamicColor = next.dynamicColor,
                forcedDark =
                    when (next.themeMode) {
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                        ThemeMode.SYSTEM -> null
                    },
            ),
        )
    }

    companion object {
        /** Broadcast after every appearance change, so a shown keyboard can repaint. */
        const val ACTION_APPEARANCE_CHANGED: String = "app.songyan.ime.action.APPEARANCE_CHANGED"

        /**
         * Builds and starts the repository, and installs the settings bridges. The application graph
         * owns the instance as a field of [android.app.Application], so no Context is ever kept in a
         * static field; only the application context is captured here.
         */
        fun create(
            context: Context,
            /**
             * Passed in rather than derived here: resolving Context.filesDir reads the file system,
             * and this is called from Application.onCreate on the main thread.
             */
            storage: AppStorage,
        ): AppearanceRepository {
            val appContext = context.applicationContext
            val directory = storage.appearanceDir
            val repository =
                AppearanceRepository(
                    context = appContext,
                    preferences = AppearancePreferences(appContext),
                    controller = AppearanceStoreController(AppearanceStore(directory.absolutePath)),
                )
            val settingsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + LogAndContinue)
            AppearanceSettingsHolder.install(AppearanceScreenSettings(repository, settingsScope, appContext))
            ThemePreferencesHolder.install(AppearanceThemeSettings(repository, settingsScope))
            repository.start()
            return repository
        }
    }
}
