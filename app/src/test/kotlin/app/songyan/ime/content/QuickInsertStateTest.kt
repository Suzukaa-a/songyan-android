package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class QuickInsertStateTest {
    private val instant = Instant.parse("2026-09-09T06:30:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    private fun core() = QuickInsertCore(now = { instant }, zone = zone)

    private fun core(preferences: QuickInsertFormatPreferences) = QuickInsertCore(preferences, { instant }, zone)

    @Test
    fun everyDateFormatIsRowWithItsLivePreview() {
        val state = quickInsertState(core())
        assertEquals(QuickInsertDateFormat.entries.size, state.dateRows.size)
        val byFormat = state.dateRows.associateBy { it.format }
        assertEquals("2026-09-09", byFormat.getValue(QuickInsertDateFormat.DATE).preview)
        assertEquals("2026年9月9日", byFormat.getValue(QuickInsertDateFormat.DATE_CHINESE).preview)
        assertEquals("14:30", byFormat.getValue(QuickInsertDateFormat.TIME).preview)
        assertEquals("2026-09-09 14:30", byFormat.getValue(QuickInsertDateFormat.DATE_TIME).preview)
        assertEquals("2026年9月9日 14:30", byFormat.getValue(QuickInsertDateFormat.DATE_TIME_CHINESE).preview)
    }

    @Test
    fun theRememberedFormatIsTheSelectedRow() {
        val state =
            quickInsertState(
                core(InMemoryQuickInsertFormatPreferences(QuickInsertDateFormat.DATE_CHINESE)),
            )
        assertEquals(1, state.dateRows.count { it.selected })
        assertTrue(state.dateRows.first { it.selected }.format == QuickInsertDateFormat.DATE_CHINESE)
    }

    @Test
    fun repeatIsUnavailableUntilSomethingWasCommitted() {
        val core = core()
        assertNull(quickInsertState(core).repeatPreview)
        core.recordCommitted("辛苦了，早点休息。")
        assertEquals("辛苦了，早点休息。", quickInsertState(core).repeatPreview)
    }

    @Test
    fun longCommitsArePreviewedOnOneLine() {
        val core = core()
        core.recordCommitted("第一行\n第二行还有很长很长的一段内容要收在一行里")
        val preview = quickInsertState(core).repeatPreview
        assertEquals(core.repeatPreview, preview)
        assertTrue(preview!!.none { it == '\n' })
    }
}
