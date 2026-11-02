package app.songyan.ime.content

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportRequestCancellationTest {
    @Test
    fun cancellationDuringPreparationReachesTheRequestOnceItStarts() {
        val cancelled = mutableListOf<String>()
        val cancellation = ImportRequestCancellation { cancelled += it }
        cancellation.beginTask()
        cancellation.cancel()
        assertEquals(emptyList<String>(), cancelled)

        cancellation.attachRequest("original")
        assertEquals(listOf("original"), cancelled)
    }

    @Test
    fun cancellationTargetsTheAttachedRequestAndDoesNotCarryIntoTheNextTask() {
        val cancelled = mutableListOf<String>()
        val cancellation = ImportRequestCancellation { cancelled += it }
        cancellation.beginTask()
        cancellation.attachRequest("original")
        cancellation.cancel()
        cancellation.endTask()
        cancellation.cancel()
        cancellation.beginTask()
        cancellation.attachRequest("retry")
        assertEquals(listOf("original"), cancelled)

        cancellation.cancel()
        assertEquals(listOf("original", "retry"), cancelled)
    }
}
