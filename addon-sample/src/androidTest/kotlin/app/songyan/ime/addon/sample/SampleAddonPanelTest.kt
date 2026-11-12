// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample

import android.os.IBinder
import android.os.SystemClock
import android.view.Display
import android.view.InputDevice
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.addon.AddonBinding
import app.songyan.ime.addon.AddonConnectResult
import app.songyan.ime.addon.AddonHostClient
import app.songyan.ime.addon.AddonInsets
import app.songyan.ime.addon.AddonPanelContext
import app.songyan.ime.addon.AddonTextScope
import app.songyan.ime.addon.sample.debug.PanelHostActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The own-panel access point, tested the way the keyboard will use it: a host window token goes to
 * the addon, the addon returns a SurfaceControlViewHost surface, the host attaches it to a
 * SurfaceView, and injected touches reach the addon's views. A process death in the middle must
 * leave the host able to take the surface down and reconnect.
 */
@RunWith(AndroidJUnit4::class)
class SampleAddonPanelTest {
    private lateinit var client: AddonHostClient
    private val recorder = CallbackRecorder()

    @Before
    fun setUp() {
        client = AddonHostClient(testContext())
    }

    @After
    fun tearDown() {
        client.disconnect()
    }

    private fun connectSample() {
        val result =
            client.connect(
                component = sampleComponent(SAMPLE_SERVICE),
                hostInfo = testHostInfo(),
                listener = recorder,
                bindFlags = AddonBinding.IME_FLAGS,
                timeoutMillis = 5_000,
            )
        assertTrue("connect returned " + result, result is AddonConnectResult.Connected)
    }

    @Test
    fun panelEmbedsInTheHostWindowAndReceivesTouches() {
        val scenario = ActivityScenario.launch(PanelHostActivity::class.java)
        try {
            connectSample()
            val metrics = testContext().resources.displayMetrics
            val density = metrics.density
            val width = metrics.widthPixels
            val height = maxOf((metrics.heightPixels * 0.42f).toInt(), (340 * density).toInt())
            lateinit var activityRef: PanelHostActivity
            scenario.onActivity { activity ->
                activityRef = activity
                activity.surfaceView.layoutParams = FrameLayout.LayoutParams(width, height)
            }
            // The host token for the embedded hierarchy is the SurfaceView's, not the window's.
            val token = awaitHostToken(scenario)
            assertNotNull(token)

            val panelContext =
                AddonPanelContext(
                    hostWindowToken = token,
                    displayId = Display.DEFAULT_DISPLAY,
                    widthPx = width,
                    heightPx = height,
                    densityDpi = metrics.densityDpi,
                    fontScale = 1f,
                    insets = AddonInsets.ZERO,
                    inputType = android.text.InputType.TYPE_CLASS_TEXT,
                    imeOptions = 0,
                    darkMode = true,
                    keyboardColor = 0xFF202124.toInt(),
                )
            val panelHost =
                RecordingPanelHost(
                    attachSurface = { surface ->
                        activityRef.runOnUiThread { activityRef.surfaceView.setChildSurfacePackage(surface) }
                    },
                    initial = panelContext,
                )
            assertTrue(client.openPanel("panel#1", panelContext, panelHost))
            assertTrue("the addon never reported a surface", panelHost.surfaceReady.await(5, TimeUnit.SECONDS))
            assertNotNull(panelHost.surface)
            assertEquals(width, panelHost.surfaceWidth)
            assertEquals(height, panelHost.surfaceHeight)

            // Give the embedded surface a moment to render and register its input channel.
            Thread.sleep(700)

            val location =
                IntArray(2).also { position ->
                    scenario.onActivity { it.surfaceView.getLocationOnScreen(position) }
                }
            val tapX = location[0] + width / 2

            fun tapButton(index: Int) {
                val centerDp =
                    SamplePanelView.FIRST_BUTTON_TOP_DP +
                        index * SamplePanelView.BUTTON_HEIGHT_DP +
                        SamplePanelView.BUTTON_HEIGHT_DP / 2
                tap(tapX, location[1] + (centerDp * density).toInt())
            }

            tapButton(0)
            assertTrue(
                "the insert button of the panel did not reach the host",
                waitFor { panelHost.writes.isNotEmpty() },
            )
            assertEquals(sampleString("panel_plain_text"), panelHost.writes.first().text)

            tapButton(1)
            assertTrue(waitFor { panelHost.writes.size >= 2 })
            assertEquals("", panelHost.writes[1].text)
            assertEquals(1, panelHost.writes[1].deleteBefore)

            tapButton(2)
            assertTrue("the read button of the panel did not reach the host", waitFor { panelHost.reads.isNotEmpty() })
            assertEquals(AddonTextScope.SELECTION, panelHost.reads.first().mode)

            tapButton(3)
            assertTrue(
                "the resize button of the panel did not reach the host",
                waitFor { panelHost.sizeRequests.isNotEmpty() },
            )
            assertTrue(panelHost.sizeRequests.first().second > height)

            tapButton(4)
            assertTrue(
                "the close button of the panel did not reach the host",
                panelHost.closeRequests.await(3, TimeUnit.SECONDS),
            )

            // Kill the addon while its panel is on screen. The host must survive, drop the surface
            // and be able to connect a fresh instance.
            // The addon kills itself while serving this call, so the call may fail; the host must
            // notice the death and stay usable.
            client.runTextTool("panel#crash", "crash", "x")
            assertTrue("the addon process did not die", recorder.awaitDisconnected(5_000))
            assertFalse(client.isConnected)
            scenario.onActivity { it.surfaceView.clearChildSurfacePackage() }
            assertFalse(activityRef.isFinishing)

            val reconnect =
                client.connect(
                    component = sampleComponent(SAMPLE_SERVICE),
                    hostInfo = testHostInfo(),
                    listener = recorder,
                    bindFlags = AddonBinding.IME_FLAGS,
                    timeoutMillis = 5_000,
                )
            assertTrue("reconnect returned " + reconnect, reconnect is AddonConnectResult.Connected)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun panelContextUpdatesAreAcceptedWhileOpen() {
        val scenario = ActivityScenario.launch(PanelHostActivity::class.java)
        try {
            connectSample()
            val metrics = testContext().resources.displayMetrics
            lateinit var activityRef: PanelHostActivity
            scenario.onActivity { activity -> activityRef = activity }
            val token = awaitHostToken(scenario)
            val initial =
                AddonPanelContext(
                    hostWindowToken = token,
                    displayId = Display.DEFAULT_DISPLAY,
                    widthPx = metrics.widthPixels,
                    heightPx = (metrics.heightPixels / 3),
                    densityDpi = metrics.densityDpi,
                )
            val panelHost =
                RecordingPanelHost(
                    attachSurface = { surface ->
                        activityRef.runOnUiThread { activityRef.surfaceView.setChildSurfacePackage(surface) }
                    },
                    initial = initial,
                )
            assertTrue(client.openPanel("panel#2", initial, panelHost))
            assertTrue(panelHost.surfaceReady.await(5, TimeUnit.SECONDS))

            val changed =
                initial.copy(
                    insets = AddonInsets(topPx = 24, bottomPx = 48),
                    heightPx = (initial.heightPx * 1.2f).toInt(),
                    darkMode = false,
                    keyboardColor = 0xFFFFFFFF.toInt(),
                )
            panelHost.updateContext(changed)
            client.updatePanelContext(changed)
            Thread.sleep(300)
            assertTrue(!recorder.disconnected)
            client.closePanel()
        } finally {
            scenario.close()
        }
    }

    /** The SurfaceView host token exists only once the host window has laid the view out. */
    private fun awaitHostToken(scenario: ActivityScenario<PanelHostActivity>): IBinder? {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            var token: IBinder? = null
            scenario.onActivity { activity -> token = activity.surfaceView.hostToken }
            if (token != null) return token
            Thread.sleep(50)
        }
        return null
    }

    private fun tap(
        x: Int,
        y: Int,
    ) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down =
            MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0).apply {
                source = InputDevice.SOURCE_TOUCHSCREEN
            }
        uiAutomation.injectInputEvent(down, true)
        down.recycle()
        SystemClock.sleep(60)
        val up =
            MotionEvent
                .obtain(
                    downTime,
                    SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_UP,
                    x.toFloat(),
                    y.toFloat(),
                    0,
                ).apply {
                    source = InputDevice.SOURCE_TOUCHSCREEN
                }
        uiAutomation.injectInputEvent(up, true)
        up.recycle()
        SystemClock.sleep(150)
    }
}
