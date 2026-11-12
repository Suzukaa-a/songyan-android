// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.addon.AddonCallers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Discovery and handshake against the repository's sample addon, installed as a real second APK.
 * This is the host side of the protocol end to end: package queries (the manifest `<queries>`
 * declaration), binding the explicit service, the version handshake and the enabled flag that the
 * keyboard later reads without binding.
 *
 * The test needs the sample installed first:
 *   adb -s <serial> install -r addon-sample/build/outputs/apk/debug/addon-sample-debug.apk
 */
@RunWith(AndroidJUnit4::class)
class AddonDiscoveryDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val samplePackage = "app.songyan.ime.addon.sample"

    private fun store(): AddonStore = AddonStore(File(context.filesDir, "addons"))

    @Before
    fun requireTheSampleInstalled() {
        val installed = runCatching { context.packageManager.getPackageInfo(samplePackage, 0) }.isSuccess
        assumeTrue(
            "install the sample addon first: adb install -r " +
                "addon-sample/build/outputs/apk/debug/addon-sample-debug.apk",
            installed,
        )
        store().write(StoredAddonState())
    }

    @Test
    fun theSampleAddonIsDiscoveredAndNegotiatesItsTools() =
        runBlocking {
            val registry = createAddonRegistry(context)
            val sample =
                registry.scan(negotiate = true).firstOrNull { it.packageName == samplePackage }
                    ?: error("the sample addon was not discovered")

            assertTrue(sample.isCompatible)
            assertFalse("a new extension starts off", sample.enabled)
            assertEquals(
                setOf("uppercase", "bookTitle", "delay", "crash"),
                sample.tools.map { it.id }.toSet(),
            )
            assertTrue(sample.handshake?.supportsPanel == true)
            assertTrue(sample.handshake?.supportsVoice == true)
            assertNotNull(sample.facts.signerSha256)
            assertEquals(64, sample.facts.signerSha256?.length ?: 0)
            assertEquals(AddonCallers.sha256Hex(ByteArray(0)).length, 64)
        }

    @Test
    fun enablingANewProcessGetsTheCachedToolsWithoutBinding() =
        runBlocking {
            val first = createAddonRegistry(context)
            val sample =
                first.scan(negotiate = true).firstOrNull { it.packageName == samplePackage }
                    ?: error("the sample addon was not discovered")
            assertTrue(sample.isCompatible)
            first.setEnabled(samplePackage, true)

            // A fresh registry stands for a fresh keyboard process: it reads the enabled flag and
            // the cached handshake from disk, so listing the tools binds nothing.
            val second = createAddonRegistry(context)
            val entries = second.scan(negotiate = false)
            val enabled = entries.filter { it.enabled && it.isCompatible }
            assertEquals(listOf(samplePackage), enabled.map { it.packageName })
            assertEquals(
                setOf("uppercase", "bookTitle", "delay", "crash"),
                enabled
                    .single()
                    .tools
                    .map { it.id }
                    .toSet(),
            )
        }
}
