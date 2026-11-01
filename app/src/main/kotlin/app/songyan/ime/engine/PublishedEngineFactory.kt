package app.songyan.ime.engine

import app.songyan.ime.keyboard.engine.EngineBackend
import java.io.File

/** A started engine plus its keyboard-facing backend. */
interface EngineHandle {
    val backend: EngineBackend

    /** Stops the engine; safe to call more than once. */
    suspend fun close()
}

/**
 * Creates the IME-process engine for one published generation. Implemented in the
 * app around :core:rime so the rest of the app never touches the native bridge.
 */
fun interface PublishedEngineFactory {
    suspend fun create(
        sharedDataDir: File,
        userDataDir: File,
        compiledDir: File,
        appName: String,
        appVersion: String,
    ): EngineHandle
}
