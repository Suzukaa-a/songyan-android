package app.songyan.ime.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject

/**
 * Persistence for the layout catalog.
 *
 * Every read and write is synchronous by design: callers on Android must run these off the main
 * thread (the IME and settings host does so). A missing file is the untouched default catalog;
 * nothing is written until the user edits.
 */
class LayoutStore(
    val directoryPath: String,
) {
    val catalogPath: String get() = joinPath(directoryPath, CATALOG_FILE_NAME)

    /** Loads the saved catalog, or the builtin default when no file exists yet. */
    @Throws(LayoutException::class)
    fun load(): LayoutRegistry {
        val text =
            LayoutFileIoImpl.readTextOrNull(catalogPath, LayoutResourceBudget.CATALOG_BYTES)
                ?: return LayoutRegistry.create()
        val registry = LayoutRegistryCodec.decode(text)
        registry.validate()
        return registry
    }

    /** Validates, then writes the whole catalog atomically. A rejected registry writes nothing. */
    @Throws(LayoutException::class)
    fun save(registry: LayoutRegistry) {
        registry.validate()
        val text = LayoutRegistryCodec.encode(registry)
        if (text.encodeToByteArray().size > LayoutResourceBudget.CATALOG_BYTES) {
            throw LayoutException.Invalid(LayoutInvalidCode.RESOURCE_BUDGET_EXCEEDED, "catalogBytes")
        }
        LayoutFileIoImpl.writeAtomically(catalogPath, text)
    }

    /** Caller owns any document access. A failed import never changes the catalog. */
    @Throws(LayoutException::class)
    fun importLayout(
        sourcePath: String,
        replaceExisting: Boolean = false,
    ): LayoutRegistry {
        val current = load()
        val text =
            LayoutFileIoImpl.readTextOrNull(sourcePath, LayoutResourceBudget.DEFINITION_BYTES)
                ?: throw LayoutException.Invalid(LayoutInvalidCode.MALFORMED_JSON, "unreadable source")
        val updated = current.install(text, replaceExisting = replaceExisting)
        save(updated)
        return updated
    }

    private fun joinPath(
        directory: String,
        name: String,
    ): String = if (directory.endsWith("/")) directory + name else directory + "/" + name

    companion object {
        const val CATALOG_FILE_NAME: String = "layouts.json"
    }
}

/**
 * Wire form of [LayoutRegistry], the iOS catalog: layouts, the page library (without unchanged
 * code-owned presets), the page order and the keyboard pairings. The iOS decoder needs every field,
 * so all are always written.
 */
internal object LayoutRegistryCodec {
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.Serializable
    private data class Catalog(
        val layouts: List<LayoutDefinition>,
        /** Catalogs from before the enabled list was dropped carry it; it is read and ignored. */
        val enabledIDs: List<String>? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
        val pages: List<KeyboardPageDefinition>? = null,
        /** Saves omit unchanged presets; the order list restores their original library positions. */
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
        val pageOrder: List<String>? = null,
        @kotlinx.serialization.EncodeDefault(kotlinx.serialization.EncodeDefault.Mode.ALWAYS)
        val compositions: Map<String, KeyboardComposition>? = null,
    )

    @Throws(LayoutException::class)
    fun decode(text: String): LayoutRegistry {
        val catalog =
            try {
                LayoutJson.format.decodeFromString(Catalog.serializer(), text)
            } catch (error: IllegalArgumentException) {
                throw LayoutException.Invalid(
                    LayoutInvalidCode.MALFORMED_JSON,
                    error.message ?: "malformed catalog",
                )
            }
        // A catalog saved before a built-in page existed still gets it: the default pairings
        // reference pages by id, so a missing preset would otherwise fail every pairing save.
        val (stored, compositions) =
            KeyboardPagePresets.resolvingRetired(catalog.pages ?: emptyList(), catalog.compositions ?: emptyMap())
        val merged = KeyboardPagePresets.mergingInto(stored)
        // Structural decode only: a repeated page id must stay visible so validation reports it
        // instead of a map silently swallowing one copy.
        return LayoutRegistry.fromWire(
            catalog.layouts.map(RetiredBuiltinLayouts::currentIfRetired),
            restorePageOrder(merged, catalog.pageOrder),
            compositions,
        )
    }

    fun encode(registry: LayoutRegistry): String {
        val userPages = registry.pages.filterNot { KeyboardPagePresets.isUnmodifiedPreset(it) }
        return LayoutJson.format.encodeToString(
            Catalog.serializer(),
            Catalog(
                layouts = registry.layouts,
                pages = userPages,
                pageOrder = registry.pages.map { it.id },
                compositions = registry.compositions,
            ),
        )
    }

    /**
     * Restores the saved library order. A repeated id is corrupt external input: every entry is kept
     * so validation rejects it exactly as before instead of a map silently swallowing one copy.
     */
    private fun restorePageOrder(
        pages: List<KeyboardPageDefinition>,
        pageOrder: List<String>?,
    ): List<KeyboardPageDefinition> {
        if (pageOrder == null) return pages
        if (pages.map { it.id }.toSet().size != pages.size) return pages
        val byId = pages.associateBy { it.id }
        val ordered = mutableListOf<KeyboardPageDefinition>()
        val seen = mutableSetOf<String>()
        for (id in pageOrder) {
            if (!seen.add(id)) continue
            byId[id]?.let { ordered.add(it) }
        }
        ordered.addAll(pages.filterNot { it.id in seen })
        return ordered
    }
}

/**
 * Earlier versions of the bundled layouts. A saved catalog stores every bundled layout in full and
 * the stored copy wins, so a fix to a bundled layout would never reach a device that saved one. A
 * stored built-in equal to a retired version is the unedited old bundle and gets the current
 * definition; one that equals neither is a user edit and stays. The data is generated by the iOS
 * repository from git history and synced with the layout resources.
 */
internal object RetiredBuiltinLayouts {
    private const val RESOURCE = "layouts/retired/builtin-layouts.json"

    /** Read on first use only: a catalog whose built-ins all match the bundle never gets here. */
    private val versions: Map<String, List<LayoutDefinition>> by lazy {
        val bytes =
            RetiredBuiltinLayouts::class.java.classLoader
                ?.getResourceAsStream(RESOURCE)
                ?.use { it.readBytes() }
        if (bytes == null) emptyMap() else decodeVersions(bytes.decodeToString())
    }

    /** Entries that no longer decode are skipped, and so is a file that is not the expected shape. */
    fun decodeVersions(text: String): Map<String, List<LayoutDefinition>> =
        try {
            Json.parseToJsonElement(text).jsonObject.mapValues { (_, entries) ->
                (entries as? JsonArray).orEmpty().mapNotNull { entry ->
                    try {
                        LayoutJson.format.decodeFromJsonElement(LayoutDefinition.serializer(), entry)
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                }
            }
        } catch (_: IllegalArgumentException) {
            emptyMap()
        }

    /** Compared as decoded values: a stored copy omits fields left at their defaults. */
    fun currentIfRetired(stored: LayoutDefinition): LayoutDefinition {
        val bundled = BuiltinLayouts.all.firstOrNull { it.id == stored.id } ?: return stored
        if (stored == bundled) return stored
        return if (versions[stored.id]?.contains(stored) == true) bundled else stored
    }
}
