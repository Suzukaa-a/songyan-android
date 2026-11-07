package app.songyan.ime.tools

import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Drawable
import app.songyan.ime.settings.state.ProcessTextApp
import app.songyan.ime.settings.state.ProcessTextToolSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The other apps this app may hand text to, and the ones the user turned on.
 *
 * The list is the same query the relay launches an app with, so settings never offers an app the
 * keyboard would then fail to open. The enabled activities are one small JSON document beside the
 * tool files; a component that is gone (its app was uninstalled) simply drops out of the list, and
 * the document only ever holds what the user chose. A document this build cannot read is never
 * replaced: a read shows nothing enabled, while a change to the list is refused until the file can
 * be read again, so no enabled component is dropped without the user asking. Every method does its
 * package queries and file IO off the main thread.
 */
class ProcessTextToolApps private constructor(
    /** The tools directory the keyboard and settings already share. */
    private val directory: File,
    /** Null only in the constructor below that takes no context, which never lists the apps. */
    private val appContext: Context?,
) : ProcessTextToolSource {
    constructor(
        context: Context,
        directory: File,
    ) : this(directory, context.applicationContext)

    /**
     * Opens the enabled list on its own, which is file IO and needs no context; listing the apps
     * needs one, so [available] refuses instead of guessing. The keyboard and settings always pass
     * a context.
     */
    internal constructor(directory: File) : this(directory, null)

    private val file: File get() = File(directory, FILE_NAME)

    override suspend fun available(): List<ProcessTextApp> =
        withContext(Dispatchers.IO) { availableNow(withIcons = true) }

    override suspend fun enabled(): Set<String> = withContext(Dispatchers.IO) { readEnabled(recovering = true) }

    override suspend fun setEnabled(
        component: String,
        enabled: Boolean,
    ): Set<String> =
        withContext(Dispatchers.IO) {
            // A document this build cannot read must not be replaced: the components it still names
            // would be turned off without the user asking. Only a missing file is the empty list.
            val current = readEnabled(recovering = false).toMutableSet()
            if (enabled) current += component else current -= component
            writeEnabled(current)
            current
        }

    /**
     * What the keyboard's tools panel lists: the enabled handlers, in label order. The query and the
     * stored set are read together here so the panel only waits for one background read.
     */
    suspend fun enabledApps(): List<ProcessTextApp> =
        withContext(Dispatchers.IO) {
            val enabled = readEnabled(recovering = true)
            if (enabled.isEmpty()) return@withContext emptyList()
            // The keyboard's panel names the apps; only the settings page shows their icons.
            availableNow(withIcons = false).filter { it.component in enabled }
        }

    private fun availableNow(withIcons: Boolean): List<ProcessTextApp> {
        val context = requireNotNull(appContext) { "listing the apps needs a context" }
        val manager = context.packageManager
        return ProcessTextTargets
            .available(context)
            .mapNotNull { info ->
                val activity = info.activityInfo ?: return@mapNotNull null
                val component = ComponentName(activity.packageName, activity.name).flattenToString()
                val label = runCatching { activity.loadLabel(manager).toString() }.getOrNull().orEmpty()
                val icon = if (withIcons) runCatching { activity.loadIcon(manager) as? Drawable }.getOrNull() else null
                ProcessTextApp(component, label.ifEmpty { activity.packageName }, icon)
            }.distinctBy { it.component }
            .sortedBy { it.label.lowercase() }
    }

    /**
     * The stored components.
     *
     * A missing file means nothing is enabled. [recovering] is the read a listing and the panel do:
     * a document this build cannot read means nothing is enabled, so the user still gets a page. A
     * mutation reads with `recovering = false` and is refused instead, because writing the empty
     * list it read would turn off every component the damaged document still names.
     */
    private fun readEnabled(recovering: Boolean): Set<String> {
        val target = file
        if (!target.isFile) return emptySet()
        val root =
            runCatching {
                val text = Files.readAllBytes(target.toPath()).toString(Charsets.UTF_8)
                Json.parseToJsonElement(text) as? JsonObject
            }.getOrNull()
        val components = root?.let { enabledIn(it) }
        if (components == null) {
            if (recovering) return emptySet()
            throw IOException("unreadable " + target.name)
        }
        return components
    }

    /** The components [root] names, or null when it is not the document this build writes. */
    private fun enabledIn(root: JsonObject): Set<String>? {
        if ((root["version"] as? JsonPrimitive)?.intOrNull != FORMAT_VERSION) return null
        val entries = root["enabled"] as? JsonArray ?: return null
        return entries
            .mapNotNull { entry -> (entry as? JsonPrimitive)?.takeIf { it.isString }?.content }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /** Replaces the document atomically, so a reader never sees a half-written list. */
    private fun writeEnabled(components: Set<String>) {
        val target = file
        val parent = target.parentFile ?: throw IOException("no parent for " + target.name)
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("cannot create " + parent.name)
        val document: JsonElement =
            buildJsonObject {
                put("version", FORMAT_VERSION)
                put(
                    "enabled",
                    buildJsonArray {
                        components.sorted().forEach { component -> add(JsonPrimitive(component)) }
                    },
                )
            }
        val bytes = Json.encodeToString(JsonElement.serializer(), document).toByteArray(Charsets.UTF_8)
        val temporary = File.createTempFile(TEMPORARY_PREFIX, TEMPORARY_SUFFIX, parent)
        try {
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            runCatching { Files.deleteIfExists(temporary.toPath()) }
        }
    }

    companion object {
        /** The enabled list inside the tools directory, beside the definitions and their index. */
        const val FILE_NAME: String = "process-text.json"

        /** The only document version this build reads and writes. */
        private const val FORMAT_VERSION: Int = 1

        private const val TEMPORARY_PREFIX: String = ".process-text"

        private const val TEMPORARY_SUFFIX: String = ".tmp"
    }
}
