package app.songyan.ime.packages

/**
 * Immutable file list for a reviewed upstream recipe or a checked user dictionary import,
 * mirroring the iOS `RecipeManifest`.
 */
data class RecipeManifest(
    val id: String,
    val repository: String,
    val revision: String,
    val recipePath: String,
    val license: String,
    val schemaID: String,
    val requiredComponents: List<String>,
    val files: List<File>,
    /** Absent for legacy reviewed manifests; user imports record the origin. */
    val origin: Origin? = null,
) {
    enum class Origin {
        USER_DICTIONARY,
        USER_SCHEME,
        USER_RECIPE,
    }

    data class File(
        val path: String,
        val sha256: String,
        val size: Int,
    )
}

/** One activated installation of a package. */
data class InstalledRecipe(
    val id: String,
    val revision: String,
    val generation: String,
    val schemaID: String,
    val activatedAt: java.time.Instant,
)

/** Active-installation view for the settings surface. */
data class RecipeStatus(
    val active: InstalledRecipe?,
)
