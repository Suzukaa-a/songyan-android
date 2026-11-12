package app.songyan.ime.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** JSON shapes spoken over the [ScriptProxy] Binder surface. The wire format is open: unknown keys
 *  are ignored, so a newer payload can travel to an older client without breaking it. */
internal object ScriptProxyProto {
    /** The proxy surface version: 2 added the audio run (`runAudio`), 3 carries the host
     *  capability set in `hello`. */
    const val VERSION = 3
    val json = Json { ignoreUnknownKeys = true }

    fun error(message: String): String =
        buildJsonObject {
            put("error", "failed")
            put("message", message)
        }.toString()

    fun unavailable(what: String): String =
        buildJsonObject {
            put("error", "unavailable")
            put("message", what)
        }.toString()

    fun negotiation(
        serverVersion: Int,
        commonVersion: Int,
    ): String =
        buildJsonObject {
            put("serverVersion", serverVersion)
            put("version", commonVersion)
        }.toString()

    fun entries(entries: List<ScriptEntry>): String =
        buildJsonObject {
            put(
                "entries",
                buildJsonArray {
                    entries.forEach { entry ->
                        add(entry.toJson())
                    }
                },
            )
        }.toString()

    fun entry(entry: ScriptEntry): String = buildJsonObject { put("entry", entry.toJson()) }.toString()

    fun header(header: ScriptHeader): String = buildJsonObject { put("header", header.toJson()) }.toString()

    fun source(bytes: ByteArray?): String =
        buildJsonObject {
            put("source", bytes?.toString(Charsets.ISO_8859_1) ?: "")
        }.toString()

    fun outcome(outcome: ScriptRunOutcome): String = buildJsonObject { put("outcome", outcome.toJson()) }.toString()

    fun ack(): String = buildJsonObject { put("ok", true) }.toString()

    // --- decoding ---

    fun parseEntries(payload: String): List<ScriptEntry> =
        json
            .parseToJsonElement(payload)
            .jsonObject["entries"]!!
            .jsonArray
            .map { it.jsonObject.toEntry() }

    fun parseEntry(payload: String): ScriptEntry {
        val node = json.parseToJsonElement(payload).jsonObject
        val entry = requireNotNull(node["entry"]?.jsonObject) { "the proxy returned no entry: " + payload }
        return entry.toEntry()
    }

    fun parseHeader(payload: String): ScriptHeader =
        json
            .parseToJsonElement(payload)
            .jsonObject["header"]!!
            .jsonObject
            .toHeader()

    fun parseSource(payload: String): ByteArray? {
        val text =
            json
                .parseToJsonElement(payload)
                .jsonObject["source"]!!
                .jsonPrimitive.content
        return text.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.ISO_8859_1)
    }

    fun parseOutcome(payload: String): ScriptRunOutcome {
        val node = json.parseToJsonElement(payload).jsonObject
        val outcome =
            node["outcome"]?.jsonObject
                ?: return ScriptRunOutcome.Failed(
                    code = node["error"]?.jsonPrimitive?.contentOrNull ?: "unavailable",
                    message = node["message"]?.jsonPrimitive?.contentOrNull ?: "the proxy returned no outcome",
                    line = 0,
                )
        return outcome.toOutcome()
    }

    fun parseError(payload: String): String? {
        val node = json.parseToJsonElement(payload).jsonObject
        return node["error"]?.jsonPrimitive?.contentOrNull
    }

    fun parseCommonVersion(payload: String): Int =
        json
            .parseToJsonElement(payload)
            .jsonObject["version"]!!
            .jsonPrimitive.content
            .toInt()

    // --- element builders ---

    private fun ScriptEntry.toJson(): JsonElement =
        buildJsonObject {
            put("id", id)
            put("name", name)
            put("description", description ?: "")
            put("enabled", enabled)
            put("order", order)
            put("apiVersion", apiVersion)
            put("connects", JsonArray(connects.map { JsonPrimitive(it) }))
            put("needsAudio", needsAudio)
            put("availableOnThisHost", availableOnThisHost)
            put("confirmationPending", confirmationPending)
            put(
                "settings",
                JsonArray(
                    settings.map { field ->
                        buildJsonObject {
                            put("key", field.key)
                            put("kind", field.kind.name)
                            put("label", field.label)
                            put("secret", field.secret)
                        }
                    },
                ),
            )
            put(
                "originKind",
                when (sourceOrigin) {
                    is ScriptSourceOrigin.Pasted -> "pasted"
                    is ScriptSourceOrigin.File -> "file"
                    is ScriptSourceOrigin.Link -> "link"
                    ScriptSourceOrigin.Edited -> "edited"
                },
            )
            (sourceOrigin as? ScriptSourceOrigin.File)?.let { put("originName", it.displayName) }
            (sourceOrigin as? ScriptSourceOrigin.Link)?.let {
                put("originUrl", it.url)
                put("originUpdate", it.updateUrl)
            }
        }

    private fun JsonObject.toEntry(): ScriptEntry =
        ScriptEntry(
            id = this["id"]!!.jsonPrimitive.content,
            name = this["name"]!!.jsonPrimitive.content,
            description = this["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
            enabled = this["enabled"]?.jsonPrimitive?.contentOrNull == "true",
            order = (this["order"]?.jsonPrimitive?.contentOrNull ?: "0").toInt(),
            apiVersion = (this["apiVersion"]?.jsonPrimitive?.contentOrNull ?: "1").toInt(),
            connects = (this["connects"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content },
            needsAudio = this["needsAudio"]?.jsonPrimitive?.contentOrNull == "true",
            availableOnThisHost = this["availableOnThisHost"]?.jsonPrimitive?.contentOrNull == "true",
            confirmationPending = this["confirmationPending"]?.jsonPrimitive?.contentOrNull == "true",
            settings =
                (this["settings"]?.jsonArray ?: JsonArray(emptyList())).map { node ->
                    val field = node.jsonObject
                    ScriptSettingField(
                        field["key"]!!.jsonPrimitive.content,
                        ScriptSettingKind.valueOf(field["kind"]!!.jsonPrimitive.content),
                        field["label"]!!.jsonPrimitive.content,
                        field["secret"]?.jsonPrimitive?.contentOrNull == "true",
                    )
                },
            sourceOrigin =
                when (this["originKind"]?.jsonPrimitive?.contentOrNull) {
                    "file" -> ScriptSourceOrigin.File(this["originName"]!!.jsonPrimitive.content)
                    "link" ->
                        ScriptSourceOrigin.Link(
                            this["originUrl"]!!.jsonPrimitive.content,
                            this["originUpdate"]!!.jsonPrimitive.content,
                        )
                    "edited" -> ScriptSourceOrigin.Edited
                    else -> ScriptSourceOrigin.Pasted
                },
        )

    private fun ScriptHeader.toJson(): JsonElement =
        when (this) {
            is ScriptHeader.Parsed ->
                buildJsonObject {
                    put("kind", "parsed")
                    put("apiVersion", apiVersion)
                    put("name", name)
                    put("description", description ?: "")
                    put("connects", JsonArray(connects.map { JsonPrimitive(it) }))
                    put("needsAudio", needsAudio)
                    put(
                        "settings",
                        JsonArray(
                            settings.map { field ->
                                buildJsonObject {
                                    put("key", field.key)
                                    put("kind", field.kind.name)
                                    put("label", field.label)
                                    put("secret", field.secret)
                                }
                            },
                        ),
                    )
                }
            is ScriptHeader.Refused ->
                buildJsonObject {
                    put("kind", "refused")
                    put("code", code)
                    put("line", line)
                    put("message", message)
                    put("truncated", truncated)
                }
        }

    private fun JsonObject.toHeader(): ScriptHeader =
        if (this["kind"]?.jsonPrimitive?.contentOrNull == "parsed") {
            ScriptHeader.Parsed(
                apiVersion = this["apiVersion"]!!.jsonPrimitive.content.toInt(),
                name = this["name"]!!.jsonPrimitive.content,
                description = this["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                connects = (this["connects"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content },
                settings =
                    (this["settings"]?.jsonArray ?: JsonArray(emptyList())).map { node ->
                        val field = node.jsonObject
                        ScriptSettingField(
                            field["key"]!!.jsonPrimitive.content,
                            ScriptSettingKind.valueOf(field["kind"]!!.jsonPrimitive.content),
                            field["label"]!!.jsonPrimitive.content,
                            field["secret"]?.jsonPrimitive?.contentOrNull == "true",
                        )
                    },
                needsAudio = this["needsAudio"]?.jsonPrimitive?.contentOrNull == "true",
            )
        } else {
            ScriptHeader.Refused(
                code = this["code"]?.jsonPrimitive?.contentOrNull ?: "header",
                line = (this["line"]?.jsonPrimitive?.contentOrNull ?: "0").toInt(),
                message = this["message"]?.jsonPrimitive?.contentOrNull ?: "",
                truncated = this["truncated"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }

    private fun ScriptRunOutcome.toJson(): JsonElement =
        when (this) {
            is ScriptRunOutcome.Success ->
                buildJsonObject {
                    put("kind", "success")
                    put("outputs", JsonArray(outputs.map { JsonPrimitive(it) }))
                    put("console", JsonArray(consoleLines.map { JsonPrimitive(it) }))
                    put("consoleTruncated", consoleTruncated)
                }
            is ScriptRunOutcome.Failed ->
                buildJsonObject {
                    put("kind", "failed")
                    put("code", code)
                    put("message", message)
                    put("line", line)
                }
            ScriptRunOutcome.Cancelled -> buildJsonObject { put("kind", "cancelled") }
        }

    private fun JsonObject.toOutcome(): ScriptRunOutcome =
        when (this["kind"]?.jsonPrimitive?.contentOrNull) {
            "success" ->
                ScriptRunOutcome.Success(
                    outputs = (this["outputs"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content },
                    consoleLines =
                        (
                            this["console"]?.jsonArray ?: JsonArray(
                                emptyList(),
                            )
                        ).map { it.jsonPrimitive.content },
                    consoleTruncated = this["consoleTruncated"]?.jsonPrimitive?.contentOrNull == "true",
                )
            "failed" ->
                ScriptRunOutcome.Failed(
                    code = this["code"]?.jsonPrimitive?.contentOrNull ?: "script",
                    message = this["message"]?.jsonPrimitive?.contentOrNull ?: "",
                    line = (this["line"]?.jsonPrimitive?.contentOrNull ?: "0").toInt(),
                )
            else -> ScriptRunOutcome.Cancelled
        }

    /** Maps the shared CLI's canonical header report (the same JSON macOS prints) to the public
     * [ScriptHeader]. The secrets list is merged into the settings fields: the confirmation page
     * shows both in declaration order. */
    fun headerFromCliReport(report: String): ScriptHeader {
        val root = json.parseToJsonElement(report).jsonObject
        if (root["ok"]?.jsonPrimitive?.contentOrNull == "true") {
            val header = root["header"]!!.jsonObject
            val needs = header["needs"]?.jsonArray ?: JsonArray(emptyList())
            val secrets = header["secrets"]?.jsonArray ?: JsonArray(emptyList())
            val secretKeys = secrets.map { it.jsonObject["key"]!!.jsonPrimitive.content }.toSet()
            return ScriptHeader.Parsed(
                apiVersion = header["api"]!!.jsonPrimitive.content.toInt(),
                name = header["name"]!!.jsonPrimitive.content,
                description = header["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
                connects = (header["connects"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content },
                settings =
                    (header["settings"]?.jsonArray ?: JsonArray(emptyList())).map { node ->
                        val field = node.jsonObject
                        ScriptSettingField(
                            key = field["key"]!!.jsonPrimitive.content,
                            kind =
                                when (field["kind"]!!.jsonPrimitive.content) {
                                    "url" -> ScriptSettingKind.URL
                                    "choice" -> ScriptSettingKind.CHOICE
                                    else -> ScriptSettingKind.TEXT
                                },
                            label = field["label"]!!.jsonPrimitive.content,
                            secret = false,
                        )
                    } +
                        secrets.map { node ->
                            val field = node.jsonObject
                            ScriptSettingField(
                                key = field["key"]!!.jsonPrimitive.content,
                                kind = ScriptSettingKind.TEXT,
                                label = field["label"]!!.jsonPrimitive.content,
                                secret = true,
                            )
                        },
                needsAudio = needs.any { it.jsonPrimitive.content == "audio" },
            )
        }
        return ScriptHeader.Refused(
            code = root["error"]?.jsonPrimitive?.contentOrNull ?: "header",
            line = (root["line"]?.jsonPrimitive?.contentOrNull ?: "0").toInt(),
            message = root["message"]?.jsonPrimitive?.contentOrNull ?: "",
            truncated = root["messageTruncated"]?.jsonPrimitive?.contentOrNull == "true",
        )
    }

    /** The on-disk catalog snapshot: the same shape as the entries payload, so the reader in any
     *  process decodes it with one code path. */
    fun snapshot(entries: List<ScriptEntry>): String = entries(entries)

    fun parseSnapshot(payload: String): List<ScriptEntry> = parseEntries(payload)
}
