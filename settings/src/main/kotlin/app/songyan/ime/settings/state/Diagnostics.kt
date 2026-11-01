package app.songyan.ime.settings.state

/**
 * Bridge for the diagnostic report the About page shares. The report holds versions, device and
 * result codes only; the app module builds it, the settings UI hands it to the system share sheet.
 */
interface DiagnosticsGateway {
    val available: Boolean

    suspend fun report(): String?

    /** The latest time the keyboard broke a crash loop at engine start; null when it never had to. */
    suspend fun engineRecovery(): EngineRecoveryNotice? = null
}

/**
 * The keyboard's engine kept dying while it started and was recovered: the learning data in use
 * was moved aside (never deleted) and, for an imported input scheme, the built-in one took over.
 */
data class EngineRecoveryNotice(
    val timeEpochMs: Long,
    val switchedToBuiltin: Boolean,
    val movedLearningData: Boolean,
)

class UnavailableDiagnosticsGateway : DiagnosticsGateway {
    override val available: Boolean = false

    override suspend fun report(): String? = null
}

object DiagnosticsHolder {
    private val fallback = UnavailableDiagnosticsGateway()

    @Volatile
    private var installed: DiagnosticsGateway? = null

    val current: DiagnosticsGateway get() = installed ?: fallback

    fun install(gateway: DiagnosticsGateway) {
        installed = gateway
    }
}
