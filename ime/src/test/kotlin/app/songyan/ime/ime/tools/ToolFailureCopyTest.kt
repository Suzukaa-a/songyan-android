package app.songyan.ime.ime.tools

import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolFailure
import app.songyan.ime.tools.ToolPhase
import app.songyan.ime.tools.UnavailableReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What the panel may show for a failed run: the failing tool's title and its own words, or nothing
 *  when the failure has its own fixed copy. */
class ToolFailureCopyTest {
    private val tool = ToolDescriptor("script:1", "加书名号", setOf(ToolCapability.TEXT))

    private fun state(
        failure: ToolFailure?,
        tool: ToolDescriptor? = this.tool,
    ) = ToolPanelController.State(
        available = true,
        tools = listOfNotNull(tool),
        tool = tool,
        phase = ToolPhase.FAILED,
        preview = null,
        failure = failure,
        canUndo = false,
    )

    @Test
    fun aToolErrorKeepsTheToolTitleAndTheToolsOwnWords() {
        val copy = state(ToolFailure.ToolError("boom from the script")).failedToolCopy()

        assertEquals(FailedToolCopy(title = "加书名号", message = "boom from the script"), copy)
    }

    @Test
    fun aMessageIsTrimmedAndABlankOneFallsBackToTheGenericCopy() {
        assertEquals(
            FailedToolCopy(title = "加书名号", message = "boom"),
            state(ToolFailure.ToolError("  boom  ")).failedToolCopy(),
        )
        assertEquals(
            FailedToolCopy(title = "加书名号", message = null),
            state(ToolFailure.ToolError("   ")).failedToolCopy(),
        )
        assertNull(
            "no title and no words means no tool copy",
            state(ToolFailure.ToolError(null), tool = null).failedToolCopy(),
        )
    }

    @Test
    fun aClassifiedReasonHidesTheRuntimeDiagnostic() {
        val copy =
            state(
                ToolFailure.ToolError("the script ran longer than 30000 ms", code = "timeout"),
            ).failedToolCopy()

        assertEquals(FailedToolCopy(title = "加书名号", message = null, code = "timeout"), copy)
    }

    @Test
    fun aScriptsOwnErrorKeepsItsWordsAndLine() {
        val copy = state(ToolFailure.ToolError("401 密钥无效", code = "script", line = 7)).failedToolCopy()

        assertEquals(FailedToolCopy(title = "加书名号", message = "401 密钥无效", code = null, line = 7), copy)
    }

    @Test
    fun fixedFailuresDoNotCarryToolWords() {
        assertNull(state(ToolFailure.TimedOut).failedToolCopy())
        assertNull(state(ToolFailure.NoResult).failedToolCopy())
        assertNull(state(ToolFailure.Cancelled).failedToolCopy())
        assertNull(state(ToolFailure.Unavailable(UnavailableReason.NO_TEXT)).failedToolCopy())
    }
}
