package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateBarDefinition

/**
 * Candidate-area fixtures for the appearance tests.
 *
 * The appearance module's tests need a candidate-area document in a couple of shapes; these helpers
 * keep them readable without duplicating the defaults in every test.
 */
internal object CandidateBarDefinitionAlias {
    val GRID: CandidateBarDefinition.Expanded.Mode = CandidateBarDefinition.Expanded.Mode.GRID

    fun default(): CandidateBarDefinition = CandidateBarDefinition.systemDefault

    fun withIdentity(id: String): CandidateBarDefinition = CandidateBarDefinition.systemDefault.copy(id = id, name = id)

    fun systemDefaultWithHeight(height: Double): CandidateBarDefinition =
        CandidateBarDefinition.systemDefault.copy(
            collapsed = CandidateBarDefinition.systemDefault.collapsed.copy(height = height),
        )
}
