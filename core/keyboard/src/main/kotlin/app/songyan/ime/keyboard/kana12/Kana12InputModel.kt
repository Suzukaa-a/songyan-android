package app.songyan.ime.keyboard.kana12

/**
 * Engine state the front end has actually observed. The model never invents it: a null observation
 * means "unknown" and every replacement falls back to no-op, so a stale or unavailable snapshot
 * can never send a BackSpace the engine did not consume.
 */
data class Kana12Observation(
    /** Engine preedit text while composing; null when idle (committed, cleared, or no composition). */
    val composition: String? = null,
    /**
     * Caret offset inside the composition, counted from its start in Kotlin Char offsets (the unit
     * Android's editor and preedit APIs use). Kana and ASCII are one unit each; the front end
     * converts the engine's byte caret before passing it in.
     */
    val caret: Int = 0,
    /** A selection inside the composition or document; replacements stay off while one exists. */
    val hasSelection: Boolean = false,
)

/** What the front end must do for one key press. The model is pure: it never calls an engine. */
sealed interface Kana12Effect {
    /** Send every character of [romaji] to the engine in order; the composition then shows kana. */
    data class Append(
        override val kana: String,
        override val romaji: String,
    ) : Kana12Effect

    /**
     * Send exactly one BackSpace, then every character of [romaji]. One BackSpace deletes one
     * kana because the engine's speller already converted the composition to kana; never use the
     * romaji length, which lets extra BackSpaces leak to the editor and over-delete.
     */
    data class Replace(
        val previousKana: String,
        override val kana: String,
        override val romaji: String,
    ) : Kana12Effect

    /** Insert the text literally (punctuation, brackets, ellipsis); never an engine key token. */
    data class Literal(
        val text: String,
    ) : Kana12Effect

    /** Nothing to do: unsupported direction, no variant, or no proof of this model's tail. */
    data object None : Kana12Effect

    /** The kana this effect leaves in the composition, if any. */
    val kana: String?
        get() =
            when (this) {
                is Append -> kana
                is Replace -> kana
                is Literal, None -> null
            }

    /** The validated engine code this effect types, if any. */
    val romaji: String?
        get() =
            when (this) {
                is Append -> romaji
                is Replace -> romaji
                is Literal, None -> null
            }

    /** BackSpaces the front end must send before the romaji: exactly one for a replacement. */
    val backspaces: Int
        get() = if (this is Replace) 1 else 0
}

/**
 * Twelve-key kana input model: maps a row plus flick direction to append/replace/literal effects,
 * owns the repeated-tap cycle and the 小゛゜ replacement, and tracks whether the kana it last
 * emitted is still the tail of the composition the front end observes.
 *
 * Composition ownership: a replacement is only emitted while the front end observes exactly the
 * composition this model predicted after its previous effect, with the caret at the end. Foreign
 * input, a cursor move, a selection, a commit or a clear changes one of those facts, so the next
 * replacement degrades to append or no-op. There is no fallback to guessing.
 */
class Kana12InputModel(
    /** The "flick only" setting (default off): when on, repeated taps append instead of cycling. */
    var flickOnly: Boolean = false,
) {
    private data class Ownership(
        /** The row that produced [lastKana], or null after the 小゛゜ key touched the composition. */
        val rowId: String?,
        val cycleIndex: Int,
        val lastKana: String,
        val expectedComposition: String,
        val isTapChain: Boolean,
        val timestamp: Double,
    )

    private var ownership: Ownership? = null

    /**
     * Handles one kana-row press. Pass the state the front end has just observed before this press.
     *
     * [rowId] is one of [Kana12Layout.rowIds]; an unknown row produces no effect.
     */
    fun press(
        rowId: String,
        direction: Kana12FlickDirection = Kana12FlickDirection.Center,
        at: Double,
        observed: Kana12Observation?,
    ): Kana12Effect {
        synchronize(observed)
        return when (val output = Kana12Layout.output(rowId, direction)) {
            null -> Kana12Effect.None
            is Kana12KeyOutput.Literal -> {
                // A literal is committed to the document, which ends the engine composition.
                ownership = null
                Kana12Effect.Literal(output.literal)
            }
            is Kana12KeyOutput.Kana -> pressKana(rowId, direction, output.kana, at, observed)
        }
    }

    /**
     * Handles the 小゛゜ key: replaces the last kana this model typed with its next variant.
     *
     * The key does nothing unless the observed composition is still exactly the one this model
     * predicted, so a composition typed by another keyboard is never rewritten.
     */
    fun pressModifier(
        at: Double,
        observed: Kana12Observation?,
    ): Kana12Effect {
        synchronize(observed)
        val current = ownership ?: return Kana12Effect.None
        val nextKana = Kana12Layout.nextVariant(current.lastKana)
        val nextRomaji = nextKana?.let { Kana12RomajiTable.romaji(it) }
        if (nextKana == null || nextRomaji == null) {
            // No variant: nothing changes, but pressing another key ends a tap cycle.
            ownership = Ownership(null, 0, current.lastKana, current.expectedComposition, false, at)
            return Kana12Effect.None
        }
        val expected = replacingLastCharacter(current.expectedComposition, nextKana)
        ownership = Ownership(null, 0, nextKana, expected, false, at)
        return Kana12Effect.Replace(current.lastKana, nextKana, nextRomaji)
    }

    /**
     * Reconciles with newly observed state without pressing a key: a cursor move, a selection, a
     * commit, a clear, or input from another keyboard. Ownership only survives when the observation
     * still matches the composition this model predicted.
     */
    fun synchronize(observed: Kana12Observation?) {
        val current = ownership ?: return
        if (!matches(observed, current.expectedComposition)) ownership = null
    }

    /** Drops all state (keyboard hidden, input mode or scheme switched, model restarted). */
    fun reset() {
        ownership = null
    }

    private fun pressKana(
        rowId: String,
        direction: Kana12FlickDirection,
        kana: String,
        at: Double,
        observed: Kana12Observation?,
    ): Kana12Effect {
        val romaji = Kana12RomajiTable.romaji(kana)
        if (romaji == null) {
            ownership = null
            return Kana12Effect.None
        }
        val cycle = Kana12Layout.toggleCycle(rowId)
        val current = ownership
        if (!flickOnly &&
            direction == Kana12FlickDirection.Center &&
            cycle.isNotEmpty() &&
            current != null &&
            current.rowId == rowId &&
            current.isTapChain &&
            current.cycleIndex < cycle.size &&
            at >= current.timestamp &&
            at - current.timestamp < TOGGLE_INTERVAL
        ) {
            val nextIndex = (current.cycleIndex + 1) % cycle.size
            val nextKana = cycle[nextIndex]
            val nextRomaji = Kana12RomajiTable.romaji(nextKana) ?: return Kana12Effect.None
            val expected = replacingLastCharacter(current.expectedComposition, nextKana)
            ownership = Ownership(rowId, nextIndex, nextKana, expected, true, at)
            return Kana12Effect.Replace(current.lastKana, nextKana, nextRomaji)
        }
        val expected = predictedComposition(kana = kana, observed = observed)
        ownership =
            expected?.let {
                Ownership(
                    rowId = rowId,
                    cycleIndex = cycle.indexOf(kana).coerceAtLeast(0),
                    lastKana = kana,
                    expectedComposition = it,
                    isTapChain = direction == Kana12FlickDirection.Center,
                    timestamp = at,
                )
            }
        return Kana12Effect.Append(kana, romaji)
    }

    /**
     * The composition the engine must show after appending [kana], or null when the front end has
     * not given enough state to predict it (unknown observation, selection, caret mid-text).
     */
    private fun predictedComposition(
        kana: String,
        observed: Kana12Observation?,
    ): String? {
        if (observed == null || observed.hasSelection) return null
        val composition = observed.composition ?: ""
        if (observed.caret != composition.length) return null
        return composition + kana
    }

    /** Replaces the last character of a composition this model built from its own single-Char kana. */
    private fun replacingLastCharacter(
        composition: String,
        kana: String,
    ): String = composition.dropLast(1) + kana

    /**
     * Ownership requires the exact predicted text and the caret at its end; a shorter bounded
     * comparison could mistake a foreign composition for this model's tail.
     */
    private fun matches(
        observed: Kana12Observation?,
        expectedComposition: String,
    ): Boolean {
        if (observed == null || observed.hasSelection) return false
        val composition = observed.composition ?: ""
        return composition == expectedComposition && observed.caret == composition.length
    }

    companion object {
        /**
         * Repeated-tap window. The window is measured with the caller's monotonic timestamps; a
         * timestamp that goes backwards starts a fresh cycle instead of extending one.
         */
        const val TOGGLE_INTERVAL: Double = 0.8
    }
}
