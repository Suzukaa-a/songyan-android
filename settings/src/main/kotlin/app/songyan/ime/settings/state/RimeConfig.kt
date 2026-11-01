package app.songyan.ime.settings.state

import android.content.Intent

/** Where the user's Rime configuration stands; messages are already localized by the app module. */
data class RimeConfigInfo(
    /** Files in the configuration folder. */
    val fileCount: Int,
    /** The folder differs from what the keyboard uses: there are changes to apply. */
    val pending: Boolean,
    /** Why the last attempt to apply the changes failed, while it still stands. */
    val failure: String? = null,
    /** The scheme in use was built without the configuration, and why. */
    val notice: String? = null,
)

/** How applying the changes ended; [Failure.message] is already localized. */
sealed interface RimeConfigApplyResult {
    data object Applied : RimeConfigApplyResult

    data class Failure(
        val message: String?,
    ) : RimeConfigApplyResult
}

/**
 * The user's own Rime configuration (`*.custom.yaml` and the like), edited in the system Files app
 * and applied from the settings screen. The app module implements it.
 */
interface RimeConfigGateway {
    val available: Boolean

    /** Intents that open the configuration folder in the Files app, the most specific first. */
    fun openFolderIntents(): List<Intent>

    suspend fun info(): RimeConfigInfo

    /** Checks the edited files and rebuilds the scheme in use with them; returns once that ended. */
    suspend fun apply(): RimeConfigApplyResult
}

/** Fallback used before the app module supplies a real gateway. */
class UnavailableRimeConfigGateway : RimeConfigGateway {
    override val available = false

    override fun openFolderIntents(): List<Intent> = emptyList()

    override suspend fun info() = RimeConfigInfo(fileCount = 0, pending = false)

    override suspend fun apply(): RimeConfigApplyResult = RimeConfigApplyResult.Failure(null)
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object RimeConfigHolder {
    private val fallback = UnavailableRimeConfigGateway()

    @Volatile
    private var installed: RimeConfigGateway? = null

    val current: RimeConfigGateway
        get() = installed ?: fallback

    fun install(gateway: RimeConfigGateway) {
        installed = gateway
    }
}
