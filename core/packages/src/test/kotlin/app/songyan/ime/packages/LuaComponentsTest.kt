package app.songyan.ime.packages

import app.songyan.ime.keyboard.layout.CompactFold
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Ported from the iOS `LuaComponentsTests`. */
class LuaComponentsTest {
    private fun compiled(
        id: String = "s",
        processors: List<String>,
        translators: List<String> = listOf("script_translator"),
        filters: List<String> = emptyList(),
        extra: String = "",
    ): ByteArray {
        val lines = mutableListOf("schema:", "  schema_id: $id", "engine:", "  processors:")
        lines += processors.map { "    - \"$it\"" }
        lines.add("  translators:")
        lines += translators.map { "    - \"$it\"" }
        if (filters.isNotEmpty()) {
            lines.add("  filters:")
            lines += filters.map { "    - \"$it\"" }
        }
        if (extra.isNotEmpty()) lines.add(extra)
        return (lines.joinToString("\n") + "\n").toByteArray()
    }

    private val processors = listOf("lua_processor@*select_char", "speller", "selector")
    private val filters = listOf("lua_filter@*charset*F", "uniquifier", "lua_filter@*emoji")

    @Test
    fun listsLuaComponentsInOrderAllEnabledByDefault() {
        val state = requireNotNull(LuaComponents.state(compiled(processors = processors, filters = filters), "s"))
        assertEquals(
            listOf(
                "lua_processor@*select_char",
                "lua_filter@*charset*F",
                "lua_filter@*emoji",
            ),
            state.components.map {
                it.prescription
            },
        )
        assertTrue(state.components.all { it.enabled })
        assertEquals(listOf(true, false, false), state.components.map { it.mayAffectBasicInput })
        assertEquals("charset*F", state.components[1].displayName)
        assertEquals("filters/lua_filter@*charset*F", state.components[1].id)
        assertEquals("s", state.name)
        assertNull(LuaComponents.state(compiled(processors = processors), "other"))
    }

    @Test
    fun disablingAssignsOnlyAffectedListsFromBaseline() {
        val state = requireNotNull(LuaComponents.state(compiled(processors = processors, filters = filters), "s"))
        val recipe = LuaComponents.make("s", state.baseline, setOf("lua_filter@*emoji"))
        val text = String(recipe.source)
        assertTrue(text.contains("engine/filters: [\"lua_filter@*charset*F\", \"uniquifier\"]"))
        assertFalse("a list that loses nothing is never pinned", text.contains("engine/processors:"))
        assertTrue(text.contains("songyan_lua_managed: true"))
        try {
            LuaComponents.make("s", state.baseline, setOf("uniquifier"))
            fail("a non-Lua component must be refused")
        } catch (_: RecipeInstallError.InvalidResource) {
        }

        // Round trip through the managed-block writer and the compiled read-back.
        val applied = applyRecipe(recipe, emptyMap())
        val custom = String(requireNotNull(applied.files["s.custom.yaml"]))
        assertTrue(custom.contains("# Rx: user/pkg:lua_components: {"))
        assertFalse(LuaComponents.userOwnsSetting(applied.files["s.custom.yaml"]))

        val deployed =
            compiled(
                processors = processors,
                filters = listOf("lua_filter@*charset*F", "uniquifier"),
                extra =
                    "songyan_lua_managed: true\nsongyan_lua_baseline:\n" +
                        "  processors: [\"lua_processor@*select_char\", speller, selector]\n" +
                        "  translators: [script_translator]\n" +
                        "  filters: [\"lua_filter@*charset*F\", uniquifier, \"lua_filter@*emoji\"]",
            )
        val read = requireNotNull(LuaComponents.state(deployed, "s"))
        assertEquals(setOf("lua_filter@*emoji"), read.disabled)
        assertEquals(state.baseline, read.baseline)
        assertTrue(LuaComponents.matches(deployed, "s", read.baseline, setOf("lua_filter@*emoji")))
        assertFalse(LuaComponents.matches(deployed, "s", read.baseline, emptySet()))

        // Restoring everything pins nothing.
        val restore = String(LuaComponents.make("s", read.baseline, emptySet()).source)
        assertTrue(restore.contains("songyan_lua_managed: false"))
        assertFalse(restore.contains("engine/"))
    }

    @Test
    fun managedPredictionComponentsStayOutOfTheBaseline() {
        val data =
            compiled(
                processors = listOf("predictor") + processors,
                translators = listOf("predict_translator", "script_translator"),
                filters = filters,
                extra = "songyan_prediction: true",
            )
        val state = requireNotNull(LuaComponents.state(data, "s"))
        assertEquals(processors, state.baseline["processors"])
        assertEquals(listOf("script_translator"), state.baseline["translators"])
        assertTrue(LuaComponents.matches(data, "s", state.baseline, emptySet()))
        // A scheme's own predictor is part of its baseline.
        val own = requireNotNull(LuaComponents.state(compiled(processors = listOf("predictor") + processors), "s"))
        assertEquals("predictor", own.baseline["processors"]?.first())
    }

    @Test
    fun userEngineEditsAreUserManagedAndCompanionsRejected() {
        assertTrue(LuaComponents.userOwnsSetting("patch:\n  engine/filters/+:\n    - lua_filter@x\n".toByteArray()))
        assertTrue(LuaComponents.userOwnsSetting("patch:\n  engine:\n    filters: []\n".toByteArray()))
        assertFalse(LuaComponents.userOwnsSetting("patch:\n  menu/page_size: 7\n".toByteArray()))
        val companion = CompactSchemaAdapter.identifier("s", CompactFold.PHONE9)
        try {
            LuaComponents.make(companion, emptyMap(), emptySet())
            fail("a generated companion is never patched")
        } catch (_: RecipeInstallError.InvalidResource) {
        }
        assertEquals("\"a\\\"b\\\\c\"", LuaComponents.quoted("a\"b\\c"))
    }

    @Test
    fun nonStringSchemaNameIsDescribedLikeSwift() {
        val numeric = "schema:\n  schema_id: s\n  name: 1.5\nengine:\n  processors: [speller]\n".toByteArray()
        assertEquals("1.5", LuaComponents.state(numeric, "s")?.name)
        val absent = "schema:\n  schema_id: s\nengine:\n  processors: [speller]\n".toByteArray()
        assertEquals("s", LuaComponents.state(absent, "s")?.name)
        // A list that is not all strings makes the whole read fail closed.
        val mixed = "schema:\n  schema_id: s\nengine:\n  processors: [speller, 1]\n".toByteArray()
        assertNull(LuaComponents.state(mixed, "s"))
    }
}
