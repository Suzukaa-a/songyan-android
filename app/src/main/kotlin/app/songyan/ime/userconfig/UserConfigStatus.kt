package app.songyan.ime.userconfig

import app.songyan.ime.deploy.DeploymentCoordinator
import app.songyan.ime.packages.RecipeInstallError
import app.songyan.ime.storage.AppStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * How the user's configuration last fared, written by the deploy process for the settings screen:
 * applied, refused (with the file at fault when one is known), or left out of a deployment of
 * [sourceId] that could not be built with it.
 */
data class UserConfigStatus(
    val state: State,
    /** The applied files' identity ([UserConfigFiles.digest]) when [state] is [State.APPLIED]. */
    val digest: String? = null,
    val file: String? = null,
    /** A [UserConfigProblem] name, or the coordinator's deploy failure. */
    val problem: String? = null,
    val sourceId: String? = null,
    val detail: String? = null,
) {
    enum class State(
        val wire: String,
    ) {
        APPLIED("applied"),
        FAILED("failed"),
        SKIPPED("skipped"),
    }

    fun encode(): String =
        buildJsonObject {
            put("state", state.wire)
            digest?.let { put("digest", it) }
            file?.let { put("file", it) }
            problem?.let { put("problem", it) }
            sourceId?.let { put("sourceId", it) }
            detail?.let { put("detail", it) }
        }.toString()

    /** Reads and writes [AppStorage.configStatusFile]; a damaged file reads as no status. */
    class Store(
        private val storage: AppStorage,
    ) {
        fun read(): UserConfigStatus? = decode(runCatching { storage.configStatusFile.readText() }.getOrNull())

        fun write(status: UserConfigStatus) {
            val file = storage.configStatusFile
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, file.name + ".tmp")
            temp.writeText(status.encode())
            if (!temp.renameTo(file)) {
                file.writeText(status.encode())
                temp.delete()
            }
        }

        /** A deployment of [sourceId] used the configuration: an earlier note that it left it out is outdated. */
        fun deployedWith(sourceId: String) {
            val current = read() ?: return
            if (current.state == State.SKIPPED && current.sourceId == sourceId) {
                write(applied(UserConfigFiles.digest(storage.appliedConfigDir)))
            }
        }
    }

    companion object {
        fun applied(digest: String?) = UserConfigStatus(State.APPLIED, digest = digest)

        fun failed(
            file: String?,
            problem: String,
            detail: String? = null,
        ) = UserConfigStatus(State.FAILED, file = file, problem = problem, detail = detail)

        fun failed(error: Throwable): UserConfigStatus =
            when (error) {
                is UserConfigException -> failed(error.file, error.problem.name, error.detail ?: error.message)
                is RecipeInstallError.InvalidResource ->
                    failed(error.file, DeploymentCoordinator.DEPLOY_PROBLEM, error.detail)
                else -> failed(null, DeploymentCoordinator.DEPLOY_PROBLEM, error.message)
            }

        fun skipped(sourceId: String) = UserConfigStatus(State.SKIPPED, sourceId = sourceId)

        fun decode(text: String?): UserConfigStatus? {
            if (text.isNullOrBlank()) return null
            val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject ?: return null

            fun string(key: String) = (root[key] as? JsonPrimitive)?.contentOrNull
            val state = State.entries.firstOrNull { it.wire == string("state") } ?: return null
            return UserConfigStatus(
                state,
                string("digest"),
                string("file"),
                string("problem"),
                string("sourceId"),
                string("detail"),
            )
        }
    }
}
