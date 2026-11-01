package app.songyan.ime.userconfig

import app.songyan.ime.packages.BoundedYaml
import app.songyan.ime.packages.UserConfigSafety
import app.songyan.ime.storage.AppStorage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

/**
 * Scripts the user's files bring in, and which of them the user has agreed to use. A Lua script
 * runs inside the keyboard and reads what is typed, so a script (a `.lua` file, or a
 * `lua_filter@…` style entry in a configuration file) must not take effect just because a file
 * landed in the folder: applying waits for a yes to each script that is new or changed since
 * the last one. What a source ships itself is not the user's to confirm: an entry or file
 * identical to the source's own at the same path is left out. Reads and writes the disk.
 */
class UserScripts(
    private val storage: AppStorage,
) {
    /** One script: [id] is what the user sees (a file path, or the file and its entry), [hash] what it was. */
    data class Entry(
        val id: String,
        val hash: String,
    ) {
        val key: String get() = "$id@$hash"
    }

    /** The scripts of [configDir]'s files that differ from [sourceDir]'s own, in path order. */
    fun entries(
        configDir: File,
        sourceDir: File?,
    ): List<Entry> {
        val found = ArrayList<Entry>()
        for (file in UserConfigFiles.files(configDir)) {
            val path = UserConfigFiles.relativePath(configDir, file)
            val own = sourceDir?.let { UserOverrides.packageFile(it, path) }
            if (path.endsWith(".lua")) {
                val bytes = file.readBytes()
                if (own == null || !own.readBytes().contentEquals(bytes)) found += Entry(path, sha256(bytes))
            } else if (path.endsWith(".yaml")) {
                val shipped = own?.let { components(it.readBytes()) }.orEmpty().toSet()
                for (component in components(file.readBytes())) {
                    if (component !in shipped) found += Entry("$path: $component", sha256(component.toByteArray()))
                }
            }
        }
        return found
    }

    /** The scripts of [configDir] the user has not agreed to yet. */
    fun unconfirmed(
        configDir: File,
        sourceDir: File?,
    ): List<Entry> {
        val agreed = readConfirmed()
        return entries(configDir, sourceDir).filter { it.key !in agreed }
    }

    /** Whether the user has accepted the risk of running their own scripts (the first time they apply one). */
    fun riskAccepted(): Boolean = readState().second

    /** Records that the user agreed to use [entries], and that they know what a script can do. */
    fun accept(entries: Collection<Entry>) {
        synchronized(LOCK) {
            val (confirmed, _) = readState()
            write((confirmed + entries.map { it.key }).toList().takeLast(MAX_CONFIRMED), true)
        }
    }

    private fun write(
        confirmed: List<String>,
        risk: Boolean,
    ) {
        val file = storage.scriptsConfirmedFile
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(
            buildJsonObject {
                put("confirmed", JsonArray(confirmed.map(::JsonPrimitive)))
                put("riskAccepted", risk)
            }.toString(),
        )
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    private fun readConfirmed(): Set<String> = readState().first

    private fun readState(): Pair<Set<String>, Boolean> {
        val root =
            runCatching { Json.parseToJsonElement(storage.scriptsConfirmedFile.readText()) }.getOrNull() as? JsonObject
        val confirmed =
            (root?.get("confirmed") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.toCollection(LinkedHashSet())
                .orEmpty()
        val risk = (root?.get("riskAccepted") as? JsonPrimitive)?.booleanOrNull == true
        return confirmed to risk
    }

    /** The Lua components a YAML file names; none when it is not readable YAML (applying refuses such a file). */
    private fun components(bytes: ByteArray): List<String> {
        val text = UserConfigFiles.decode(bytes)?.removePrefix("\uFEFF") ?: return emptyList()
        if (UserConfigSafety.tooDeep(text)) return emptyList()
        return try {
            UserConfigSafety.luaComponents(BoundedYaml.load(text))
        } catch (_: Exception) {
            emptyList()
        } catch (_: StackOverflowError) {
            emptyList()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            (it.toInt() and 0xFF).toString(16).padStart(2, '0')
        }

    private companion object {
        val LOCK = Any()
        const val MAX_CONFIRMED = 1000
    }
}
