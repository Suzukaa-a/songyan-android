package app.songyan.ime.deploy

import app.songyan.ime.storage.AppStorage
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Coarse deployment state shared between the :deploy process and the UI process. */
enum class DeployState { IDLE, PREPARING, FAILED, READY }

/**
 * One read of the status file. [updatedAtEpochMs] orders it against a later write and [tag] names
 * the request that wrote it, so a caller waiting for its own request cannot read another one's
 * result as its own.
 */
data class DeployStatusSnapshot(
    val state: DeployState,
    val updatedAtEpochMs: Long,
    val tag: String?,
) {
    companion object {
        val MISSING = DeployStatusSnapshot(DeployState.IDLE, 0L, null)
    }
}

/**
 * Cross-process deployment status for settings ("正在准备输入方案"/"准备失败").
 * The publication record itself is authoritative for readiness; this file only
 * adds in-flight and failure hints.
 *
 * Every write also records its wall-clock time: a caller that starts a deploy waits for a
 * status file newer than the one it saw, so a stale failure from an earlier attempt cannot be
 * mistaken for its own result.
 */
class DeployStatusStore(
    private val storage: AppStorage,
) {
    private val file: File get() = File(storage.rimeRoot, "deploy-status.json")

    fun read(): DeployState = readSnapshot().state

    fun readSnapshot(): DeployStatusSnapshot =
        try {
            if (!file.isFile) {
                DeployStatusSnapshot.MISSING
            } else {
                val text = file.readText()
                val state =
                    runCatching { DeployState.valueOf(field(text, "state") ?: "IDLE") }.getOrDefault(DeployState.IDLE)
                DeployStatusSnapshot(state, longField(text, "updatedAt") ?: 0L, field(text, "tag"))
            }
        } catch (_: IOException) {
            DeployStatusSnapshot.MISSING
        }

    fun write(
        state: DeployState,
        tag: String? = null,
    ) {
        storage.ensureBaseDirectories()
        val temp = File(storage.rimeRoot, "deploy-status.json.tmp")
        val updatedAt = System.currentTimeMillis()
        val tagField = if (tag.isNullOrBlank()) "" else ",\"tag\":\"" + tag.replace("\\", "") + "\""
        temp.writeText("{\"state\":\"" + state.name + "\",\"updatedAt\":" + updatedAt + tagField + "}")
        try {
            Files.move(
                temp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: IOException) {
            temp.renameTo(file)
        }
    }

    private fun field(
        json: String,
        key: String,
    ): String? = Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)

    private fun longField(
        json: String,
        key: String,
    ): Long? =
        Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*(-?[0-9]+)")
            .find(json)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
}
