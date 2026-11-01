package app.songyan.ime.content

import app.songyan.ime.keyboard.engine.EngineOptionDescriptor
import app.songyan.ime.rime.EngineOptionPreferences
import app.songyan.ime.rime.SchemaOptionCapabilities
import app.songyan.ime.settings.state.EngineOptionSetting
import app.songyan.ime.settings.state.EngineOptionsGateway
import app.songyan.ime.storage.AppStorage
import app.songyan.ime.storage.GenerationPublisher
import app.songyan.ime.storage.LearningData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * The settings side of the schema switches: the same descriptors the keyboard reads from the active
 * build and the same per-input-method files it applies at the next editor, so both always agree.
 */
internal class EngineOptionsGatewayImpl(
    private val storage: AppStorage,
) : EngineOptionsGateway {
    private val publisher = GenerationPublisher(storage)
    private val preferences = EngineOptionPreferences(storage.optionsDir)

    override suspend fun load(schemaId: String): List<EngineOptionSetting> =
        withContext(Dispatchers.IO) {
            descriptors(schemaId).map { descriptor ->
                EngineOptionSetting(
                    name = descriptor.name,
                    kind = descriptor.kind,
                    on = runCatching { preferences.read(descriptor) }.getOrNull() ?: descriptor.defaultValue,
                )
            }
        }

    override suspend fun set(
        schemaId: String,
        name: String,
        on: Boolean,
    ): Boolean =
        withContext(Dispatchers.IO) {
            val descriptor = descriptors(schemaId).firstOrNull { it.name == name } ?: return@withContext false
            try {
                preferences.write(descriptor, on)
                true
            } catch (_: IOException) {
                false
            }
        }

    private fun descriptors(schemaId: String): List<EngineOptionDescriptor> {
        val active = publisher.readActive() ?: return emptyList()
        val generation = storage.generationDir(active.generation)
        return SchemaOptionCapabilities.read(
            // The keyboard's engine uses the learning namespace as the resource id.
            resourceID = LearningData.namespace(active.sourceId),
            schemaID = schemaId,
            compiledDir = File(generation, "build"),
            sharedDir = File(generation, "resources"),
        )
    }
}
