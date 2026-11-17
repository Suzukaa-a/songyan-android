package app.songyan.ime.script

import android.content.res.AssetManager

/** The AI guide users copy, assembled for this host: the shared guide, plus the voice-script
 *  section only when the host can record audio, so every example in it imports here. */
object ScriptGuide {
    const val BASE_ASSET = "script/ai-guide.txt"
    const val AUDIO_ASSET = "script/ai-guide-audio.txt"

    fun assetsFor(capabilities: Set<ScriptHostCapability>): List<String> =
        if (ScriptHostCapability.AUDIO in capabilities) listOf(BASE_ASSET, AUDIO_ASSET) else listOf(BASE_ASSET)

    /** Blocking asset reads; call off the main thread. */
    fun read(
        assets: AssetManager,
        capabilities: Set<ScriptHostCapability>,
    ): String =
        assetsFor(capabilities).joinToString("") { path ->
            assets.open(path).bufferedReader().use {
                it.readText()
            }
        }
}
