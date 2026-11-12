// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.tools.ToolCatalog
import app.songyan.ime.scripttools.PreloadedToolCatalog
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The toolbar builds on the main thread, and the script catalog's source is a file. This guards the
 * preloaded snapshot the service hands the toolbar: [PreloadedToolCatalog.tools] must answer from
 * memory, never by reading a source on the caller's (main) thread.
 *
 * A synthetic catalog that reads a file inside [ToolCatalog.tools] stands for the script catalog.
 * [CombinedToolCatalog] (the former wiring, still used by these tests only) reads it synchronously
 * on whoever calls tools(), which is exactly the main-thread disk read this fix removes.
 */
@RunWith(AndroidJUnit4::class)
class PreloadedToolCatalogDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private class DiskReadingCatalog(
        private val file: File,
    ) : ToolCatalog {
        var reads: Int = 0
            private set

        override fun tools(): List<ToolDescriptor> {
            reads += 1
            file.readText()
            return listOf(
                ToolDescriptor(
                    id = "disk:tool",
                    title = "Disk tool",
                    requiredCapabilities = setOf(ToolCapability.TEXT),
                ),
            )
        }
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    @Test
    fun theToolbarSnapshotNeverReadsTheSourcesOnTheMainThread() {
        val file = File(context.cacheDir, "tool-catalog-preload-probe.txt").apply { writeText("tool") }
        try {
            val source = DiskReadingCatalog(file)
            val catalog = PreloadedToolCatalog(listOf(source))

            // The toolbar asks before the refresh finished: an empty list and no source read, so the
            // main thread cannot block on the file.
            onMain { assertTrue(catalog.tools().isEmpty()) }
            assertEquals(0, source.reads)

            runBlocking { catalog.refresh() }
            assertEquals(1, source.reads)

            // Later toolbar builds read the snapshot only.
            onMain { assertEquals(1, catalog.tools().size) }
            assertEquals("the snapshot must not re-read its sources", 1, source.reads)
        } finally {
            file.delete()
        }
    }

    @Test
    fun theFormerCombinedCatalogReadsTheSourcesOnTheCallerThread() {
        val file = File(context.cacheDir, "tool-catalog-combined-probe.txt").apply { writeText("tool") }
        try {
            val source = DiskReadingCatalog(file)
            val oldPath = CombinedToolCatalog(listOf(source))
            // This is what the service used to hand the toolbar: tools() reads the file on the
            // caller (in production that caller is the main thread, which is why StrictMode logged
            // it). The call is made from this test thread so the probe itself adds no StrictMode
            // noise; the read count is what makes the old wiring visible.
            assertEquals(1, oldPath.tools().size)
            assertEquals(1, source.reads)
        } finally {
            file.delete()
        }
    }
}
