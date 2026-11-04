package app.songyan.ime.deploy

import app.songyan.ime.storage.AppStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Where one deploy request stands. */
enum class RequestState {
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    ;

    val isTerminal: Boolean get() = this == SUCCEEDED || this == FAILED
}

/**
 * One request's status. [problem] names why it failed (a problem-kind name the requesting gateway
 * maps to its own copy); [paths] lists the files a missing-dependency failure still needs;
 * [sourceId] is the source a successful request activated or touched. While a running request
 * downloads, [downloadedBytes] says how much has arrived and [totalBytes] how much the server
 * announced (null when it did not say).
 */
data class RequestStatus(
    val state: RequestState,
    val problem: String? = null,
    val paths: List<String> = emptyList(),
    val sourceId: String? = null,
    val updatedAtEpochMs: Long = 0L,
    val downloadedBytes: Long? = null,
    val totalBytes: Long? = null,
    val detail: String? = null,
)

/** Hears how much of a download has arrived; [update] runs on the downloading thread for every chunk. */
fun interface DownloadProgress {
    /** [total] is the size the server announced, or null when it did not say. */
    fun update(
        bytes: Long,
        total: Long?,
    )

    companion object {
        val NONE = DownloadProgress { _, _ -> }
    }
}

/**
 * Per-request status files under `rime/requests/`, written by the deploy process and read by the
 * process that asked. Every request has its own file, so a waiter can never read a result that
 * belongs to another request, and a result written while the waiter was not looking (process
 * death, a missed broadcast) is still there afterwards.
 */
class DeployRequestStore(
    private val storage: AppStorage,
) {
    fun read(requestId: String): RequestStatus? {
        if (!DeployRequest.isValidRequestId(requestId)) return null
        val file = storage.requestFile(requestId)
        return try {
            if (!file.isFile || file.length() > MAX_BYTES) return null
            parse(file.readText())
        } catch (_: IOException) {
            null
        }
    }

    fun write(
        requestId: String,
        status: RequestStatus,
    ) {
        require(DeployRequest.isValidRequestId(requestId)) { "invalid request id" }
        storage.requestsDir.mkdirs()
        val target = storage.requestFile(requestId)
        val temp = File(storage.requestsDir, ".$requestId.tmp")
        temp.writeText(toJson(status.copy(updatedAtEpochMs = System.currentTimeMillis())))
        try {
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: IOException) {
            temp.renameTo(target)
        }
    }

    /** The newest [limit] request statuses, newest first; for the diagnostic report. */
    fun recent(limit: Int): List<RequestStatus> =
        storage.requestsDir
            .listFiles()
            .orEmpty()
            .filter { it.isFile && it.name.endsWith(".json") }
            .mapNotNull { file ->
                runCatching { if (file.length() <= MAX_BYTES) parse(file.readText()) else null }.getOrNull()
            }.sortedByDescending { it.updatedAtEpochMs }
            .take(limit)

    /** Status files nobody will read again; a waiter gives up long before this. */
    fun purgeOlderThan(
        ageMs: Long,
        now: Long = System.currentTimeMillis(),
    ) {
        for (file in storage.requestsDir.listFiles().orEmpty()) {
            if (file.lastModified() < now - ageMs) file.delete()
        }
    }

    companion object {
        private const val MAX_BYTES = 64L * 1024

        fun toJson(status: RequestStatus): String =
            buildJsonObject {
                put("state", status.state.name)
                status.problem?.let { put("problem", it) }
                status.detail?.let { put("detail", it) }
                if (status.paths.isNotEmpty()) put("paths", JsonArray(status.paths.map { JsonPrimitive(it) }))
                status.sourceId?.let { put("sourceId", it) }
                status.downloadedBytes?.let { put("downloaded", it) }
                status.totalBytes?.let { put("total", it) }
                put("updatedAt", status.updatedAtEpochMs)
            }.toString()

        fun parse(json: String): RequestStatus? {
            val root = runCatching { Json.parseToJsonElement(json) }.getOrNull() as? JsonObject ?: return null
            val state =
                (root["state"] as? JsonPrimitive)
                    ?.contentOrNull
                    ?.let { name -> RequestState.entries.firstOrNull { it.name == name } }
                    ?: return null
            return RequestStatus(
                state = state,
                problem = (root["problem"] as? JsonPrimitive)?.contentOrNull,
                detail = (root["detail"] as? JsonPrimitive)?.contentOrNull,
                paths = (root["paths"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty(),
                sourceId = (root["sourceId"] as? JsonPrimitive)?.contentOrNull,
                updatedAtEpochMs = (root["updatedAt"] as? JsonPrimitive)?.longOrNull ?: 0L,
                downloadedBytes = (root["downloaded"] as? JsonPrimitive)?.longOrNull,
                totalBytes = (root["total"] as? JsonPrimitive)?.longOrNull,
            )
        }
    }
}
