package app.songyan.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackspaceTraceTest {
    @Test
    fun keepsTheLatestEventsOldestFirst() {
        BackspaceTrace.clear()
        BackspaceTrace.editorPackage = "com.example.notes"
        repeat(BackspaceTrace.CAPACITY + 3) { i ->
            val event = if (i % 2 == 0) BackspaceTrace.Event.TOUCH_DOWN_DELETE else BackspaceTrace.Event.EDITOR_DELETE
            BackspaceTrace.record(event, composing = i == BackspaceTrace.CAPACITY + 2)
        }
        val events = BackspaceTrace.snapshot()
        assertEquals(BackspaceTrace.CAPACITY, events.size)
        // The three oldest fell out: the first kept is event #3 (odd), the last is the newest.
        assertEquals(BackspaceTrace.Event.EDITOR_DELETE, events.first().event)
        assertTrue(events.last().composing)
        assertTrue(events.all { it.editorPackage == "com.example.notes" })
        assertTrue(events.zipWithNext().all { (a, b) -> a.timeEpochMs <= b.timeEpochMs })
    }

    @Test
    fun anEntryHasNoRoomForText() {
        // The privacy rule is structural: an entry carries codes, flags, a time and a package only.
        val fields =
            BackspaceTrace.Entry::class.java.declaredFields
                .map { it.name }
                .filterNot { it.startsWith("$") }
                .toSet()
        assertEquals(setOf("timeEpochMs", "event", "composing", "cachedCaret", "editorPackage"), fields)
    }
}
