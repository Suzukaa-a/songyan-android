package app.songyan.ime.content

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.nio.file.Path

/**
 * One symbol entry.
 *
 * [text] is what gets inserted into the host (one glyph or a few combining characters),
 * [name] is the Chinese display name, [aliases] are lowercase pinyin/English search aliases,
 * and [category] references a [SymbolCategory.id].
 */
data class SymbolItem(
    val id: String,
    val text: String,
    val name: String,
    val aliases: List<String>,
    val category: String,
)

/** A catalog category: display name plus the weight that orders it. */
data class SymbolCategory(
    val id: String,
    val name: String,
    val order: Int,
)

/**
 * The symbol catalog: categories plus items, the data behind the keyboard symbol panel.
 *
 * The JSON shape is `{version:1, categories:[{id,name,order}], items:[{id,text,name,aliases,category}]}`
 * and is shared with the iOS app, so an exported catalog imports on both platforms.
 *
 * [decode] is strict: unknown fields, a version other than 1 and any out-of-range value are
 * rejected instead of silently repaired, and the reason is a [ContentException] carrying the
 * offending JSON path. [bundledDefault] reads the resource synced from the iOS repository.
 */
data class SymbolCatalog(
    val version: Int = 1,
    val categories: List<SymbolCategory> = emptyList(),
    val items: List<SymbolItem> = emptyList(),
) {
    /**
     * Category display order: known categories by ascending [SymbolCategory.order] (ties by
     * id), then categories that only items reference, sorted by id. Empty categories are
     * included; the panel decides whether to hide them.
     */
    val orderedCategories: List<SymbolCategory>
        get() {
            val known = categories.sortedWith(compareBy({ it.order }, { it.id }))
            val knownIds = known.mapTo(HashSet()) { it.id }
            val extras =
                items.map { it.category }.filter { it !in knownIds }.distinct().sorted().map {
                    SymbolCategory(id = it, name = it, order = Int.MAX_VALUE)
                }
            return known + extras
        }

    /** Items of [category], in catalog declaration order so rendering stays stable. */
    fun itemsIn(category: String): List<SymbolItem> = items.filter { it.category == category }

    /**
     * Search over the Chinese name, the pinyin/English aliases and the symbol text itself,
     * using this catalog's own query buffer only; the host text and the engine are never
     * involved.
     *
     * Ranking is deterministic: name prefix, alias prefix, name contains, alias contains,
     * text match; ties keep catalog declaration order. An empty (or whitespace-only) query
     * returns nothing, which is how the panel falls back to category browsing.
     */
    fun search(rawQuery: String): List<SymbolItem> {
        val query = SymbolSearch.normalize(rawQuery)
        if (query.isEmpty()) return emptyList()
        val trimmedRaw = rawQuery.trim { it.isWhitespace() }
        val byNamePrefix = ArrayList<SymbolItem>()
        val byAliasPrefix = ArrayList<SymbolItem>()
        val byNameContains = ArrayList<SymbolItem>()
        val byAliasContains = ArrayList<SymbolItem>()
        val byText = ArrayList<SymbolItem>()
        for (item in items) {
            val name = SymbolSearch.normalize(item.name)
            if (name.startsWith(query)) {
                byNamePrefix.add(item)
                continue
            }
            if (item.aliases.any { SymbolSearch.normalize(it).startsWith(query) }) {
                byAliasPrefix.add(item)
                continue
            }
            if (name.contains(query)) {
                byNameContains.add(item)
                continue
            }
            if (item.aliases.any { SymbolSearch.normalize(it).contains(query) }) {
                byAliasContains.add(item)
                continue
            }
            if (SymbolSearch.normalize(item.text) == query || item.text.contains(trimmedRaw)) {
                byText.add(item)
            }
        }
        return byNamePrefix + byAliasPrefix + byNameContains + byAliasContains + byText
    }

    /** Validates every documented bound; throws a path-accurate [ContentException] on the first miss. */
    fun validate() {
        requireField(version == 1, "$.version")
        requireField(items.size <= MAX_ITEMS, "$.items")
        val seenIds = HashSet<String>()
        val seenTexts = HashSet<String>()
        items.forEachIndexed { index, item ->
            val path = "$.items[" + index + "]"
            requireField(isValidIdentifier(item.id), path + ".id")
            requireField(seenIds.add(item.id), path + ".id")
            requireField(
                item.text.isNotEmpty() && item.text.codePointCount(0, item.text.length) <= MAX_TEXT_CODE_POINTS,
                path + ".text",
            )
            requireField(seenTexts.add(item.text), path + ".text")
            requireField(item.name.trim { it.isWhitespace() }.isNotEmpty(), path + ".name")
            requireField(item.aliases.size <= MAX_ALIASES_PER_ITEM, path + ".aliases")
            requireField(item.category.isNotEmpty(), path + ".category")
            item.aliases.forEachIndexed { aliasIndex, alias ->
                requireField(alias.trim { it.isWhitespace() }.isNotEmpty(), path + ".aliases[" + aliasIndex + "]")
            }
        }
        val seenCategoryIds = HashSet<String>()
        categories.forEachIndexed { index, category ->
            val path = "$.categories[" + index + "]"
            requireField(isValidIdentifier(category.id), path + ".id")
            requireField(seenCategoryIds.add(category.id), path + ".id")
            requireField(category.name.trim { it.isWhitespace() }.isNotEmpty(), path + ".name")
        }
    }

    /** The export bytes: validated, pretty-printed JSON, within [MAX_JSON_BYTES]. */
    fun encode(): ByteArray {
        validate()
        val element = toJson()
        val text = ContentJson.pretty.encodeToString(JsonElement.serializer(), element)
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_JSON_BYTES) throw ContentException(ContentErrorCode.OVERSIZE, "$")
        return bytes
    }

    private fun toJson(): JsonObject =
        buildJsonObject {
            put("version", version)
            put(
                "categories",
                buildJsonArray {
                    categories.forEach { category ->
                        add(
                            buildJsonObject {
                                put("id", category.id)
                                put("name", category.name)
                                put("order", category.order)
                            },
                        )
                    }
                },
            )
            put(
                "items",
                buildJsonArray {
                    items.forEach { item ->
                        add(
                            buildJsonObject {
                                put("id", item.id)
                                put("text", item.text)
                                put("name", item.name)
                                put(
                                    "aliases",
                                    buildJsonArray { item.aliases.forEach { alias -> add(JsonPrimitive(alias)) } },
                                )
                                put("category", item.category)
                            },
                        )
                    }
                },
            )
        }

    companion object {
        /** Byte budget of the catalog document (256 KB). */
        const val MAX_JSON_BYTES: Int = 256 * 1024

        /** Entry budget of the catalog. */
        const val MAX_ITEMS: Int = 2_000

        /** Alias budget of one entry. */
        const val MAX_ALIASES_PER_ITEM: Int = 16

        /** Identifier budgets: at most 64 ASCII bytes of letters, digits, `-` and `_`. */
        const val MAX_IDENTIFIER_BYTES: Int = 64

        /** Cross-platform clamp of one symbol's text. */
        const val MAX_TEXT_CODE_POINTS: Int = 32

        /** Classpath resource of the bundled default, synced from the iOS repository. */
        const val RESOURCE_NAME: String = "default-symbols.json"

        /** Decodes and validates a catalog document; the reason is a path-accurate exception. */
        fun decode(bytes: ByteArray): SymbolCatalog {
            if (bytes.size > MAX_JSON_BYTES) throw ContentException(ContentErrorCode.OVERSIZE, "$")
            val element = ContentJson.parse(ContentJson.decodeText(bytes))
            val source = ContentJson.parseObject(element)
            ContentJson.rejectUnknownFields(source, "$", TOP_LEVEL_FIELDS)
            val version = ContentJson.requireInt(source, "version", "$")
            if (version != 1) throw ContentException(ContentErrorCode.UNSUPPORTED_VERSION, "$.version")
            val categoriesElement = source["categories"]
            val categories =
                if (categoriesElement == null || categoriesElement is JsonNull) {
                    emptyList()
                } else {
                    decodeCategories(categoriesElement)
                }
            val itemsElement = source["items"] ?: throw ContentException(ContentErrorCode.INVALID_VALUE, "$.items")
            val catalog = SymbolCatalog(version = version, categories = categories, items = decodeItems(itemsElement))
            catalog.validate()
            return catalog
        }

        /** Reads a user file within [MAX_JSON_BYTES] before decoding it. */
        fun decodeFile(path: Path): SymbolCatalog = decode(BoundedFile.read(path, MAX_JSON_BYTES))

        /**
         * The bundled default catalog. A missing resource is a packaging error, so it is
         * thrown rather than papered over with made-up items.
         */
        fun bundledDefault(): SymbolCatalog {
            val stream =
                SymbolCatalog::class.java.getResourceAsStream("/" + RESOURCE_NAME)
                    ?: throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, RESOURCE_NAME)
            val bytes =
                try {
                    stream.use { it.readNBytes(MAX_JSON_BYTES + 1) }
                } catch (error: IOException) {
                    throw ContentException(ContentErrorCode.STORAGE_UNAVAILABLE, RESOURCE_NAME, error)
                }
            return decode(bytes)
        }

        private val TOP_LEVEL_FIELDS: Set<String> = setOf("version", "categories", "items")
        private val CATEGORY_FIELDS: Set<String> = setOf("id", "name", "order")
        private val ITEM_FIELDS: Set<String> = setOf("id", "text", "name", "aliases", "category")

        private fun decodeCategories(element: JsonElement): List<SymbolCategory> {
            val array = element as? JsonArray ?: throw ContentException(ContentErrorCode.INVALID_VALUE, "$.categories")
            return array.mapIndexed { index, entry ->
                val path = "$.categories[" + index + "]"
                val source =
                    ContentJson.parseObject(entry).let {
                        ContentJson.rejectUnknownFields(it, path, CATEGORY_FIELDS)
                        it
                    }
                SymbolCategory(
                    id = ContentJson.requireString(source, "id", path),
                    name = ContentJson.requireString(source, "name", path),
                    order = ContentJson.requireInt(source, "order", path),
                )
            }
        }

        private fun decodeItems(element: JsonElement): List<SymbolItem> {
            val array = element as? JsonArray ?: throw ContentException(ContentErrorCode.INVALID_VALUE, "$.items")
            return array.mapIndexed { index, entry ->
                val path = "$.items[" + index + "]"
                val source =
                    ContentJson.parseObject(entry).let {
                        ContentJson.rejectUnknownFields(it, path, ITEM_FIELDS)
                        it
                    }
                val aliasesElement =
                    source["aliases"] ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + ".aliases")
                val aliasesArray =
                    aliasesElement as? JsonArray
                        ?: throw ContentException(ContentErrorCode.INVALID_VALUE, path + ".aliases")
                SymbolItem(
                    id = ContentJson.requireString(source, "id", path),
                    text = ContentJson.requireString(source, "text", path),
                    name = ContentJson.requireString(source, "name", path),
                    aliases =
                        aliasesArray.mapIndexed { aliasIndex, aliasElement ->
                            val aliasPath = path + ".aliases[" + aliasIndex + "]"
                            val primitive =
                                aliasElement as? JsonPrimitive
                                    ?: throw ContentException(ContentErrorCode.INVALID_VALUE, aliasPath)
                            if (!primitive.isString) throw ContentException(ContentErrorCode.INVALID_VALUE, aliasPath)
                            primitive.content
                        },
                    category = ContentJson.requireString(source, "category", path),
                )
            }
        }

        private fun isValidIdentifier(value: String): Boolean {
            if (value.isEmpty() || value.toByteArray(Charsets.UTF_8).size > MAX_IDENTIFIER_BYTES) return false
            return value.all { it.code < 128 && (it.isLetterOrDigit() || it == '-' || it == '_') }
        }
    }
}

/**
 * Normalization shared by a query and the values it is matched against: lowercase, fold
 * full-width ASCII to half-width, strip whitespace. Only used for comparison; user data is
 * never rewritten.
 */
object SymbolSearch {
    fun normalize(value: String): String {
        val builder = StringBuilder(value.length)
        for (char in value.lowercase()) {
            val folded =
                when {
                    char.code in 0xFF01..0xFF5E -> (char.code - 0xFF01 + 0x21).toChar()
                    char.code == 0x3000 -> ' '
                    else -> char
                }
            if (!folded.isWhitespace()) builder.append(folded)
        }
        return builder.toString()
    }
}
