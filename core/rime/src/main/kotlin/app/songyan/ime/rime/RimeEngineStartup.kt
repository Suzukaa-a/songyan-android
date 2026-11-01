package app.songyan.ime.rime

import java.io.File

/** Checks a prebuilt build directory before opening it; [fail] reports the problem and throws. */
internal fun validatePrebuiltDirectory(
    directory: File,
    fail: (String) -> Nothing,
) {
    if (!directory.isDirectory) fail("prebuilt directory missing: ${directory.path}")
    if (!File(directory, "default.yaml").isFile) {
        fail("prebuilt build/default.yaml missing: ${directory.path}")
    }
    val schemas = directory.listFiles { file -> file.name.endsWith(".schema.yaml") }.orEmpty()
    if (schemas.isEmpty()) fail("prebuilt directory has no compiled schemas: ${directory.path}")
    // Compiled schemas repeat librime's own version marker in their YAML header;
    // reading it keeps a build from another librime version out of this process.
    for (schema in schemas) {
        val header =
            schema.inputStream().use { stream ->
                val buffer = ByteArray(4096)
                val read = stream.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.UTF_8)
            }
        if (!header.contains("__build_info:") || !header.contains(RimeEngine.EXPECTED_RIME_VERSION)) {
            fail(
                "prebuilt schema version mismatch (requires librime ${RimeEngine.EXPECTED_RIME_VERSION}): ${schema.name}",
            )
        }
    }
}

internal fun createEngineHandle(
    dispatcher: RimeDispatcher,
    configuration: RimeEngine.Configuration,
): Result<Long> =
    // A full deployment (no compiled directory) may compile for minutes; opening a prebuilt
    // build must be quick. A handle that arrives after the wait gave up is destroyed on the
    // engine thread, so the process-wide engine slot is free for the next attempt.
    dispatcher.onEngineBlocking(
        timeoutMillis =
            if (configuration.compiledDir == null) {
                RimeDispatcher.DEPLOY_TIMEOUT_MILLIS
            } else {
                RimeDispatcher.DEFAULT_TIMEOUT_MILLIS
            },
        onAbandoned = { handle -> if (handle != 0L) RimeNative.nativeEngineDestroy(handle) },
    ) {
        if (configuration.compiledDir != null) {
            RimeNative.nativeEngineCreatePrebuilt(
                configuration.sharedDataDir.path,
                configuration.userDataDir.path,
                configuration.appName,
                configuration.appVersion,
                configuration.compiledDir.path,
            )
        } else {
            RimeNative.nativeEngineCreate(
                configuration.sharedDataDir.path,
                configuration.userDataDir.path,
                configuration.appName,
                configuration.appVersion,
            )
        }
    }
