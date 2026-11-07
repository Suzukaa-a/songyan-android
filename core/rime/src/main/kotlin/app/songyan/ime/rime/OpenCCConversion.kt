package app.songyan.ime.rime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Why a conversion could not run: no engine library, no OpenCC data, or a conversion the bridge
 * refused. The keyboard maps this onto a localized message; the text here is for diagnostics only.
 */
class OpenCCConversionUnavailable(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Runs the app's own OpenCC conversions for the keyboard tools.
 *
 * This is deliberately separate from the engine's simplifier filter: librime is a process-wide
 * singleton with one single-thread scheduler, and a tool converts text the user already typed,
 * unrelated to any composition. The conversion itself runs on the standalone bridge, which owns its
 * converter, keeps no librime state and may therefore run on any background thread; this class only
 * resolves the shipped data and stays off the caller's thread (the IME main thread never converts).
 *
 * Each call loads the conversion's dictionaries because that is the bridge's contract (no converter
 * lives between calls, which is what makes concurrent tools safe). A tool run is a deliberate,
 * low-frequency action on at most one paragraph, and the result is shown before it is written.
 */
class OpenCCConversion(
    /** Where the bundled `opencc/` data lives right now; null while no generation is active. */
    private val dataDirectory: () -> File?,
) {
    /**
     * Converts [text] with the app's [config] conversion (a wire name such as `s2twp`).
     *
     * Fails closed with [OpenCCConversionUnavailable] instead of returning the input unchanged, so a
     * tool never writes text the user did not ask for.
     */
    suspend fun convert(
        text: String,
        config: String,
    ): String {
        // Resolving the directory reads the active publication, so it must not run on the caller's
        // thread; the conversion itself is disk-bound on a background lane of its own.
        val configPath =
            withContext(Dispatchers.IO) {
                val directory = dataDirectory() ?: throw OpenCCConversionUnavailable("no OpenCC data directory")
                File(directory, config + ".json")
            }
        return withContext(Dispatchers.Default) {
            if (!configPath.isFile) throw OpenCCConversionUnavailable("no " + configPath.name)
            if (!RimeNative.ensureLoaded()) throw OpenCCConversionUnavailable("the engine library is not loaded")
            val result = arrayOfNulls<String>(2)
            val status = RimeNative.nativeOpenccConvert(configPath.absolutePath, text, result)
            if (status != 0) {
                throw OpenCCConversionUnavailable("OpenCC status $status: ${result[1].orEmpty()}")
            }
            result[0] ?: throw OpenCCConversionUnavailable("OpenCC returned no text")
        }
    }
}
