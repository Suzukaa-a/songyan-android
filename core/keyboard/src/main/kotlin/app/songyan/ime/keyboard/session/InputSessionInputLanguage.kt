package app.songyan.ime.keyboard.session

import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.engine.EngineOptionState
import app.songyan.ime.keyboard.engine.InputLanguageProfile
import app.songyan.ime.keyboard.engine.InputLanguageResolver

/**
 * Keeps [InputSession.inputLanguage] current without putting schema reads on the key path.
 *
 * Preparation runs on every editor start, so identical schema and declared conversion values are
 * skipped; each source schema's alphabet is read at most once per session. Only switches that
 * declare a script conversion take part: emoji candidates use a simplifier too and must not make
 * a traditional scheme look contradictory.
 */
internal suspend fun InputSession.refreshInputLanguage() {
    val schemaID = selectedSchemaID
    val conversions = activeScriptConversions(options)
    val key = conversions.sorted().joinToString(",")
    if (schemaID == inputLanguageSource && key == inputLanguageConversion) return
    inputLanguage = resolveScriptState(schemaID, alphabetOf(schemaID), conversions)
    inputLanguageSource = schemaID
    inputLanguageConversion = key
}

/**
 * The profile the declared script conversions prove. Several active declarations that all point the
 * same way keep that direction; a known traditional and a known simplified declaration together
 * have no proven winner and fail closed to the legacy profile instead of guessing. Unrelated
 * conversions the script rules do not know (emoji included) are ignored.
 */
private fun resolveScriptState(
    schemaID: String,
    alphabet: String,
    conversions: List<String>,
): InputLanguageProfile {
    val directions = conversions.mapNotNull(::directionOf).distinct()
    if (directions.size != 1) return InputLanguageResolver.resolve(schemaID, alphabet, null)
    val direction = directions.single()
    val config = conversions.first { directionOf(it) == direction }
    return InputLanguageResolver.resolve(schemaID, alphabet, config)
}

private enum class ScriptDirection { TRADITIONAL, SIMPLIFIED }

private fun directionOf(config: String): ScriptDirection? =
    when (config) {
        in TRADITIONAL_CONFIGS -> ScriptDirection.TRADITIONAL
        in SIMPLIFIED_CONFIGS -> ScriptDirection.SIMPLIFIED
        else -> null
    }

// The declared configs whose output direction the script rules name. Anything else is not a script
// state this keyboard can prove, so it must not vote.
private val TRADITIONAL_CONFIGS = setOf("s2t", "s2tw", "s2twp", "s2hk", "t2tw", "t2hk")
private val SIMPLIFIED_CONFIGS = setOf("t2s", "tw2s", "tw2sp", "hk2s")

private suspend fun InputSession.alphabetOf(schemaID: String): String {
    alphabetCache[schemaID]?.let { return it }
    val alphabet = engine.spellerAlphabet(schemaID).orEmpty()
    alphabetCache[schemaID] = alphabet
    return alphabet
}

/**
 * The OpenCC configs of the schema's declared script switches that are currently in force;
 * switches without a declared config prove nothing, so they contribute no entry.
 */
private fun activeScriptConversions(options: List<EngineOptionState>): List<String> =
    options
        .filter { it.value && it.descriptor.kind in SCRIPT_KINDS }
        .mapNotNull { it.descriptor.scriptConfig }
        .distinct()

/** Switch kinds that can describe a script state; emoji and full shape never do. */
private val SCRIPT_KINDS = setOf(EngineOptionKind.TRADITIONAL, EngineOptionKind.SIMPLIFIED)
