package app.songyan.ime.deploy

import java.io.File

/**
 * Runs a full librime deployment in the deploy process and reports what the
 * deployed resources actually provide. Implemented in :core:rime; the app only
 * decides when to deploy and what to publish.
 */
interface RimeDeployRunner {
    /**
     * Creates a fresh engine over [sharedDataDir] (extracted source) and
     * [userDataDir] (deploy-only work directory), running librime's full
     * maintenance synchronously before returning.
     *
     * [afterDeploy] runs on the deployed engine before it closes, only when the deployment
     * succeeded. An engine failure inside it fails the outcome; other exceptions propagate.
     */
    suspend fun deployFull(
        sharedDataDir: File,
        userDataDir: File,
        appName: String,
        appVersion: String,
        afterDeploy: (suspend (DeployedEngine) -> Unit)? = null,
    ): DeployOutcome
}

/** The engine of a successful deployment, still open over its work directory. */
interface DeployedEngine {
    /** The build directory the deployment compiled into. */
    val compiledDir: File

    /** The engine's spelling projection of [schemaId] (`rb_syllable_display_map_json`), or null. */
    suspend fun sourceProjection(schemaId: String): ByteArray?

    /** A config scalar of a compiled schema, or null when absent. */
    suspend fun schemaValue(
        schemaId: String,
        key: String,
    ): String?

    /** Compiles one schema file into [compiledDir] without listing it in `schema_list`. */
    suspend fun deploySchema(file: File): Boolean

    /**
     * Whether every key of [keys], pressed twice in a row on the compiled schema [schemaId], reaches
     * its composition as typed. The second press arrives while a candidate menu may be open, which
     * is when a scheme's own processors can claim a digit. Leaves no composition behind.
     */
    suspend fun composesKeys(
        schemaId: String,
        keys: String,
    ): Boolean
}

data class DeployOutcome(
    val success: Boolean,
    val compiledDir: File,
    val engineVersion: String,
    val deployedSchemaIds: List<String>,
    val lastError: String?,
)
