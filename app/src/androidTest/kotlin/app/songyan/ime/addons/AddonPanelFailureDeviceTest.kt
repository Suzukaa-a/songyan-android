// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.addon.AddonCapability
import app.songyan.ime.addon.AddonHandshake
import app.songyan.ime.addon.AddonHostInfo
import app.songyan.ime.addon.AddonProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The panel's failure and race paths, on a device, with the same host controller the IME service
 * uses:
 *  - no host token (the window is not attached): the surface and the hidden keyboard view are undone;
 *  - closing before the token arrives: the late poll must not bind or show a panel;
 *  - an addon that connects but never hands back a surface: the watchdog closes the panel;
 *  - an editor that is not writable is refused without touching the keyboard.
 */
@RunWith(AndroidJUnit4::class)
class AddonPanelFailureDeviceTest {
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val samplePackage = "app.songyan.ime.addon.sample"
    private val stalledService = "app.songyan.ime.addon.sample.debug.StalledPanelAddonService"

    private fun store(): AddonStore = AddonStore(File(targetContext.filesDir, "addons"))

    private fun facts(serviceClassName: String): AddonPackageFacts =
        AddonPackageFacts(
            packageName = samplePackage,
            serviceClassName = serviceClassName,
            label = "Sample Addon",
            versionName = "0.1.0",
            signerSha256 = "AA".repeat(32),
            permissionLabels = emptyList(),
        )

    private fun panelHandshake(): AddonHandshake =
        AddonHandshake(
            protocolVersion = AddonProtocol.VERSION,
            tools = emptyList(),
            supportsPanel = true,
            supportsVoice = false,
            capabilities = setOf(AddonCapability.PANEL),
        )

    private fun discovery(serviceClassName: String): AddonDiscovery =
        object : AddonDiscovery {
            override suspend fun discover(): List<AddonPackageFacts> = listOf(facts(serviceClassName))
        }

    private fun negotiator(): AddonNegotiator =
        object : AddonNegotiator {
            override suspend fun negotiate(
                facts: AddonPackageFacts,
                hostInfo: AddonHostInfo,
            ): AddonNegotiation = AddonNegotiation.Answered(panelHandshake())
        }

    private fun registry(serviceClassName: String): AddonRegistry =
        runBlocking {
            val registry =
                AddonRegistry(
                    store = store(),
                    discovery = discovery(serviceClassName),
                    negotiator = negotiator(),
                    hostInfo = { AddonHostInfo.forHost(targetContext) },
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                )
            registry.scan(negotiate = true)
            registry.setEnabled(samplePackage, true)
            registry
        }

    private class FakeEditorAccess : AddonEditorAccess {
        override val hasEditor: Boolean = true
        override val isPassword: Boolean = false
        override val isComposing: Boolean = false
        override val selection: IntRange? = null

        override fun selectedText(): String? = null

        override fun textBeforeCursor(maxUnits: Int): String? = null

        override fun insert(text: String) = Unit

        override fun deleteBefore(units: Int) = Unit
    }

    private class Recorder {
        val shownChanges: MutableList<Boolean> = CopyOnWriteArrayList()
        var failed: Boolean = false
    }

    private fun controller(
        container: FrameLayout,
        keyboardView: View,
        registry: AddonRegistry,
        recorder: Recorder,
    ): AddonPanelController =
        AddonPanelController(
            context = container.context,
            container = container,
            keyboardView = keyboardView,
            editorAccess = FakeEditorAccess(),
            editorInfo = { EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT } },
            registry = registry,
            onShownChanged = { recorder.shownChanges += it },
            onFailed = { recorder.failed = true },
        )

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
    fun aPanelWithoutAHostTokenRestoresTheKeyboard() {
        val registry = registry("app.songyan.ime.addon.sample.SampleAddonService")
        // A container that is not attached to any window: SurfaceView.getHostToken() never arrives,
        // which is exactly the failure the old code left a blank keyboard behind.
        val container = FrameLayout(targetContext)
        val keyboardView = View(targetContext).apply { layout(0, 0, 1080, 900) }
        container.addView(keyboardView, 1080, 900)
        val recorder = Recorder()
        val panel = controller(container, keyboardView, registry, recorder)
        assertTrue(panel.open(samplePackage))
        // No surface was drawn yet: the keyboard stays visible until it would be.
        assertEquals(View.VISIBLE, keyboardView.visibility)

        assertTrue(
            "the token failure must release the surface",
            waitFor { container.childCount == 1 && keyboardView.visibility == View.VISIBLE },
        )
        assertFalse(panel.isShown)
        assertTrue("the failure must be reported to the service", recorder.failed)
        assertTrue(recorder.shownChanges.isEmpty())
    }

    @Test
    fun closingBeforeTheHostTokenArrivesLeavesNothingBehind() {
        val registry = registry("app.songyan.ime.addon.sample.SampleAddonService")
        val container = FrameLayout(targetContext)
        val keyboardView = View(targetContext)
        container.addView(keyboardView, 1080, 900)
        val recorder = Recorder()
        val panel = controller(container, keyboardView, registry, recorder)
        assertTrue(panel.open(samplePackage))
        assertTrue(panel.close())
        assertEquals(1, container.childCount)
        assertEquals(View.VISIBLE, keyboardView.visibility)
        // The token poll that was in flight must not bind anything or show a panel after the close.
        SystemClock.sleep(1_500)
        assertEquals(1, container.childCount)
        assertEquals(View.VISIBLE, keyboardView.visibility)
        assertFalse(panel.isShown)
        assertTrue(recorder.shownChanges.isEmpty())
    }

    @Test
    fun aPanelWhoseSurfaceNeverArrivesIsClosedByTheWatchdog() {
        val registry = registry(stalledService)
        val recorder = Recorder()
        val scenario = ActivityScenario.launch(AddonPanelHostProbeActivity::class.java)
        try {
            lateinit var panel: AddonPanelController
            scenario.onActivity { host ->
                panel =
                    controller(
                        container = host.container,
                        keyboardView = host.keyboardView,
                        registry = registry,
                        recorder = recorder,
                    )
                assertTrue(panel.open(samplePackage))
                assertEquals(2, host.container.childCount)
                assertEquals(View.VISIBLE, host.keyboardView.visibility)
            }
            assertTrue("the watchdog must close the stalled panel", waitFor(timeoutMillis = 10_000) { !panel.isShown })
            scenario.onActivity { host ->
                assertEquals(1, host.container.childCount)
                assertEquals(View.VISIBLE, host.keyboardView.visibility)
            }
            assertTrue("the timeout must be reported to the service", recorder.failed)
            assertTrue(
                "the timed-out panel call must be recorded",
                waitFor {
                    registry
                        .entries()
                        .singleOrNull { it.packageName == samplePackage }
                        ?.lastCall
                        ?.error != null
                },
            )
            val call = registry.entries().singleOrNull { it.packageName == samplePackage }?.lastCall
            assertNotNull(call)
            assertTrue(call!!.error!!.contains("surface"))
        } finally {
            scenario.close()
        }
    }

    private fun waitFor(
        timeoutMillis: Long = 8_000,
        condition: () -> Boolean,
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(25)
        }
        return condition()
    }
}
