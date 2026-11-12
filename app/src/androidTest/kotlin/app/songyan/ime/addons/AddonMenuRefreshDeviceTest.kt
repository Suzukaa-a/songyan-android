// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The 扩展 page enables an extension; the keyboard process must show its menu row on the next show
 * without being restarted. Two registries over the same state file stand for the two processes: the
 * settings one writes the enable, the keyboard one re-reads it when the keyboard becomes visible.
 */
@RunWith(AndroidJUnit4::class)
class AddonMenuRefreshDeviceTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val samplePackage = "app.songyan.ime.addon.sample"

    private fun store(): AddonStore = AddonStore(File(targetContext.filesDir, "addons"))

    @Before
    fun requireTheSampleInstalled() {
        val installed = runCatching { targetContext.packageManager.getPackageInfo(samplePackage, 0) }.isSuccess
        assumeTrue(
            "install the sample addon first: adb install -r addon-sample/build/outputs/apk/debug/addon-sample-debug.apk",
            installed,
        )
        store().write(StoredAddonState())
    }

    @Test
    fun enablingInSettingsShowsTheMenuRowOnTheNextKeyboardShow() {
        kotlinx.coroutines.runBlocking {
            // The keyboard process started before the user enabled anything.
            val keyboard = createAddonRegistry(targetContext)
            keyboard.scan(negotiate = false)
            assertTrue("a new extension must start disabled", keyboard.enabledCompatible().isEmpty())
            assertTrue("the menu has no extension rows yet", AddonMenu.entries(keyboard).isEmpty())

            // The 扩展 page: its own registry scans (negotiates), then enables the extension. This
            // is what settings writes to the shared state file.
            val settings = createAddonRegistry(targetContext)
            settings.scan(negotiate = true)
            assertTrue(settings.setEnabled(samplePackage, true))

            // Still the keyboard's in-memory snapshot from process start: no row before the refresh.
            assertTrue(AddonMenu.entries(keyboard).isEmpty())

            // What the IME service does on every onStartInputView / menu open.
            keyboard.refreshOnKeyboardShow()

            val rows = AddonMenu.entries(keyboard)
            assertEquals(listOf(samplePackage), rows.map { it.first })
            assertTrue("the row needs a user-visible label", rows.single().second.isNotBlank())
        }
    }
}
