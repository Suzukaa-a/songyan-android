package app.songyan.ime.appearance

import android.content.Context
import android.net.Uri
import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.settings.R
import app.songyan.ime.settings.state.AppearanceImportOutcome
import app.songyan.ime.settings.state.AppearanceOption
import app.songyan.ime.settings.state.AppearanceSettingsSource
import app.songyan.ime.settings.state.AppearanceSettingsState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * The appearance screen's read/write bridge onto the repository.
 *
 * Picking a specific colour scheme also turns the wallpaper palette off: the user asked for that
 * skin's colours. Key shape and type sizes keep coming from the skin either way.
 */
class AppearanceScreenSettings(
    private val repository: AppearanceRepository,
    private val scope: CoroutineScope,
    private val context: Context,
) : AppearanceSettingsSource {
    private val mutable = MutableStateFlow(AppearanceSettingsState())
    override val state: StateFlow<AppearanceSettingsState> = mutable.asStateFlow()

    init {
        // Rebuild the list whenever the applied appearance or a choice changes.
        scope.launch { repository.state.collect { load() } }
    }

    override fun refresh() {
        scope.launch {
            runCatching { repository.refresh() }
            load()
        }
    }

    override suspend fun select(id: String): Boolean =
        runCatching {
            if (repository.state.value.dynamicColor) repository.setDynamicColor(false)
            repository.select(id)
            load()
        }.isSuccess

    override suspend fun setDynamicColor(enabled: Boolean): Boolean =
        runCatching {
            repository.setDynamicColor(enabled)
            load()
        }.isSuccess

    override suspend fun updateCandidateBar(bar: CandidateBarDefinition): Boolean =
        runCatching {
            repository.editCandidateBar(bar, context.getString(R.string.appearance_copy_suffix))
            load()
        }.isSuccess

    override suspend fun importFrom(uri: Uri): AppearanceImportOutcome =
        withContext(Dispatchers.IO) {
            val text =
                try {
                    AppearanceFileReader.read(context, uri)
                } catch (error: AppearanceError) {
                    return@withContext rejected(error)
                } catch (error: IOException) {
                    return@withContext AppearanceImportOutcome.Rejected(AppearanceImportOutcome.Reason.UNREADABLE, null)
                }
            try {
                val document = repository.import(text)
                load()
                AppearanceImportOutcome.Imported(document.name)
            } catch (error: AppearanceError) {
                rejected(error)
            } catch (error: IOException) {
                AppearanceImportOutcome.Rejected(AppearanceImportOutcome.Reason.STORAGE, null)
            }
        }

    override suspend fun exportTo(
        id: String,
        uri: Uri,
    ): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val text = repository.export(id)
                context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.encodeToByteArray()) }
            }.isSuccess
        }

    override suspend fun delete(id: String): Boolean =
        runCatching {
            repository.delete(id)
            load()
        }.isSuccess

    private fun rejected(error: AppearanceError): AppearanceImportOutcome.Rejected {
        val reason =
            when (error.code) {
                AppearanceErrorCode.OVERSIZE -> AppearanceImportOutcome.Reason.TOO_LARGE
                AppearanceErrorCode.UNSUPPORTED_VERSION -> AppearanceImportOutcome.Reason.UNSUPPORTED_VERSION
                AppearanceErrorCode.UNREADABLE_SOURCE -> AppearanceImportOutcome.Reason.UNREADABLE
                AppearanceErrorCode.STORAGE_UNAVAILABLE,
                AppearanceErrorCode.ALREADY_EXISTS,
                AppearanceErrorCode.READ_ONLY_BUILTIN,
                -> AppearanceImportOutcome.Reason.STORAGE
                AppearanceErrorCode.INVALID_VALUE,
                AppearanceErrorCode.UNKNOWN_FIELD,
                AppearanceErrorCode.IDENTITY_MISMATCH,
                -> AppearanceImportOutcome.Reason.INVALID_VALUE
                else -> AppearanceImportOutcome.Reason.NOT_AN_APPEARANCE
            }
        return AppearanceImportOutcome.Rejected(reason, error.path.takeIf { it != "$" })
    }

    private suspend fun load() {
        val documents = runCatching { repository.loadAppearances() }.getOrNull() ?: repository.appearances.value
        // A slower list read must not publish controls from an older applied appearance.
        val current = repository.state.value
        mutable.value =
            AppearanceSettingsState(
                appearances =
                    documents.map { document ->
                        AppearanceOption(
                            id = document.id,
                            name = document.name.takeUnless { AppearancePresets.isBuiltIn(document) },
                            builtIn = AppearancePresets.isBuiltIn(document),
                        )
                    },
                inUseId = current.document.id,
                dynamicColor = current.dynamicColor,
                dynamicColorAvailable = repository.dynamicColorAvailable,
                candidateBar = current.document.candidateBar,
                available = true,
            )
    }
}
