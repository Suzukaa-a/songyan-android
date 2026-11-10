package app.songyan.ime.ime

/**
 * One user action of the twelve-key surface.
 *
 * The surface never talks to the engine: it reports what the user did and the caller's input model
 * decides what to send. [Kana] carries the row id and the flick direction, so the model owns the
 * whole kana-to-romaji table; the rest are the surrounding key actions.
 */
sealed interface Kana12Action {
    /** A kana key: [direction] [Kana12Direction.Center] is a plain tap. */
    data class Kana(
        val rowId: String,
        val direction: Kana12Direction,
    ) : Kana12Action

    /** The small/dakuten key: change the previously typed kana into its next variant. */
    data object Modify : Kana12Action

    data object CursorLeft : Kana12Action

    data object Symbols : Kana12Action

    data object Language : Kana12Action

    data object Backspace : Kana12Action

    data object Space : Kana12Action

    data object Enter : Kana12Action

    /**
     * The leading rail's system switch key. It is not a kana key: the caller keeps its existing
     * input-method switching, so this intent only says the user asked for another keyboard.
     */
    data object Globe : Kana12Action
}
