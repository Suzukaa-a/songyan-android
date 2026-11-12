// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** One call the keyboard made into one extension; [error] is the extension's own message. */
internal data class StoredAddonCall(
    val kind: String,
    val atEpochMs: Long,
    val durationMs: Long,
    val error: String? = null,
)

/** One text tool from the last handshake, so a fresh process needs no binding to list them. */
internal data class StoredAddonTool(
    val id: String,
    val title: String,
)

/** The last handshake facts an extension answered. */
internal data class StoredAddonTools(
    val tools: List<StoredAddonTool> = emptyList(),
    val supportsPanel: Boolean = false,
    val supportsVoice: Boolean = false,
)

/** What the app keeps between processes about extensions: what the user turned on, and calls made. */
internal data class StoredAddonState(
    val enabled: Set<String> = emptySet(),
    val calls: Map<String, StoredAddonCall> = emptyMap(),
    /** Cached handshake facts per package; refreshed by the extensions page and after every enable. */
    val cached: Map<String, StoredAddonTools> = emptyMap(),
)

/**
 * The extension state file. It holds package names, the enabled switch and the last call per
 * package — never text the user typed and never the extension's code. Reads tolerate a missing or
 * damaged file by starting empty; a failed write leaves the previous file in place.
 */
internal class AddonStore(
    directory: File,
) : AddonStateStore {
    private val file = File(directory, "addons.json")

    override fun read(): StoredAddonState {
        if (!file.isFile) return StoredAddonState()
        val root =
            runCatching { Json.parseToJsonElement(file.readText()).jsonObject }.getOrNull()
                ?: return StoredAddonState()
        val enabled =
            runCatching {
                root["enabled"]?.jsonArray?.mapNotNull { element -> element.jsonPrimitive.contentOrNull }?.toSet()
            }.getOrNull().orEmpty()
        val calls =
            runCatching {
                root["calls"]
                    ?.jsonObject
                    .orEmpty()
                    .mapNotNull { (packageName, element) ->
                        val call = element as? JsonObject ?: return@mapNotNull null
                        val kind = call.string("kind") ?: return@mapNotNull null
                        packageName to
                            StoredAddonCall(
                                kind = kind,
                                atEpochMs = call.long("at") ?: 0L,
                                durationMs = call.long("duration") ?: 0L,
                                error = call.string("error"),
                            )
                    }.toMap()
            }.getOrDefault(emptyMap())
        val cached =
            runCatching {
                root["cached"]
                    ?.jsonObject
                    .orEmpty()
                    .mapNotNull { (packageName, element) ->
                        val facts = element as? JsonObject ?: return@mapNotNull null
                        val tools =
                            runCatching {
                                facts["tools"]
                                    ?.jsonArray
                                    ?.mapNotNull { tool ->
                                        val item = tool as? JsonObject ?: return@mapNotNull null
                                        val id = item.string("id") ?: return@mapNotNull null
                                        StoredAddonTool(id, item.string("title").orEmpty())
                                    }.orEmpty()
                            }.getOrDefault(emptyList())
                        packageName to
                            StoredAddonTools(
                                tools = tools,
                                supportsPanel = facts.boolean("panel") ?: false,
                                supportsVoice = facts.boolean("voice") ?: false,
                            )
                    }.toMap()
            }.getOrDefault(emptyMap())
        return StoredAddonState(enabled = enabled, calls = calls, cached = cached)
    }

    /** Writes atomically; false when the file could not be replaced (the previous file stays). */
    override fun write(state: StoredAddonState): Boolean {
        val directory = file.parentFile ?: return false
        if (!directory.isDirectory && !directory.mkdirs()) return false
        val text =
            buildJsonObject {
                put("enabled", buildJsonArray { state.enabled.sorted().forEach { add(JsonPrimitive(it)) } })
                put(
                    "calls",
                    buildJsonObject {
                        for ((packageName, call) in state.calls) {
                            put(
                                packageName,
                                buildJsonObject {
                                    put("kind", call.kind)
                                    put("at", call.atEpochMs)
                                    put("duration", call.durationMs)
                                    call.error?.let { put("error", it) }
                                },
                            )
                        }
                    },
                )
                put(
                    "cached",
                    buildJsonObject {
                        for ((packageName, cached) in state.cached) {
                            put(
                                packageName,
                                buildJsonObject {
                                    put(
                                        "tools",
                                        buildJsonArray {
                                            for (tool in cached.tools) {
                                                add(
                                                    buildJsonObject {
                                                        put("id", tool.id)
                                                        put("title", tool.title)
                                                    },
                                                )
                                            }
                                        },
                                    )
                                    put("panel", cached.supportsPanel)
                                    put("voice", cached.supportsVoice)
                                },
                            )
                        }
                    },
                )
            }.toString()
        val temporary = File(directory, "addons.json.tmp")
        val written =
            runCatching {
                // fsync before the rename: a battery pull right after enabling may not lose the switch.
                FileOutputStream(temporary).use { out ->
                    out.write(text.toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
                runCatching {
                    Files.move(
                        temporary.toPath(),
                        file.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE,
                    )
                    true
                }.getOrElse { temporary.renameTo(file) }
            }.getOrDefault(false)
        if (!written) temporary.delete()
        return written
    }

    private fun JsonObject.string(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.long(key: String): Long? = runCatching { this[key]?.jsonPrimitive?.longOrNull }.getOrNull()

    private fun JsonObject.boolean(key: String): Boolean? =
        runCatching { this[key]?.jsonPrimitive?.booleanOrNull }.getOrNull()
}

/** The state file as the registry sees it; replaceable so a test can count reads. */
internal interface AddonStateStore {
    fun read(): StoredAddonState

    fun write(state: StoredAddonState): Boolean
}

/**
 * The production store, resolved on first use. The directory is handed in as a lambda so creating
 * the registry does not call [android.content.Context.getFilesDir] (a disk access) on whatever
 * thread builds the registry: the service creates it in Application.onCreate, on the main thread.
 */
internal class LazyAddonStore(
    private val directory: () -> File,
) : AddonStateStore {
    private val delegate: AddonStore by lazy { AddonStore(directory()) }

    override fun read(): StoredAddonState = delegate.read()

    override fun write(state: StoredAddonState): Boolean = delegate.write(state)
}
