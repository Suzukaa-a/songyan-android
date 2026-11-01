package app.songyan.ime.packages

/**
 * One failed step of a scheme-package operation, mirroring iOS `RecipeInstallError`.
 *
 * The subtype is stable API; [message] carries developer-facing detail only. User-facing copy is
 * selected by the caller from the subtype and lives in `strings.xml`, so this module never
 * hardcodes UI text.
 */
sealed class RecipeInstallError(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    /** A recipe or package manifest is malformed; [detail] names the offending statement. */
    class InvalidManifest(
        val detail: String,
    ) : RecipeInstallError("invalid manifest: " + detail)

    /** A resource, patch target or file tree failed validation; [detail] names the rule. */
    class InvalidResource(
        val detail: String,
    ) : RecipeInstallError("invalid resource: " + detail)

    /** Schema files the package advertises or includes are missing. */
    class MissingSchemaFiles(
        val files: List<String>,
    ) : RecipeInstallError("missing schema files: " + files.joinToString(", "))

    /** A download failed with [statusCode]. */
    class Http(
        val statusCode: Int,
    ) : RecipeInstallError("download failed with HTTP " + statusCode)

    /** Another installation is running; the caller retries. */
    data object Busy : RecipeInstallError("installation already in progress")

    /** The engine build does not provide these components. */
    class MissingComponents(
        val components: List<String>,
    ) : RecipeInstallError("missing engine components: " + components.joinToString(", "))

    /** The scheme needs grammar models that were not supplied with it. */
    class MissingGrammarModels(
        val files: List<String>,
    ) : RecipeInstallError("missing grammar models: " + files.joinToString(", "))

    /** The engine failed to deploy the installed configuration. */
    class Deployment(
        val detail: String,
    ) : RecipeInstallError("deployment failed: " + detail)
}
