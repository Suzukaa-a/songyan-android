package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyFlickTest {
    private val first = LayoutOption("，", LayoutAction.literal("，"))
    private val second = LayoutOption(",", LayoutAction.literal(","))

    private fun snapshot(
        options: List<LayoutOption>,
        hint: String? = options.firstOrNull()?.label,
        kind: LayoutActionKind = LayoutActionKind.CODE,
        swipeCodes: List<String>? = null,
    ) = KeyPressSnapshot(
        identity = "k",
        action = LayoutAction(kind, "a"),
        railLabel = null,
        longPress = options,
        swipeCodes = swipeCodes,
        hint = hint,
    )

    private fun target(
        snapshot: KeyPressSnapshot,
        direction: FlickDirection = FlickDirection.UP,
        digit: String? = null,
    ) = KeyFlick.target(snapshot, direction, digit, (snapshot.swipeCodes?.size ?: 0) > 1)

    @Test
    fun `the flick types the hint drawn on the key, either way it is set`() {
        val key = snapshot(listOf(first, second))
        assertEquals(first, target(key, FlickDirection.UP))
        assertEquals(first, target(key, FlickDirection.DOWN))
    }

    @Test
    fun `a hint that is not the first option still picks its own option`() {
        assertEquals(second, target(snapshot(listOf(first, second), hint = ",")))
    }

    @Test
    fun `a hint without a matching option types nothing`() {
        assertNull(target(snapshot(listOf(first, second), hint = "iang")))
        assertNull(target(snapshot(listOf(first, second), hint = null)))
    }

    @Test
    fun `a key without options has no flick`() {
        assertNull(target(snapshot(emptyList())))
    }

    @Test
    fun `a nine-key digit key types its digit`() {
        val digit = target(snapshot(emptyList(), hint = "5"), digit = "5")
        assertEquals(LayoutOption("5", LayoutAction.literal("5")), digit)
        assertNull(target(snapshot(emptyList(), hint = "5"), FlickDirection.OFF, digit = "5"))
    }

    @Test
    fun `a merged key keeps its sideways swipes`() {
        assertNull(target(snapshot(listOf(first, second), swipeCodes = listOf("q", "w"))))
    }

    @Test
    fun `the setting off removes the flick`() {
        val key = snapshot(listOf(first, second))
        assertNull(target(key, FlickDirection.OFF))
        assertFalse(KeyFlick.eligible(key, FlickDirection.OFF, false))
        assertTrue(KeyFlick.eligible(key, FlickDirection.DOWN, false))
    }

    @Test
    fun `only keys that type text flick`() {
        assertNull(target(snapshot(listOf(first), kind = LayoutActionKind.SHIFT)))
        assertEquals(first, target(snapshot(listOf(first), kind = LayoutActionKind.LITERAL)))
    }

    @Test
    fun `stored directions resolve, and anything unknown flicks up`() {
        assertEquals(FlickDirection.OFF, FlickDirection.resolve("off"))
        assertEquals(FlickDirection.DOWN, FlickDirection.resolve("down"))
        assertEquals(FlickDirection.UP, FlickDirection.resolve(null))
        assertEquals(FlickDirection.UP, FlickDirection.resolve("sideways"))
    }

    @Test
    fun `progress starts past the slop and is whole at the threshold`() {
        assertEquals(0f, KeyFlick.progress(-20f, 8f, 24f))
        assertEquals(0f, KeyFlick.progress(8f, 8f, 24f))
        assertEquals(0.5f, KeyFlick.progress(16f, 8f, 24f))
        assertEquals(1f, KeyFlick.progress(40f, 8f, 24f))
    }

    @Test
    fun `the morph glides to a new target and lands on it`() {
        val morph = FlickMorph()
        morph.set(key = 3, up = true, target = 1f, now = 0L, motionScale = 1f)
        val halfway = morph.value(45L, 1f)
        assertTrue(halfway > 0f && halfway < 1f)
        assertTrue(morph.animating(45L, 1f))
        assertEquals(1f, morph.value(90L, 1f))
        assertFalse(morph.animating(90L, 1f))
        // Pulled back: it glides down from where it was, not from zero.
        morph.set(key = 3, up = true, target = 0.2f, now = 100L, motionScale = 1f)
        assertEquals(1f, morph.value(100L, 1f))
        assertEquals(0.2f, morph.value(190L, 1f), 1e-6f)
    }

    @Test
    fun `without system animations the morph jumps to its target`() {
        val morph = FlickMorph()
        morph.set(key = 0, up = false, target = 1f, now = 0L, motionScale = 0f)
        assertEquals(1f, morph.value(0L, 0f))
        assertFalse(morph.animating(0L, 0f))
        assertFalse(morph.up)
    }

    @Test
    fun `another key starts its morph from rest, and clearing ends it`() {
        val morph = FlickMorph()
        morph.set(key = 1, up = true, target = 1f, now = 0L, motionScale = 0f)
        morph.set(key = 2, up = true, target = 0.5f, now = 10L, motionScale = 1f)
        assertEquals(0f, morph.value(10L, 1f))
        morph.clear()
        assertEquals(-1, morph.key)
        assertFalse(morph.animating(20L, 1f))
    }
}
