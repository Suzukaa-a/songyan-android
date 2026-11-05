package app.songyan.ime.rime

import app.songyan.ime.keyboard.engine.KeyboardSchemaDescriptor

/**
 * Fills the facts the engine's schema list does not carry by itself.
 *
 * The declared speller alphabet places an input method whose id the language table cannot name (an
 * alias with the zhuyin key map); it is read from the deployed config once per listed schema, at
 * setup and on a schema switch, never on the key path.
 */
internal object SchemaDescriptors {
    suspend fun withDeclaredAlphabets(
        listed: List<KeyboardSchemaDescriptor>,
        alphabetOf: suspend (String) -> String?,
    ): List<KeyboardSchemaDescriptor> = listed.map { it.copy(alphabet = alphabetOf(it.id).orEmpty()) }
}
