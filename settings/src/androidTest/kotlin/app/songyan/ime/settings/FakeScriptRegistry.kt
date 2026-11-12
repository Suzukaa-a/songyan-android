package app.songyan.ime.settings

import app.songyan.ime.script.ScriptEntry
import app.songyan.ime.script.ScriptHeader
import app.songyan.ime.script.ScriptRegistry
import app.songyan.ime.script.ScriptRunOutcome
import app.songyan.ime.script.ScriptSettingField
import app.songyan.ime.script.ScriptSettingKind
import app.songyan.ime.script.ScriptSourceOrigin
import kotlinx.coroutines.CompletableDeferred

/**
 * The 脚本 registry without the proxy process: no engine, no network, no storage. It records what
 * the screens asked for so a test can assert the flow, and hands back stored entries as if the
 * catalog had been read from disk.
 *
 * Shared by the screen tests and the navigation test, which drive the real settings graph: both
 * need the same store/confirm/enable semantics.
 */
internal class FakeScriptRegistry : ScriptRegistry {
    private val stored = mutableListOf<ScriptEntry>()
    private val confirmedBefore = mutableSetOf<String>()

    override var entries: List<ScriptEntry> = emptyList()
        private set

    override val refreshing: Boolean = false

    var imported: ScriptEntry = declaredEntry()
    var checkedUpdate: ScriptEntry? = null
    var confirmGate: CompletableDeferred<Unit>? = null

    /** When set, [reload] waits for it: a test can hold the first read, and with it loading=true. */
    var reloadGate: CompletableDeferred<Unit>? = null

    /** When set, [add] waits for it: a test can finish an import after its dialog has gone. */
    var addGate: CompletableDeferred<Unit>? = null

    val adds = mutableListOf<Pair<ByteArray, ScriptSourceOrigin>>()
    val links = mutableListOf<String>()
    val confirms = mutableListOf<Pair<String, Map<String, String>>>()
    val enabled = mutableListOf<Pair<String, Boolean>>()
    val updates = mutableListOf<String>()

    fun store(vararg scripts: ScriptEntry) {
        stored.clear()
        stored += scripts
        // A seed that is no longer pending has been confirmed before, so confirming it again
        // is a re-confirmation and must leave its switch alone.
        scripts.filterNot { it.confirmationPending }.forEach { confirmedBefore += it.id }
        entries = stored.toList()
    }

    override suspend fun reload() {
        reloadGate?.await()
        entries = stored.toList()
    }

    override suspend fun inspect(source: ByteArray): ScriptHeader = error("inspect is not used by this screen")

    override suspend fun add(
        source: ByteArray,
        origin: ScriptSourceOrigin,
    ): ScriptEntry {
        adds += source to origin
        addGate?.await()
        val entry =
            imported.copy(
                id = "script-" + (stored.size + 1),
                order = stored.size,
                sourceOrigin = origin,
                enabled = false,
            )
        stored += entry
        entries = stored.toList()
        return entry
    }

    override suspend fun addFromLink(url: String): ScriptEntry {
        links += url
        return add(ByteArray(0), ScriptSourceOrigin.Link(url, url))
    }

    override suspend fun confirm(
        id: String,
        settings: Map<String, String>,
    ): ScriptEntry {
        confirms += id to settings
        confirmGate?.await()
        val first = id !in confirmedBefore
        confirmedBefore += id
        return replace(id) { it.copy(confirmationPending = false, enabled = if (first) true else it.enabled) }
    }

    override suspend fun updateSettings(
        id: String,
        settings: Map<String, String>,
    ): ScriptEntry = confirm(id, settings)

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): ScriptEntry {
        this.enabled += id to enabled
        return replace(id) { it.copy(enabled = enabled) }
    }

    override suspend fun reorder(orderedIds: List<String>) = Unit

    override suspend fun source(id: String): ByteArray? = null

    override suspend fun export(id: String): ByteArray? = null

    override suspend fun checkUpdate(id: String): ScriptEntry {
        updates += id
        // The update replaces the declarations, never the switch the user chose.
        val fresh = checkedUpdate ?: return replace(id) { it.copy(confirmationPending = false) }
        return replace(id) { fresh.copy(id = id, order = it.order, enabled = it.enabled) }
    }

    override suspend fun delete(id: String) {
        stored.removeAll { it.id == id }
        entries = stored.toList()
    }

    override suspend fun runTrial(
        id: String,
        text: String,
    ): ScriptRunOutcome = ScriptRunOutcome.Success(emptyList(), emptyList(), false)

    override suspend fun run(
        id: String,
        text: String,
    ): ScriptRunOutcome = ScriptRunOutcome.Success(emptyList(), emptyList(), false)

    override suspend fun runAudio(
        id: String,
        wav: ByteArray,
    ): ScriptRunOutcome = ScriptRunOutcome.Success(emptyList(), emptyList(), false)

    override suspend fun cancel(id: String) = Unit

    private fun replace(
        id: String,
        change: (ScriptEntry) -> ScriptEntry,
    ): ScriptEntry {
        val index = stored.indexOfFirst { it.id == id }
        check(index >= 0) { "no script " + id }
        stored[index] = change(stored[index])
        entries = stored.toList()
        return stored[index]
    }
}

// --- the fixtures both script test classes start from ---

internal const val SCRIPT_SOURCE = "// @api 1 @name 加书名号 export default async ({ text }) => text;"
internal const val SCRIPT_NAME = "加书名号"
internal const val SECRET = "hunter2"
internal const val LINK = "https://example.com/rent.js"

internal val DECLARED_SITES = listOf("https://api.example.com/v1", "https://second.example.com")

internal val DECLARED_SETTINGS =
    listOf(
        ScriptSettingField("token", ScriptSettingKind.TEXT, "API token", secret = true),
        ScriptSettingField("endpoint", ScriptSettingKind.URL, "Endpoint", secret = false),
        ScriptSettingField("mode", ScriptSettingKind.CHOICE, "Mode", secret = false),
    )

internal fun declaredEntry(
    name: String = SCRIPT_NAME,
    enabled: Boolean = false,
    pending: Boolean = true,
    connects: List<String> = DECLARED_SITES,
    origin: ScriptSourceOrigin = ScriptSourceOrigin.Pasted,
    settings: List<ScriptSettingField> = DECLARED_SETTINGS,
    needsAudio: Boolean = false,
) = ScriptEntry(
    id = "script-1",
    name = name,
    description = "把选中的文字两边加上书名号",
    enabled = enabled,
    order = 0,
    apiVersion = 1,
    connects = connects,
    needsAudio = needsAudio,
    availableOnThisHost = true,
    confirmationPending = pending,
    settings = settings,
    sourceOrigin = origin,
)
