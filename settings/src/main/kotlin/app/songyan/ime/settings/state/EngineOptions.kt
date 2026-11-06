package app.songyan.ime.settings.state

import app.songyan.ime.keyboard.engine.BuiltinScriptState
import app.songyan.ime.keyboard.engine.EngineOptionKind

/** One schema switch of an input method as the settings screen shows it. */
data class EngineOptionSetting(
    val name: String,
    val kind: EngineOptionKind,
    /** The user's choice, or the schema's own default when they never chose. */
    val on: Boolean,
    val choiceGroup: List<String> = emptyList(),
    val choiceLabel: String? = null,
    /** The bundled scheme's known script state; its wording comes from resources instead of [choiceLabel]. */
    val knownScriptState: BuiltinScriptState? = null,
)

/**
 * Bridge the settings UI uses to read and change the schema switches of an input method. The
 * settings process has no engine: a change is stored, and the keyboard applies it the next time
 * it prepares an editor.
 */
interface EngineOptionsGateway {
    /** The switches of [schemaId] in the active build; empty when it has none or no build is readable. */
    suspend fun load(schemaId: String): List<EngineOptionSetting>

    /** Stores the choice; false when it could not be saved. */
    suspend fun set(
        schemaId: String,
        name: String,
        on: Boolean,
    ): Boolean
}

class UnavailableEngineOptionsGateway : EngineOptionsGateway {
    override suspend fun load(schemaId: String): List<EngineOptionSetting> = emptyList()

    override suspend fun set(
        schemaId: String,
        name: String,
        on: Boolean,
    ) = false
}

/** Process-wide holder; the app module installs its implementation once during Application creation. */
object EngineOptionsHolder {
    private val fallback = UnavailableEngineOptionsGateway()

    @Volatile
    private var installed: EngineOptionsGateway? = null

    val current: EngineOptionsGateway
        get() = installed ?: fallback

    fun install(gateway: EngineOptionsGateway) {
        installed = gateway
    }
}
