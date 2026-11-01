package app.songyan.ime.userconfig

import app.songyan.ime.packages.BoundedYaml
import app.songyan.ime.packages.UserConfigSafety
import java.io.File

/**
 * The backstop behind the checks on the user's files: after librime compiled a deployment, every
 * file-naming value in the compiled build files is checked at its real place, whatever patch
 * spelling produced it. A value that is absolute, climbs out with `..`, or is otherwise not a
 * plain relative name fails the deployment, so librime never opens it when the keyboard runs.
 */
object CompiledConfigGuard {
    private const val MAX_BYTES = 32L * 1024 * 1024

    /**
     * Throws [UserConfigException] naming the compiled file at fault. A Lua component the compiled
     * files name must be one the source ships ([shipped]) or one the user agreed to ([confirmed]):
     * a patch can assemble an entry from pieces no check on the files reads whole.
     */
    fun check(
        compiledDir: File,
        shipped: Set<String> = emptySet(),
        confirmed: Set<String> = emptySet(),
    ) {
        val files =
            compiledDir
                .listFiles { file -> file.isFile && file.name.endsWith(".yaml") }
                .orEmpty()
                .sortedBy { it.name }
        for (file in files) {
            if (file.length() > MAX_BYTES) throw UserConfigException(file.name, UserConfigProblem.TOO_LARGE)
            val parsed =
                try {
                    BoundedYaml.loadAsText(file.readText())
                } catch (e: Exception) {
                    throw UserConfigException(file.name, UserConfigProblem.INVALID_YAML, e.message?.take(80))
                } catch (_: StackOverflowError) {
                    throw UserConfigException(file.name, UserConfigProblem.INVALID_YAML, "nested too deeply")
                }
            UserConfigSafety.unsafeReference(parsed)?.let {
                throw UserConfigException(file.name, UserConfigProblem.UNSAFE_REFERENCE, it.take(80))
            }
            UserConfigSafety.luaComponents(parsed).firstOrNull { it !in shipped && it !in confirmed }?.let {
                throw UserConfigException(file.name, UserConfigProblem.UNCONFIRMED_SCRIPT, it.take(80))
            }
        }
    }
}
