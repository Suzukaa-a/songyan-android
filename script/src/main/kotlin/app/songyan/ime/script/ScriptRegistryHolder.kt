package app.songyan.ime.script

/** Process-wide holder; the app module installs its implementation once during Application
 *  creation. The unavailable fallback keeps preview builds and tests working without the proxy. */
object ScriptRegistryHolder {
    private val fallback = UnavailableScriptRegistry()

    @Volatile
    private var installed: ScriptRegistry? = null

    val current: ScriptRegistry
        get() = installed ?: fallback

    fun install(registry: ScriptRegistry) {
        installed = registry
    }
}

/** Every query answers "nothing here": empty catalog, refusals that name the missing host. The
 *  IME process can hold this without ever loading the engine or starting a process. */
private class UnavailableScriptRegistry : ScriptRegistry {
    override val entries: List<ScriptEntry> = emptyList()
    override val refreshing: Boolean = false

    override suspend fun reload() = Unit

    override suspend fun inspect(source: ByteArray): ScriptHeader =
        ScriptHeader.Refused("unavailable", 0, "the script host is not installed", false)

    override suspend fun add(
        source: ByteArray,
        origin: ScriptSourceOrigin,
    ): ScriptEntry = throw ScriptRegistryUnavailable()

    override suspend fun addFromLink(url: String): ScriptEntry = throw ScriptRegistryUnavailable()

    override suspend fun confirm(
        id: String,
        settings: Map<String, String>,
    ): ScriptEntry = throw ScriptRegistryUnavailable()

    override suspend fun updateSettings(
        id: String,
        settings: Map<String, String>,
    ): ScriptEntry = throw ScriptRegistryUnavailable()

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): ScriptEntry = throw ScriptRegistryUnavailable()

    override suspend fun reorder(orderedIds: List<String>) = throw ScriptRegistryUnavailable()

    override suspend fun source(id: String): ByteArray? = null

    override suspend fun export(id: String): ByteArray? = null

    override suspend fun checkUpdate(id: String): ScriptEntry = throw ScriptRegistryUnavailable()

    override suspend fun delete(id: String) = throw ScriptRegistryUnavailable()

    override suspend fun runTrial(
        id: String,
        text: String,
    ): ScriptRunOutcome = ScriptRunOutcome.Failed("unavailable", "the script host is not installed", 0)

    override suspend fun run(
        id: String,
        text: String,
    ): ScriptRunOutcome = ScriptRunOutcome.Failed("unavailable", "the script host is not installed", 0)

    override suspend fun runAudio(
        id: String,
        wav: ByteArray,
    ): ScriptRunOutcome = ScriptRunOutcome.Failed("unavailable", "the script host is not installed", 0)

    override suspend fun cancel(id: String) = Unit
}

/** Thrown by the unavailable registry's mutating operations. */
class ScriptRegistryUnavailable : IllegalStateException("the script host is not installed")
