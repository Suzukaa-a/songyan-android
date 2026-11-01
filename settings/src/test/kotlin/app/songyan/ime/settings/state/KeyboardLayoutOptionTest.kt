package app.songyan.ime.settings.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyboardLayoutOptionTest {
    @Test
    fun `every built-in layout id resolves to its option`() {
        assertEquals(KeyboardLayoutOption.QWERTY26, KeyboardLayoutOption.fromId("qwerty26"))
        assertEquals(KeyboardLayoutOption.MERGED14, KeyboardLayoutOption.fromId("merged14"))
        assertEquals(KeyboardLayoutOption.MERGED18, KeyboardLayoutOption.fromId("merged18"))
        assertEquals(KeyboardLayoutOption.PHONE9, KeyboardLayoutOption.fromId("phone9"))
    }

    @Test
    fun `unknown or missing ids fall back to the default layout`() {
        assertEquals(KeyboardLayoutOption.QWERTY26, KeyboardLayoutOption.fromId(null))
        assertEquals(KeyboardLayoutOption.QWERTY26, KeyboardLayoutOption.fromId(""))
        assertEquals(KeyboardLayoutOption.QWERTY26, KeyboardLayoutOption.fromId("not-a-layout"))
    }

    @Test
    fun `enabled layouts carry no unavailability note`() {
        assertEquals(null, KeyboardLayoutOption.PHONE9.noteRes(available = true))
    }

    @Test
    fun `every layout has a thumbnail taken from its real rows`() {
        for (option in KeyboardLayoutOption.entries) {
            val rows = option.thumbnailRows
            assertTrue("${option.id} has no rows", rows.isNotEmpty())
            assertTrue(rows.all { row -> row.isNotEmpty() && row.all { it > 0.0 } })
        }
        // The 26-key layout is the three letter rows; the 14-key layout is a coarser grid of them.
        assertEquals(
            10,
            KeyboardLayoutOption.QWERTY26.thumbnailRows
                .first()
                .size,
        )
        assertTrue(
            KeyboardLayoutOption.MERGED14.thumbnailRows
                .first()
                .size <
                KeyboardLayoutOption.QWERTY26.thumbnailRows
                    .first()
                    .size,
        )
    }
}
