package app.songyan.ime.keyboard.engine

/**
 * The scheme families a caller already resolved and cached.
 *
 * Layout selection reads these instead of resolving a schema id again, so an id the language table
 * cannot place by its name (an alias whose speller alphabet is the zhuyin one) still selects the
 * physical layout of its family. Ids the caller did not resolve keep the id-only rule, which is
 * also what an empty context answers.
 */
class LayoutFamilyContext private constructor(
    private val bySchema: Map<String, InputSchemeFamily>,
) {
    /** The cached family of [schemaID]; unresolved ids follow the language table alone. */
    fun familyOf(schemaID: String): InputSchemeFamily = bySchema[schemaID] ?: InputLayoutPolicy.familyOf(schemaID)

    companion object {
        /** No cached facts: every id follows the language table alone. */
        val UNKNOWN = LayoutFamilyContext(emptyMap())

        fun of(vararg families: Pair<String, InputSchemeFamily>): LayoutFamilyContext =
            LayoutFamilyContext(families.toMap())

        fun of(families: Map<String, InputSchemeFamily>): LayoutFamilyContext = LayoutFamilyContext(families.toMap())
    }
}
