// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
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
 * The text-tool access point against the sample addon as an installed second APK: the tool list is
 * built from the cached handshake, one run binds only when the user presses, and a crash, a timeout
 * or a disabled extension leaves the pipeline usable for the next run.
 */
@RunWith(AndroidJUnit4::class)
class AddonToolPipelineDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val samplePackage = "app.songyan.ime.addon.sample"

    private fun store(): AddonStore = AddonStore(File(context.filesDir, "addons"))

    private suspend fun enabledRegistry(): AddonRegistry {
        val registry = createAddonRegistry(context)
        val sample =
            registry.scan(negotiate = true).firstOrNull { it.packageName == samplePackage }
                ?: error(
                    "install the sample addon first: adb install -r addon-sample/build/outputs/apk/debug/addon-sample-debug.apk",
                )
        assertTrue(sample.isCompatible)
        registry.setEnabled(samplePackage, true)
        return registry
    }

    private fun tool(
        registry: AddonRegistry,
        toolId: String,
    ): ToolDescriptor =
        AddonToolCatalog(registry)
            .tools()
            .firstOrNull { it.id == AddonToolIds.of(samplePackage, toolId) }
            ?: error("the sample tool " + toolId + " is not offered")

    private fun executor(
        registry: AddonRegistry,
        timeoutMillis: Long = 3_500,
    ): AddonToolExecutor = AddonToolExecutor(context, registry, timeoutMillis)

    @Before
    fun requireTheSampleInstalled() {
        val installed = runCatching { context.packageManager.getPackageInfo(samplePackage, 0) }.isSuccess
        assumeTrue(
            "install the sample addon first: adb install -r addon-sample/build/outputs/apk/debug/addon-sample-debug.apk",
            installed,
        )
        store().write(StoredAddonState())
    }

    @Test
    fun theCatalogListsTheSampleTextToolsAndNothingElse() =
        runBlocking {
            val registry = enabledRegistry()
            val tools = AddonToolCatalog(registry).tools()
            assertEquals(
                setOf("uppercase", "bookTitle", "delay", "crash").map { AddonToolIds.of(samplePackage, it) }.toSet(),
                tools.map { it.id }.toSet(),
            )
            assertTrue(tools.all { it.requiredCapabilities == setOf(ToolCapability.TEXT) })
            // The addon's name keeps two tools with the same title apart in the panel.
            assertTrue(tools.single { it.id.endsWith("uppercase") }.title.contains(" · "))
        }

    @Test
    fun aTextToolRunsAcrossTheProcessBoundary() =
        runBlocking {
            val registry = enabledRegistry()
            val results = executor(registry).run(tool(registry, "uppercase"), "hello 世界")
            assertEquals(listOf("HELLO 世界"), results)
            val call = registry.entries().single { it.packageName == samplePackage }.lastCall
            assertNotNull(call)
            assertEquals(AddonCallKinds.TEXT, call?.kind)
            assertNotNull(call?.durationMs)
            assertEquals(null, call?.error)
        }

    @Test
    fun eachSampleToolKeepsItsOwnBehaviour() =
        runBlocking {
            val registry = enabledRegistry()
            assertEquals(listOf("《abc》"), executor(registry).run(tool(registry, "bookTitle"), "abc"))
        }

    @Test
    fun aCrashingAddonFailsTheRunAndTheNextRunStillWorks() =
        runBlocking {
            val registry = enabledRegistry()
            val crashed =
                runCatching {
                    executor(registry).run(tool(registry, "crash"), "x")
                }
            assertTrue("the crashing tool must fail", crashed.isFailure)
            val call = registry.entries().single { it.packageName == samplePackage }.lastCall
            assertNotNull(call?.error)
            assertEquals(listOf("BACK"), executor(registry).run(tool(registry, "uppercase"), "back"))
        }

    @Test
    fun aSlowAddonTimesOutAndLaterRunsStillWork() =
        runBlocking {
            val registry = enabledRegistry()
            val slow = runCatching { executor(registry, timeoutMillis = 300).run(tool(registry, "delay"), "x") }
            assertTrue("a run past the deadline must fail", slow.isFailure)
            assertEquals(listOf("OK"), executor(registry).run(tool(registry, "uppercase"), "ok"))
        }

    @Test
    fun aDisabledExtensionOffersNoToolsAndRefusesRuns() =
        runBlocking {
            val registry = enabledRegistry()
            assertTrue(AddonToolCatalog(registry).tools().isNotEmpty())
            registry.setEnabled(samplePackage, false)
            assertTrue(AddonToolCatalog(registry).tools().isEmpty())
            assertTrue(registry.enabledCompatible().isEmpty())
            val refused =
                runCatching {
                    executor(registry).run(
                        ToolDescriptor(
                            id = AddonToolIds.of(samplePackage, "uppercase"),
                            title = "Uppercase",
                            requiredCapabilities = setOf(ToolCapability.TEXT),
                        ),
                        "x",
                    )
                }
            assertTrue("a disabled extension must not run", refused.isFailure)
            assertFalse(refused.isSuccess)
        }
}
