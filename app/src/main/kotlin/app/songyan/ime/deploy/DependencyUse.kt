package app.songyan.ime.deploy

import java.io.File

/** Whether a package in use reads the files of another stored package. */
internal object DependencyUse {
    private val SHARED_SUFFIXES = listOf(".dict.yaml", ".schema.yaml")
    private const val SHARED_NAME = "essay.txt"

    /**
     * True when [user] (the package in use) is deployed with a file of [other] that it lacks itself
     * ([provided], see [DeployOverlay.provided]), or carries a copy of one of [other]'s dictionaries,
     * schemes or word list (copied in when it was imported after [other]).
     */
    fun reads(
        user: File,
        provided: List<File>,
        other: File,
    ): Boolean {
        val own = other.canonicalFile
        if (own == user.canonicalFile) return false
        if (provided.any { it.canonicalFile.path.startsWith(own.path + File.separator) }) return true
        return own.listFiles().orEmpty().any { file ->
            file.isFile &&
                (SHARED_SUFFIXES.any { file.name.endsWith(it) } || file.name == SHARED_NAME) &&
                File(user, file.name).let { it.isFile && it.length() == file.length() }
        }
    }
}
