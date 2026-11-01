package app.songyan.ime.settings.state

/** An input scheme the user installed from a scheme package (ZIP). */
data class ImportedSchemeInfo(
    val name: String,
    val schemaIds: List<String>,
    /** True when the keyboard is currently typing with one of the imported schemes. */
    val inUse: Boolean,
    /** The stored package; the id its learned words are merged under. */
    val sourceId: String? = null,
    /** How many dictionaries the package brought learned words for, still waiting for an answer; 0 when none. */
    val learningDictionaries: Int = 0,
    /** Grammar model files (`*.gram`) the package's configuration names but does not ship; empty when none. */
    val requiredModels: List<String> = emptyList(),
)

/** How merging a package's learned words ended; [message] is already localized by the app module. */
sealed interface PackageLearningResult {
    data class Merged(
        val message: String,
    ) : PackageLearningResult

    data class Failure(
        val message: String?,
    ) : PackageLearningResult
}

/**
 * How much of a download has arrived: [total] is the size the server announced, or null when it
 * did not say. A download that reached its total is being installed.
 */
data class TransferProgress(
    val bytes: Long,
    val total: Long?,
) {
    val finished: Boolean get() = total != null && bytes >= total

    /** The share that has arrived, or null when the size is unknown. */
    val fraction: Float? get() = total?.takeIf { it > 0 }?.let { (bytes.toFloat() / it).coerceIn(0f, 1f) }
}

/** Result of one scheme import request; [message] is already localized by the app module. */
sealed interface SchemeImportResult {
    data class Success(
        val info: ImportedSchemeInfo?,
        /** The import was a new version of a stored package and took its place. */
        val updated: Boolean = false,
    ) : SchemeImportResult

    /** An update found the same version already installed; nothing changed. */
    data class UpToDate(
        val info: ImportedSchemeInfo?,
    ) : SchemeImportResult

    data class Failure(
        val message: String?,
        /** Whether trying the same request again can succeed. */
        val retryable: Boolean = false,
    ) : SchemeImportResult
}

/**
 * Bridge the settings UI uses to import a Rime scheme package.
 *
 * The app module implements it: it validates the archive, stages a complete Rime source, deploys it
 * in the `:deploy` process and publishes the new generation. Calls are already on a background
 * dispatcher and return only after the deploy finished (or failed); the UI shows progress meanwhile.
 */
interface SchemeImportGateway {
    /** False until the app module installs its implementation (tests / early UI). */
    val available: Boolean

    suspend fun importFromUri(uri: android.net.Uri): SchemeImportResult

    /** Imports the folder the user picked with the system folder picker; its files are the package. */
    suspend fun importFromTree(uri: android.net.Uri): SchemeImportResult = SchemeImportResult.Failure(null)

    /**
     * Downloads the package at an HTTPS [url] (a GitHub repository address works too) and imports it;
     * [progress] hears how much has arrived.
     */
    suspend fun importFromLink(
        url: String,
        progress: (TransferProgress) -> Unit = {},
    ): SchemeImportResult = SchemeImportResult.Failure(null)

    /** Downloads the stored package [sourceId] again from the link it came from and installs the new version. */
    suspend fun update(
        sourceId: String,
        progress: (TransferProgress) -> Unit = {},
    ): SchemeImportResult = SchemeImportResult.Failure(null)

    /**
     * Merges the learned words the stored package [sourceId] came with into its dictionaries, after
     * putting the package in use when it is not. The package stays installed whatever happens.
     */
    suspend fun mergePackageLearning(sourceId: String): PackageLearningResult = PackageLearningResult.Failure(null)

    /** Drops the learned words of [sourceId] that were waiting for an answer. */
    fun discardPackageLearning(sourceId: String) = Unit

    /** Asks the running import to stop; its request then ends with a cancelled failure. */
    fun cancelRunning() = Unit
}

/** Fallback used before the app module supplies a real gateway. */
class UnavailableSchemeImportGateway : SchemeImportGateway {
    override val available: Boolean = false

    override suspend fun importFromUri(uri: android.net.Uri): SchemeImportResult = SchemeImportResult.Failure(null)
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object SchemeImportHolder {
    private val fallback = UnavailableSchemeImportGateway()

    @Volatile
    private var installed: SchemeImportGateway? = null

    val current: SchemeImportGateway
        get() = installed ?: fallback

    fun install(gateway: SchemeImportGateway) {
        installed = gateway
    }
}
