package app.songyan.ime.settings.state

/** How one small download for a settings screen ended; [Failure.message] is already localized. */
sealed interface LinkDownloadResult {
    class Downloaded(
        val bytes: ByteArray,
    ) : LinkDownloadResult

    data class Failure(
        val message: String?,
        /** Whether trying the same link again can work (the network or the server failed). */
        val retryable: Boolean = false,
        /** The file was larger than the caller allows; the caller names its own limit. */
        val tooLarge: Boolean = false,
    ) : LinkDownloadResult
}

/**
 * Downloads a small file from an https link for a settings screen. The app module implements it
 * through the `:deploy` process, the only one allowed to use the network.
 */
interface LinkDownloadGateway {
    val available: Boolean

    suspend fun download(
        url: String,
        maxBytes: Long,
    ): LinkDownloadResult

    /** Asks the running download to stop; it then ends with a failure. */
    fun cancelRunning() = Unit
}

/** Fallback used before the app module supplies a real gateway. */
class UnavailableLinkDownloadGateway : LinkDownloadGateway {
    override val available = false

    override suspend fun download(
        url: String,
        maxBytes: Long,
    ): LinkDownloadResult = LinkDownloadResult.Failure(null)
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object LinkDownloadHolder {
    private val fallback = UnavailableLinkDownloadGateway()

    @Volatile
    private var installed: LinkDownloadGateway? = null

    val current: LinkDownloadGateway
        get() = installed ?: fallback

    fun install(gateway: LinkDownloadGateway) {
        installed = gateway
    }
}
