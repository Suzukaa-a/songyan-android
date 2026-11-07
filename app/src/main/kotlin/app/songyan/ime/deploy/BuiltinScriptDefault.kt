package app.songyan.ime.deploy

import app.songyan.ime.keyboard.engine.BuiltinScriptState
import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.engine.TraditionalDefault
import app.songyan.ime.rime.EngineOptionPreferences
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.BuiltinSchemes
import app.songyan.ime.storage.InputSchemeSetup
import app.songyan.ime.storage.LearningData
import app.songyan.ime.storage.SourceIds

/**
 * Gives a first installation the bundled scheme's script state its language expects. An
 * installation that already has data keeps whatever it had, including the old silent default of
 * simplified output for a user who never chose — which is why this asks whether the installation
 * has data at all, before the deployment writes any, instead of whether a choice was ever saved.
 *
 * The choice is the preference file the keyboard itself writes when the user picks a state, so the
 * engine applies it at the next start, a schema switch, a restart and every later deployment. A
 * saved choice is never replaced. The two compact layouts persist through the input method that
 * owns their switch, so seeding the two bundled input methods covers all of their adapters.
 */
internal class BuiltinScriptDefault(
    private val storage: AppStorage,
    /** The app's effective locales, most preferred first; read only when a first installation is seeded. */
    private val localeTags: () -> List<String>,
) {
    private val preferences = EngineOptionPreferences(storage.optionsDir)

    /**
     * Seeds the language's script state when the installation has no data of its own. A failed
     * save propagates: the deployment fails with nothing installed, instead of publishing a
     * generation and reporting a default it did not keep, and the retry can seed again.
     */
    fun seedIfFirstInstallation() {
        if (!InputSchemeSetup.needsDownload(storage)) return
        val state = TraditionalDefault.initialState(localeTags(), false) ?: return
        for (schemaID in BuiltinSchemes.IDS) {
            val members = switchMembers(schemaID)
            // A record that exists is the user's choice, even when this build cannot read its value:
            // an older switch's boolean or a state a later version added must survive the default.
            if (preferences.hasStoredValue(members.first())) continue
            preferences.write(members.first { it.name == state.optionName }, true)
        }
    }

    /**
     * The four members of the bundled scheme's one script radio group, as [BuiltinScriptState]
     * names them. A preference the deployed data does not declare this group for is never read
     * back, so a mismatch stays inert instead of changing what the keyboard offers.
     */
    private fun switchMembers(schemaID: String): List<EngineOptionDescriptor> {
        val state = BuiltinScriptState.values()
        val names = state.map { it.optionName }
        return state.map {
            EngineOptionDescriptor(
                resourceID = LearningData.namespace(SourceIds.BUILTIN),
                schemaID = schemaID,
                name = it.optionName,
                kind = EngineOptionKind.TRADITIONAL,
                defaultValue = it == BuiltinScriptState.SIMPLIFIED,
                choiceGroup = names,
            )
        }
    }
}
