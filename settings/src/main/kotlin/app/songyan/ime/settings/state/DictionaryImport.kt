package app.songyan.ime.settings.state

import android.net.Uri

/**
 * An imported user dictionary: over the built-in input methods, or stacked onto an imported scheme
 * package when [targetSourceId] is set.
 */
data class ImportedDictionaryInfo(
    val name: String,
    val entryCount: Int,
    /** True when the keyboard is currently running on the imported dictionary generation. */
    val inUse: Boolean,
    /** The scheme package the dictionary is stacked onto; null for the built-in input methods. */
    val targetSourceId: String? = null,
    /** That package's name, already localized by the app module. */
    val targetName: String? = null,
)

/** Result of one import or removal request; [message] is already localized by the app module. */
sealed interface DictionaryImportResult {
    data class Success(
        val info: ImportedDictionaryInfo?,
    ) : DictionaryImportResult

    data class Failure(
        val message: String?,
        /** Whether trying the same request again can succeed (a network failure, not a bad file). */
        val retryable: Boolean = false,
    ) : DictionaryImportResult
}

/**
 * Bridge the settings UI uses to import a Rime `.dict.yaml` dictionary or remove it again.
 *
 * The app module implements it: it checks and normalizes the file, stages a complete Rime source,
 * deploys it in the `:deploy` process and publishes the new generation. Calls are already on a
 * background dispatcher and return only after the deploy finished (or failed); the UI shows a
 * progress dialog meanwhile.
 */
interface DictionaryImportGateway {
    /** False until the app module installs its implementation (tests / early UI). */
    val available: Boolean

    /**
     * Imports one dictionary made of the picked files (a main dictionary plus what it imports).
     * [target] is the scheme package to stack it onto; null adds it to the built-in input methods.
     */
    suspend fun importFromUris(
        uris: List<Uri>,
        target: String? = null,
    ): DictionaryImportResult

    suspend fun importFromUrl(
        url: String,
        target: String? = null,
    ): DictionaryImportResult

    /** Removes the dictionary of [target] (null: the one over the built-in input methods). */
    suspend fun removeImportedDictionary(target: String? = null): DictionaryImportResult

    /** Asks the running import to stop; its request then ends with a cancelled failure. */
    fun cancelRunning() = Unit
}

/** Fallback used before the app module supplies a real gateway. */
class UnavailableDictionaryImportGateway : DictionaryImportGateway {
    override val available: Boolean = false

    override suspend fun importFromUris(
        uris: List<Uri>,
        target: String?,
    ): DictionaryImportResult = DictionaryImportResult.Failure(null)

    override suspend fun importFromUrl(
        url: String,
        target: String?,
    ): DictionaryImportResult = DictionaryImportResult.Failure(null)

    override suspend fun removeImportedDictionary(target: String?): DictionaryImportResult =
        DictionaryImportResult.Failure(null)
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object DictionaryImportHolder {
    private val fallback = UnavailableDictionaryImportGateway()

    @Volatile
    private var installed: DictionaryImportGateway? = null

    val current: DictionaryImportGateway
        get() = installed ?: fallback

    fun install(gateway: DictionaryImportGateway) {
        installed = gateway
    }
}
