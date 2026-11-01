package app.songyan.ime.packages

/**
 * Disk budgets for scheme packages, not claims about the engine's resident memory. Ported from the
 * iOS `RecipeResourceBudget`; models are never held as in-memory bytes.
 */
object RecipeResourceBudget {
    const val SOURCE_TOTAL: Int = 256 * 1024 * 1024
    const val SOURCE_FILE: Int = 128 * 1024 * 1024
    const val MODEL_FILE: Int = 512 * 1024 * 1024
    const val MODEL_TOTAL: Int = 1024 * 1024 * 1024
    const val ARCHIVE_FILE: Int = 1536 * 1024 * 1024
    const val BUFFER_BYTES: Int = 1024 * 1024
    private const val MAXIMUM_FILE_COUNT = 2000

    /**
     * Large binary resources passed by file reference, never loaded as bytes: grammar models
     * (`*.gram`) and root-level prediction databases (`*.db`, checked by [Prediction]).
     */
    fun isModel(path: String): Boolean = path.endsWith(".gram") || Prediction.isDatabasePath(path)

    fun maximumFileBytes(path: String): Int = if (isModel(path)) MODEL_FILE else SOURCE_FILE

    fun validate(sizes: Map<String, Int>) = validate(sizes) { it }

    internal fun validate(
        sizes: Map<String, Int>,
        logicalPath: (String) -> String,
    ) {
        if (sizes.size >
            MAXIMUM_FILE_COUNT
        ) {
            throw RecipeInstallError.InvalidResource("more than $MAXIMUM_FILE_COUNT files")
        }
        var sources = 0L
        var models = 0L
        for ((path, size) in sizes) {
            if (!SchemeSourceCheck.safe(path)) throw RecipeInstallError.InvalidResource(path)
            val logical = logicalPath(path)
            if (size < 0 || size > maximumFileBytes(logical)) {
                throw RecipeInstallError.InvalidResource("file exceeds its size limit: " + path)
            }
            if (isModel(logical)) models += size else sources += size
        }
        if (sources > SOURCE_TOTAL || models > MODEL_TOTAL) {
            throw RecipeInstallError.InvalidResource("scheme source or model files exceed the total size limit")
        }
    }
}
