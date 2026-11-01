package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.KeyboardResourceSet
import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor
import app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/**
 * The schema map the keyboard runs on: the bundled schemes with their generated compact
 * companions, plus every other source scheme the deployed generation makes loadable.
 *
 * A user scheme package publishes its scheme files beside the built-in ones, so the engine reports
 * them in its schema list. The template pairing alone knows only the bundled ids and would refuse
 * them as unknown input methods; here a source scheme runs on the 26-key layout its own speller is
 * written for, and on 14, 18 or 9 keys only through a companion its generation compiled for it.
 */
internal object KeyboardResourceSets {
    /** The resource set for one running engine, or null when the engine reports no schemas. */
    fun forEngineSchemas(
        schemas: List<KeyboardSchemaDescriptor>,
        adapters: List<KeyboardResourceSet.EncodingAdapter> = emptyList(),
    ): KeyboardResourceSet? {
        if (schemas.isEmpty()) return null
        val base = KeyboardResourceSet.builtin("", "")
        val known = base.layoutEntries.map { it.schemaID }.toSet()
        // The bundled schemes keep their bundled companions; a generation cannot rebind them.
        val generated = adapters.filter { it.sourceSchemaID !in known }
        val companions = companions() + generated.filter { it.schemaID != it.sourceSchemaID }.map { it.schemaID }
        val extra =
            schemas
                .map { it.id }
                .filter { it.isNotEmpty() && it !in companions && it !in known }
                .distinct()
        val offered = generated.filter { it.sourceSchemaID in extra }
        if (extra.isEmpty()) return base
        return base.copy(
            layoutEntries =
                base.layoutEntries + extra.map { KeyboardResourceSet.LayoutEntry(KeyboardLayoutKind.QWERTY26, it) },
            encodingAdapters = base.encodingAdapters + offered,
        )
    }

    /** Engine ids that are generated transport for a source scheme, never a selectable input method. */
    private fun companions(): Set<String> =
        BuiltinTemplateSchemas.ADAPTERS
            .map { it.schemaID }
            .toSet()
}
