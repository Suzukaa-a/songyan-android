package app.songyan.ime.layout

/**
 * Runtime identity of a physical layout definition: proven (content-level, deterministic), unproven,
 * or absent.
 *
 * Unprovable identities never compare equal to anything (fail-closed): [Absent] is distinct from a
 * definition, [Proven] is stable across calls and processes, and [Unproven] keeps the id for
 * diagnostics only. Any comparison involving [Unproven] or [Absent] is false, including
 * [Absent] vs [Absent], so callers treat it as a change and never revive stale state.
 */
sealed class LayoutRuntimeIdentity {
    /** No definition (for example English mode, or none resolved). */
    data object Absent : LayoutRuntimeIdentity()

    /** Content-level identity derived from a deterministic encoding. */
    data class Proven(
        val canonical: String,
    ) : LayoutRuntimeIdentity()

    /** A definition exists but its identity cannot be proven; the id is diagnostics only. */
    data class Unproven(
        val id: String,
    ) : LayoutRuntimeIdentity()

    /** The only entry point allowed to decide equality. */
    fun isProvablySameAs(other: LayoutRuntimeIdentity): Boolean {
        if (this !is Proven || other !is Proven) return false
        return canonical == other.canonical
    }

    /** Diagnostics only: whether the identity is proven. */
    val isProven: Boolean get() = this is Proven

    companion object {
        /** Derives from an optional definition (null becomes [Absent]). */
        fun of(definition: LayoutDefinition?): LayoutRuntimeIdentity =
            if (definition == null) Absent else definition.runtimeIdentity
    }
}

/** Deterministic content-level identity encoder (failure is injectable so callers can fail closed). */
object LayoutIdentityEncoder {
    /** Raised when the canonical encoding is unavailable. */
    class EncodingUnavailable : Exception("layout identity encoding unavailable")

    /**
     * Fields that affect key behavior; name and note are display-only and excluded.
     */
    @kotlinx.serialization.Serializable
    private data class Canonical(
        val id: String,
        val input: LayoutInput,
        val rows: List<LayoutRow>,
        val auxiliaryAreas: List<LayoutAuxiliaryArea>?,
        val candidateArea: LayoutCandidateArea?,
        val longPressDelay: Double?,
        val split: LayoutSplitConfiguration?,
        // Null is omitted, so layouts without these settings keep their earlier identity.
        val t9FirstKeyBehavior: T9FirstKeyBehavior? = null,
        val t9QuickSymbols: String? = null,
    )

    /**
     * The only identity entry point. [encode] is injectable; a throw yields [LayoutRuntimeIdentity.Unproven],
     * never an id posing as content identity and never a failure treated as equal.
     */
    fun identity(
        definition: LayoutDefinition,
        encode: (LayoutDefinition) -> String = ::canonicalEncode,
    ): LayoutRuntimeIdentity =
        try {
            LayoutRuntimeIdentity.Proven(encode(definition))
        } catch (_: Throwable) {
            LayoutRuntimeIdentity.Unproven(definition.id)
        }

    /**
     * Deterministic encoding: sorted field order, no randomness and no hash iteration, so two calls
     * on equal content produce the same string in any process.
     */
    fun canonicalEncode(definition: LayoutDefinition): String {
        val canonical =
            Canonical(
                id = definition.id,
                input = definition.input,
                rows = definition.rows,
                auxiliaryAreas = definition.auxiliaryAreas,
                candidateArea = definition.candidateArea,
                longPressDelay = definition.longPressDelay,
                split = definition.split,
                t9FirstKeyBehavior = definition.t9FirstKeyBehavior,
                t9QuickSymbols = definition.t9QuickSymbols,
            )
        return LayoutJson.compact.encodeToString(Canonical.serializer(), canonical)
    }
}

/**
 * Stable, content-level runtime identity, consistent across calls and processes.
 *
 * Derived from the behavior-affecting content (id / input / rows including actions, long-press
 * alternates and width weights / auxiliaryAreas / longPressDelay / split); name and note changes do
 * not alter it.
 */
val LayoutDefinition.runtimeIdentity: LayoutRuntimeIdentity
    get() = LayoutIdentityEncoder.identity(this)
