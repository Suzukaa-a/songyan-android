package app.songyan.ime.rime

import java.io.File

/**
 * Full deployment for the deployment process.
 *
 * The IME process must never compile dictionaries: it only loads an already
 * published build with [RimeEngine.createPrebuilt]. This entry point runs librime's
 * complete maintenance synchronously on a fresh engine, joins the maintenance
 * thread before returning and reports what the deployed build contains, so the
 * caller can publish it and let the IME reload.
 */
object RimeDeployment {
    /**
     * Outcome of one deployment.
     *
     * [success] is false for every failure mode (engine creation, maintenance start,
     * deployment notification failure, missing compiled output), so a caller never
     * publishes a build that the IME would fail to load.
     */
    data class DeployResult(
        val success: Boolean,
        /** Runnable build directory produced by librime (always [userDataDir]/build). */
        val compiledDir: File,
        /** librime version that produced the build. */
        val engineVersion: String,
        /** Schema ids the deployed build can activate. */
        val schemaIds: List<String>,
        /** Component availability probed in the live registry. */
        val components: Map<String, Boolean>,
        /** Deploy state observed after maintenance. */
        val deployState: RimeDeployState,
        /** First failure detail, or null on success. */
        val lastError: String?,
    )

    /**
     * Deploys [sharedDataDir] into [userDataDir] and returns the runnable build.
     *
     * [sharedDataDir] must contain the Rime source files (default.yaml,
     * *.schema.yaml, *.dict.yaml); dictionaries are compiled into
     * [userDataDir]/build. The engine is closed before returning, so no userdb lock
     * outlives the deployment process.
     *
     * [afterDeploy] runs on the still open engine once the deployment succeeded, for work that
     * needs the deployed build (compiling generated schemas into it). A [RimeException] from it
     * fails the deployment; any other exception propagates to the caller.
     */
    suspend fun deploy(
        sharedDataDir: File,
        userDataDir: File,
        appName: String = RimeEngine.DEFAULT_APP_NAME,
        appVersion: String? = null,
        afterDeploy: (suspend (RimeEngine) -> Unit)? = null,
    ): DeployResult {
        val compiledDir = File(userDataDir, "build")
        var engine: RimeEngine? = null
        var version = "-"
        var components: Map<String, Boolean> = emptyMap()
        try {
            // create() already runs the initial full deployment synchronously and
            // rejects an engine whose deployment reported failure.
            val opened = RimeEngine.create(sharedDataDir, userDataDir, appName, appVersion)
            engine = opened
            version = opened.version()
            components = opened.availableComponents()
            val schemaIds = opened.schemas().map { it.id }
            val state = opened.lastDeployState()
            if (schemaIds.isEmpty()) {
                return DeployResult(
                    success = false,
                    compiledDir = compiledDir,
                    engineVersion = version,
                    schemaIds = emptyList(),
                    components = components,
                    deployState = state,
                    lastError = "deployed build exposes no schema",
                )
            }
            val missing = components.filterValues { !it }.keys
            if (missing.isEmpty()) afterDeploy?.invoke(opened)
            return DeployResult(
                success = missing.isEmpty(),
                compiledDir = compiledDir,
                engineVersion = version,
                schemaIds = schemaIds,
                components = components,
                deployState = state,
                lastError = if (missing.isEmpty()) null else "engine components unavailable: ${missing.joinToString()}",
            )
        } catch (e: RimeException) {
            return DeployResult(
                success = false,
                compiledDir = compiledDir,
                engineVersion = version,
                schemaIds = emptyList(),
                components = components,
                deployState = RimeDeployState.FAILURE,
                lastError = e.message ?: "deployment failed",
            )
        } finally {
            engine?.close()
        }
    }
}
