package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyFlickTest {
    private val first = LayoutOption("，", LayoutAction.literal("，"))
    private val second = LayoutOption(",", LayoutAction.literal(","))

    private fun snapshot(
        options: List<LayoutOption>,
        kind: LayoutActionKind = LayoutActionKind.CODE,
        swipeCodes: List<String>? = null,
    ) = KeyPressSnapshot(
        identity = "k",
        action = LayoutAction(kind, "a"),
        railLabel = null,
        longPress = options,
        swipeCodes = swipeCodes,
    )

    private fun option(
        snapshot: KeyPressSnapshot,
        up: Boolean,
        enabled: Boolean = true,
        digitFlick: Boolean = false,
    ) = KeyFlick.option(snapshot, up, enabled, digitFlick, (snapshot.swipeCodes?.size ?: 0) > 1)

    @Test
    fun `up types the first option and down the second`() {
        val key = snapshot(listOf(first, second))
        assertEquals(first, option(key, up = true))
        assertEquals(second, option(key, up = false))
    }

    @Test
    fun `a single option answers only an upward flick`() {
        val key = snapshot(listOf(first))
        assertEquals(first, option(key, up = true))
        assertNull(option(key, up = false))
    }

    @Test
    fun `a key without options has no flick`() {
        val key = snapshot(emptyList())
        assertNull(option(key, up = true))
        assertNull(option(key, up = false))
    }

    @Test
    fun `a nine-key digit key keeps its own flick`() {
        assertNull(option(snapshot(listOf(first, second)), up = true, digitFlick = true))
    }

    @Test
    fun `a merged key keeps its sideways swipes`() {
        val key = snapshot(listOf(first, second), swipeCodes = listOf("q", "w"))
        assertNull(option(key, up = true))
        assertNull(option(key, up = false))
    }

    @Test
    fun `the preference off removes the flick`() {
        val key = snapshot(listOf(first, second))
        assertNull(option(key, up = true, enabled = false))
        assertNull(option(key, up = false, enabled = false))
    }

    @Test
    fun `only keys that type text flick`() {
        assertNull(option(snapshot(listOf(first), kind = LayoutActionKind.SHIFT), up = true))
        assertEquals(first, option(snapshot(listOf(first), kind = LayoutActionKind.LITERAL), up = true))
    }
}
