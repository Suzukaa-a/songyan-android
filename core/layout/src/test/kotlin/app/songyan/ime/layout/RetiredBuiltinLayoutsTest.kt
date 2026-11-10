package app.songyan.ime.layout

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** A stored copy of a bundled layout from an older build must not shadow the current bundle. */
class RetiredBuiltinLayoutsTest {
    /** The catalog wire form of the bundled registry with layout [id] replaced by [layout]. */
    private fun catalog(
        id: String,
        layout: String,
    ): String {
        val root = Json.parseToJsonElement(LayoutRegistryCodec.encode(LayoutRegistry.create())).jsonObject
        val layouts =
            root.getValue("layouts").jsonArray.map {
                if (it.jsonObject.getValue("id") == JsonPrimitive(id)) Json.parseToJsonElement(layout) else it
            }
        return JsonObject(root + ("layouts" to JsonArray(layouts))).toString()
    }

    @Test
    fun storedOldBundledLayoutBecomesTheCurrentOneInPlace() {
        val old = LayoutFixtures.readText("retired-layouts/romaji26-before-long-vowel-move.json")
        assertNotEquals(BuiltinLayouts.romaji26, LayoutJson.decode(old))
        val loaded = LayoutRegistryCodec.decode(catalog("romaji26", old))
        assertEquals(BuiltinLayouts.all, loaded.layouts)
    }

    @Test
    fun userEditOfABundledLayoutIsKept() {
        val edited = LayoutJson.format.encodeToString(LayoutDefinition.serializer(), BuiltinLayouts.qwerty26)
        val relabelled = edited.replaceFirst("\"label\": \"q\"", "\"label\": \"Q!\"")
        assertNotEquals(edited, relabelled)
        val loaded = LayoutRegistryCodec.decode(catalog("qwerty26", relabelled))
        val qwerty = loaded.layouts.first { it.id == "qwerty26" }
        assertEquals("Q!", qwerty.rows[0].keys[0].label)
        assertEquals(BuiltinLayouts.all.drop(1), loaded.layouts.drop(1))
    }

    @Test
    fun catalogOfTheCurrentBundleIsUnchanged() {
        val registry = LayoutRegistry.create()
        assertEquals(registry, LayoutRegistryCodec.decode(LayoutRegistryCodec.encode(registry)))
    }

    @Test
    fun unknownAndGarbledRetiredEntriesAreSkipped() {
        val good = LayoutJson.format.encodeToString(LayoutDefinition.serializer(), BuiltinLayouts.qwerty26)
        val versions =
            RetiredBuiltinLayouts.decodeVersions(
                """{"qwerty26": [$good, {"id": 5}, "text", null], "gone": [{"x": 1}]}""",
            )
        assertEquals(listOf(BuiltinLayouts.qwerty26), versions["qwerty26"])
        assertEquals(emptyList<LayoutDefinition>(), versions["gone"])
        assertEquals(emptyMap<String, List<LayoutDefinition>>(), RetiredBuiltinLayouts.decodeVersions("[1]"))
        assertEquals(emptyMap<String, List<LayoutDefinition>>(), RetiredBuiltinLayouts.decodeVersions("nope"))
    }

    @Test
    fun everyRetiredVersionInTheShippedFileDecodes() {
        val text = LayoutFixtures.readText("layouts/retired/builtin-layouts.json")
        val raw = Json.parseToJsonElement(text).jsonObject
        val decoded = RetiredBuiltinLayouts.decodeVersions(text)
        for ((id, entries) in raw) assertEquals(id, entries.jsonArray.size, decoded[id]?.size)
    }
}
