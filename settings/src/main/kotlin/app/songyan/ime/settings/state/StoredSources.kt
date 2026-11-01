package app.songyan.ime.settings.state

/** What a stored input scheme is; settings words its row accordingly. */
enum class StoredSourceKind { BUILTIN, DICTIONARY, SCHEME }

/** Where a stored input scheme stands; the row shows it as its status. */
enum class StoredSourceStatus {
    IN_USE,
    NOT_IN_USE,

    /** A newer version is installed than the one the keyboard runs; "use new version" switches it over. */
    UPDATE_AVAILABLE,

    /** Files of the scheme are missing or unreadable; it can only be deleted or imported again. */
    DAMAGED,
}

/** One input scheme the app keeps on the device; any of them can be used again or deleted. */
data class StoredSourceInfo(
    val id: String,
    /** Already localized by the app module. */
    val name: String,
    val kind: StoredSourceKind,
    val inUse: Boolean,
    val status: StoredSourceStatus = if (inUse) StoredSourceStatus.IN_USE else StoredSourceStatus.NOT_IN_USE,
    /** Whether the package remembers the link it came from, so "update" can download it again. */
    val updatable: Boolean = false,
    /** Host of the link the package was downloaded from; null when it came from a file or folder. */
    val originHost: String? = null,
    /** The input methods the package contains. */
    val schemes: List<InstalledScheme> = emptyList(),
    /** The input method the keyboard types with; only set while this package is in use. */
    val inUseSchemeId: String? = null,
) {
    /** The bundled package is never deleted: it is what the keyboard falls back to. */
    val deletable: Boolean get() = kind != StoredSourceKind.BUILTIN

    val usable: Boolean get() = status != StoredSourceStatus.DAMAGED
}

/** Result of one use or delete request; [message] is already localized by the app module. */
sealed interface StoredSourceResult {
    data object Success : StoredSourceResult

    data class Failure(
        val message: String?,
    ) : StoredSourceResult
}

/**
 * Bridge the settings UI uses to switch between stored input schemes and delete them. Calls
 * return after the deploy process finished the request.
 */
interface StoredSourcesGateway {
    val available: Boolean

    suspend fun use(sourceId: String): StoredSourceResult

    suspend fun delete(sourceId: String): StoredSourceResult

    /** Undoes the last [delete]: the scheme comes back as a stored scheme (not in use). */
    suspend fun restore(sourceId: String): StoredSourceResult = StoredSourceResult.Failure(null)
}

class UnavailableStoredSourcesGateway : StoredSourcesGateway {
    override val available: Boolean = false

    override suspend fun use(sourceId: String): StoredSourceResult = StoredSourceResult.Failure(null)

    override suspend fun delete(sourceId: String): StoredSourceResult = StoredSourceResult.Failure(null)
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object StoredSourcesHolder {
    private val fallback = UnavailableStoredSourcesGateway()

    @Volatile
    private var installed: StoredSourcesGateway? = null

    val current: StoredSourcesGateway
        get() = installed ?: fallback

    fun install(gateway: StoredSourcesGateway) {
        installed = gateway
    }
}
