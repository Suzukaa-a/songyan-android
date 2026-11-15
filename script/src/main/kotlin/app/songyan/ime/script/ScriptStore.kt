package app.songyan.ime.script

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Script storage, versioned. Layout under [root]:
 *
 *   scripts/<id>/source.js    the stored bytes, verbatim (backup/export copy these)
 *   scripts/<id>/state.json   version, enabled, origin, parsed declarations, plain settings
 *   scripts/<id>/secret.bin   encrypted settings whose field is a secret (never in state.json)
 *   catalog.json              the order list; the single source of truth for [ScriptEntry.order]
 *
 * state.json carries [STATE_VERSION]; a reader that sees a newer version refuses rather than
 * guessing. Secret values are encrypted with a per-store data key; [keyWrap] hands that key to the
 * platform keystore in production and stays plain in JVM tests.
 */
class ScriptStore(
    private val root: File,
    private val keyWrap: KeyWrap,
    private val idGenerator: () -> String = { newId() },
) {
    /** Wraps the data-encryption key; production uses Android Keystore, tests use plain bytes. */
    interface KeyWrap {
        fun wrap(key: SecretKey): ByteArray

        fun unwrap(bytes: ByteArray): SecretKey
    }

    companion object {
        const val STATE_VERSION = 1
        private const val CIPHER = "AES/GCM/NoPadding"
        private const val BLOB_HEADER = "songyan-script-store"
        private val json = Json { prettyPrint = false }

        fun newId(): String {
            val bytes = ByteArray(8)
            SecureRandom().nextBytes(bytes)
            return bytes.joinToString("") { String.format("%02x", it) }
        }
    }

    private val dataKey: SecretKey by lazy {
        val keyFile = File(root, "keys/data.key")
        val raw: ByteArray =
            if (keyFile.isFile) {
                keyWrap.unwrap(keyFile.readBytes()).encoded
            } else {
                val generator = KeyGenerator.getInstance("AES")
                generator.init(256)
                val fresh = generator.generateKey()
                keyFile.parentFile?.mkdirs()
                keyFile.writeBytes(keyWrap.wrap(fresh))
                fresh.encoded
            }
        SecretKeySpec(raw, "AES")
    }

    fun list(hostCapabilities: Set<ScriptHostCapability> = ScriptHostCapability.DEFAULT): List<ScriptEntry> {
        val catalog = readCatalog()
        return catalog.mapIndexed { index, id -> readEntry(id, index, hostCapabilities) }.filterNotNull()
    }

    /** The script already stored with exactly these bytes, so importing the same file twice does
     *  not leave two identical rows. */
    fun idWithSource(source: ByteArray): String? =
        readCatalog().firstOrNull { id -> source(id)?.contentEquals(source) == true }

    fun source(id: String): ByteArray? {
        val file = File(root, "scripts/" + id + "/source.js")
        return if (file.isFile) file.readBytes() else null
    }

    /** Stores the source together with its parsed header, so the catalog never re-parses scripts. */
    fun add(
        source: ByteArray,
        header: ScriptHeader.Parsed,
        origin: ScriptSourceOrigin,
        hostCapabilities: Set<ScriptHostCapability> = ScriptHostCapability.DEFAULT,
    ): ScriptEntry {
        val id = idGenerator()
        val dir = File(root, "scripts/" + id)
        check(dir.mkdirs() && dir.isDirectory) { "cannot create " + dir }
        writeAtomically(File(dir, "source.js"), source)
        writeState(dir, newState(header, origin))
        val catalog = readCatalog().toMutableList()
        catalog.add(id)
        writeCatalog(catalog)
        return requireNotNull(readEntry(id, catalog.size - 1, hostCapabilities))
    }

    /** Confirms the stored declarations and settings. The first confirmation also enables the
     *  script; a later one (the re-confirmation an update asks for) keeps the user's choice. */
    fun confirm(
        id: String,
        declarations: ScriptHeader.Parsed,
        settings: Map<String, String>,
        fields: List<ScriptSettingField>,
        hostCapabilities: Set<ScriptHostCapability> = ScriptHostCapability.DEFAULT,
        clearSecrets: Set<String> = emptySet(),
    ): ScriptEntry {
        val dir = dirOf(id)
        val state = readState(dir).toMutableMap()
        // Matching the iOS store: the first confirmation of an import also turns the script on,
        // so the user does not need a second trip to the toggle. A re-confirmation after an update
        // keeps whatever the user chose - a script they switched off stays off.
        val firstConfirmation = (state["confirmedApi"]?.jsonPrimitive?.contentOrNull ?: "0").toInt() == 0
        state["confirmedApi"] = JsonPrimitive(declarations.apiVersion)
        state["confirmedConnects"] = buildJsonArray { declarations.connects.forEach { add(JsonPrimitive(it)) } }
        state["confirmedSettings"] = fields.toJsonArray()
        state["confirmedNeedsAudio"] = JsonPrimitive(declarations.needsAudio)
        state["declarationsChanged"] = JsonPrimitive(false)
        if (firstConfirmation) {
            state["enabled"] = JsonPrimitive(true)
        }
        writeState(dir, JsonObject(state))
        writeSettings(dir, settings, fields, clearSecrets)
        return requireNotNull(readEntry(id, hostCapabilities = hostCapabilities))
    }

    fun updateSettings(
        id: String,
        settings: Map<String, String>,
        fields: List<ScriptSettingField>,
        clearSecrets: Set<String> = emptySet(),
    ) {
        writeSettings(dirOf(id), settings, fields, clearSecrets)
    }

    fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        val dir = dirOf(id)
        val state = readState(dir).toMutableMap()
        state["enabled"] = JsonPrimitive(enabled)
        writeState(dir, JsonObject(state))
    }

    fun reorder(orderedIds: List<String>) {
        val current = readCatalog()
        require(orderedIds.sorted() == current.sorted()) { "reorder must mention every script once" }
        writeCatalog(orderedIds)
    }

    fun delete(id: String) {
        val dir = dirOf(id)
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
        writeCatalog(readCatalog() - id)
    }

    /** The stored non-secret settings, keyed by declaration key. Secrets live in a separate
     *  encrypted blob and never leave the proxy process, so a caller can prefill a form but
     *  cannot read a stored secret back. */
    fun plainSettings(id: String): Map<String, String> =
        readState(dirOf(id))["settings"]
            ?.jsonObject
            ?.mapValues { it.value.jsonPrimitive.contentOrNull ?: "" }
            ?: emptyMap()

    fun settingsOf(
        id: String,
        fields: List<ScriptSettingField>,
    ): Map<String, String> {
        val plain = readState(dirOf(id))["settings"]?.jsonObject ?: JsonObject(emptyMap())
        val secrets = readSecrets(dirOf(id))
        return fields.associate { field ->
            val stored =
                if (field.secret) {
                    secrets[field.key]
                } else {
                    plain[field.key]?.jsonPrimitive?.contentOrNull
                }
            val value = stored ?: ""
            // A choice always runs with one of its options: the first is the default.
            field.key to
                if (field.kind == ScriptSettingKind.CHOICE && value !in field.options) {
                    field.options.firstOrNull() ?: value
                } else {
                    value
                }
        }
    }

    /** True when the stored source's declarations differ from what the user last confirmed. */
    fun declarationsChanged(
        id: String,
        current: ScriptHeader.Parsed,
    ): Boolean {
        val state = readState(dirOf(id))
        if ((state["confirmedApi"]?.jsonPrimitive?.contentOrNull ?: "0").toInt() == 0) return true
        val confirmedConnects = state["confirmedConnects"]?.jsonArray ?: JsonArray(emptyList())
        if (confirmedConnects != buildJsonArray { current.connects.forEach { add(JsonPrimitive(it)) } }) {
            return true
        }
        val confirmedSettings = state["confirmedSettings"]?.jsonArray ?: return true
        // Any change the confirmation page shows (a label, a kind, an option) asks again.
        if (confirmedSettings.map { it.jsonObject.toField() } != current.settings) return true
        val confirmedAudio = state["confirmedNeedsAudio"]?.jsonPrimitive?.contentOrNull
        return confirmedAudio != null && confirmedAudio != current.needsAudio.toString()
    }

    fun entry(
        id: String,
        hostCapabilities: Set<ScriptHostCapability> = ScriptHostCapability.DEFAULT,
    ): ScriptEntry? = readEntry(id, hostCapabilities = hostCapabilities)

    /** Replaces the source and its parsed header; a changed declaration set reopens confirmation. */
    fun replaceSource(
        id: String,
        source: ByteArray,
        header: ScriptHeader.Parsed,
        origin: ScriptSourceOrigin? = null,
    ) {
        val dir = dirOf(id)
        writeAtomically(File(dir, "source.js"), source)
        val state = readState(dir).toMutableMap()
        origin?.let { state["origin"] = encodeOrigin(it) }
        state["name"] = JsonPrimitive(header.name)
        state["description"] = JsonPrimitive(header.description ?: "")
        state["apiVersion"] = JsonPrimitive(header.apiVersion)
        state["connects"] = buildJsonArray { header.connects.forEach { add(JsonPrimitive(it)) } }
        state["needsAudio"] = JsonPrimitive(header.needsAudio)
        header.timeoutSeconds?.let { state["timeoutSeconds"] = JsonPrimitive(it) } ?: state.remove("timeoutSeconds")
        state["declaredSettings"] = header.settings.toJsonArray()
        state["declarationsChanged"] = JsonPrimitive(declarationsChanged(id, header))
        writeState(dir, JsonObject(state))
    }

    fun originOf(id: String): ScriptSourceOrigin = decodeOrigin(readState(dirOf(id))["origin"]?.jsonObject)

    // --- internals ---

    private fun newState(
        header: ScriptHeader.Parsed,
        origin: ScriptSourceOrigin,
    ): JsonObject =
        buildJsonObject {
            put("version", STATE_VERSION)
            put("enabled", false)
            put("name", header.name)
            put("description", header.description ?: "")
            put("apiVersion", header.apiVersion)
            put("connects", JsonArray(header.connects.map { JsonPrimitive(it) }))
            put("needsAudio", header.needsAudio)
            header.timeoutSeconds?.let { put("timeoutSeconds", it) }
            put("declaredSettings", header.settings.toJsonArray())
            put("confirmedApi", 0)
            put("confirmedConnects", JsonArray(emptyList()))
            put("declarationsChanged", false)
            put("origin", encodeOrigin(origin))
            put("settings", JsonObject(emptyMap()))
        }

    private fun dirOf(id: String): File =
        File(root, "scripts/" + id).also { check(it.isDirectory) { "no script " + id } }

    private fun readCatalog(): List<String> {
        val file = File(root, "catalog.json")
        if (!file.isFile) return emptyList()
        return json.parseToJsonElement(file.readText()).jsonArray.map { it.jsonPrimitive.content }
    }

    private fun writeCatalog(ids: List<String>) {
        root.mkdirs()
        writeAtomically(File(root, "catalog.json"), JsonArray(ids.map { JsonPrimitive(it) }).toString().toByteArray())
    }

    /** Runs read the store on their own thread while settings write it, so a reader must never
     *  see half a file: each write lands under a temporary name and replaces the file at once. */
    private fun writeAtomically(
        file: File,
        bytes: ByteArray,
    ) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeBytes(bytes)
        java.nio.file.Files.move(
            temporary.toPath(),
            file.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE,
        )
    }

    private fun readState(dir: File): JsonObject {
        val state = json.parseToJsonElement(File(dir, "state.json").readText()).jsonObject
        val version = (state["version"]?.jsonPrimitive?.contentOrNull ?: "0").toInt()
        check(version <= STATE_VERSION) { "state version " + version + " is newer than supported " + STATE_VERSION }
        return state
    }

    private fun writeState(
        dir: File,
        state: JsonObject,
    ) {
        writeAtomically(File(dir, "state.json"), state.toString().toByteArray())
    }

    private fun writeSettings(
        dir: File,
        settings: Map<String, String>,
        fields: List<ScriptSettingField>,
        clearSecrets: Set<String> = emptySet(),
    ) {
        val state = readState(dir).toMutableMap()
        val storedPlain = state["settings"]?.jsonObject ?: JsonObject(emptyMap())
        val plain =
            buildJsonObject {
                fields.filterNot { it.secret }.forEach { field ->
                    // A key the form sends updates the value; an empty string clears it. A key the
                    // form omits keeps what is stored, so an older form cannot wipe fields it does
                    // not know about.
                    val value = settings[field.key]
                    if (value != null) {
                        put(field.key, value)
                    } else {
                        storedPlain[field.key]?.let { put(field.key, it) }
                    }
                }
            }
        val secrets = readSecrets(dir).toMutableMap()
        fields.filter { it.secret }.forEach { field ->
            when {
                // An explicit clear wins over a value still typed in the form: the user asked for
                // the stored secret to go away.
                field.key in clearSecrets -> secrets.remove(field.key)
                else -> {
                    // A blank secret field means "keep the stored secret": the form never reads
                    // secrets back, so blank cannot be told apart from "not edited".
                    val value = settings[field.key]
                    if (!value.isNullOrEmpty()) secrets[field.key] = value
                }
            }
        }
        state["settings"] = plain
        writeState(dir, JsonObject(state))
        writeSecrets(dir, JsonObject(secrets.mapValues { JsonPrimitive(it.value) }))
    }

    private fun writeSecrets(
        dir: File,
        secrets: JsonObject,
    ) {
        val file = File(dir, "secret.bin")
        if (secrets.isEmpty()) {
            if (file.isFile) file.delete()
            return
        }
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, dataKey)
        val encrypted = cipher.doFinal(secrets.toString().toByteArray(Charsets.UTF_8))
        val blob =
            buildJsonObject {
                put("header", BLOB_HEADER)
                put("iv", Base64.getEncoder().encodeToString(cipher.iv))
                put("data", Base64.getEncoder().encodeToString(encrypted))
            }
        writeAtomically(file, blob.toString().toByteArray())
    }

    private fun readSecrets(dir: File): Map<String, String> {
        val file = File(dir, "secret.bin")
        if (!file.isFile) return emptyMap()
        val blob = json.parseToJsonElement(file.readText()).jsonObject
        check(blob["header"]?.jsonPrimitive?.contentOrNull == BLOB_HEADER) { "unknown secret blob" }
        val iv = Base64.getDecoder().decode(blob["iv"]!!.jsonPrimitive.content)
        val data = Base64.getDecoder().decode(blob["data"]!!.jsonPrimitive.content)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, dataKey, GCMParameterSpec(128, iv))
        val decrypted = JsonObject(json.parseToJsonElement(String(cipher.doFinal(data), Charsets.UTF_8)).jsonObject)
        return decrypted.keys.associateWith { decrypted[it]!!.jsonPrimitive.content }
    }

    /** Availability is computed, not stored: a different host can offer a different set, and the
     *  import is never rejected for a missing one. */
    private fun readEntry(
        id: String,
        order: Int = -1,
        hostCapabilities: Set<ScriptHostCapability> = ScriptHostCapability.DEFAULT,
    ): ScriptEntry? {
        val dir = File(root, "scripts/" + id)
        if (!dir.isDirectory) return null
        val state = readState(dir)
        // What the stored source declares now: the confirmation page asks for these, and a script
        // that was never confirmed has no confirmed list yet. Older state files only have the
        // confirmed list.
        val fields =
            (state["declaredSettings"]?.jsonArray ?: state["confirmedSettings"]?.jsonArray ?: JsonArray(emptyList()))
                .map { node -> node.jsonObject.toField() }
        val index = if (order >= 0) order else readCatalog().indexOf(id)
        val needsAudio = state["needsAudio"]?.jsonPrimitive?.contentOrNull == "true"
        val confirmedApi = (state["confirmedApi"]?.jsonPrimitive?.contentOrNull ?: "0").toInt()
        val declarationsChanged = state["declarationsChanged"]?.jsonPrimitive?.contentOrNull == "true"
        return ScriptEntry(
            id = id,
            name = state["name"]?.jsonPrimitive?.contentOrNull ?: id,
            description = state["description"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotEmpty() },
            enabled = state["enabled"]?.jsonPrimitive?.contentOrNull == "true",
            order = index,
            apiVersion = (state["apiVersion"]?.jsonPrimitive?.contentOrNull ?: "1").toInt(),
            connects = (state["connects"]?.jsonArray ?: JsonArray(emptyList())).map { it.jsonPrimitive.content },
            needsAudio = needsAudio,
            availableOnThisHost = hostCapabilities.containsAll(ScriptHostCapability.required(needsAudio)),
            confirmationPending = confirmedApi == 0 || declarationsChanged,
            settings = fields,
            sourceOrigin = decodeOrigin(state["origin"]?.jsonObject),
            timeoutSeconds = state["timeoutSeconds"]?.jsonPrimitive?.contentOrNull?.toInt(),
        )
    }

    private fun JsonObject.toField(): ScriptSettingField = ScriptSettingField.fromJson(this)

    private fun List<ScriptSettingField>.toJsonArray(): JsonArray = JsonArray(map { it.toJson() })

    private fun encodeOrigin(origin: ScriptSourceOrigin): JsonObject =
        when (origin) {
            is ScriptSourceOrigin.Pasted -> buildJsonObject { put("kind", "pasted") }
            is ScriptSourceOrigin.File ->
                buildJsonObject {
                    put("kind", "file")
                    put("name", origin.displayName)
                }
            is ScriptSourceOrigin.Link ->
                buildJsonObject {
                    put("kind", "link")
                    put("url", origin.url)
                    put("update", origin.updateUrl)
                }
            ScriptSourceOrigin.Edited -> buildJsonObject { put("kind", "edited") }
        }

    private fun decodeOrigin(node: JsonObject?): ScriptSourceOrigin =
        when (node?.get("kind")?.jsonPrimitive?.contentOrNull) {
            "file" -> ScriptSourceOrigin.File(node["name"]!!.jsonPrimitive.content)
            "link" ->
                ScriptSourceOrigin.Link(
                    node["url"]!!.jsonPrimitive.content,
                    node["update"]!!.jsonPrimitive.content,
                )
            "edited" -> ScriptSourceOrigin.Edited
            else -> ScriptSourceOrigin.Pasted
        }
}
