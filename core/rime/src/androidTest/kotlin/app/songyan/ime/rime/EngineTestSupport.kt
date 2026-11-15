package app.songyan.ime.rime

/** Helpers shared by the real-engine instrumented tests of this module. */
internal object EngineTestSupport {
    const val BUILTIN_ASSET_ROOT = "rime/builtin"
    const val FIXTURE_ASSET_ROOT = "engine-fixtures"

    /** Feeds each character of [keys] as one key press. */
    suspend fun type(
        engine: RimeEngine,
        keys: String,
    ) {
        for (char in keys) {
            engine.processKey(
                RimeKey(requireNotNull(RimeKey.baseKeycode(char)) { "no keycode for '$char'" }),
            )
        }
    }

    /** Candidate texts of the current page. */
    suspend fun candidateTexts(engine: RimeEngine): List<String> = engine.readSnapshot().candidates.map { it.text }
}
