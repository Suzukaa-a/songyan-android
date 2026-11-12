// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.text.InputType
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The own-panel access point through the app's real host code: [AddonPanelController] creates the
 * SurfaceView in a keyboard-sized container, passes the SurfaceView host token to the installed
 * sample addon, attaches the returned SurfacePackage and answers the panel's editor operations.
 *
 * The controller is the same code the IME service wires into the keyboard window; this window is a
 * debug activity so the test does not have to type into a live keyboard. Touch delivery, sizing and
 * process death were measured on a real IME window by scripts/probes/android-addon.
 */
@RunWith(AndroidJUnit4::class)
class AddonPanelDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().context
    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val samplePackage = "app.songyan.ime.addon.sample"

    private fun store(): AddonStore = AddonStore(File(targetContext.filesDir, "addons"))

    private class FakeEditorAccess : AddonEditorAccess {
        override var hasEditor: Boolean = true
        override var isPassword: Boolean = false
        override var isComposing: Boolean = false
        override var selection: IntRange? = 0 until 2
        var selected: String? = "选区内容"
        var before: String? = "光标前的文字"
        val inserted: MutableList<String> = mutableListOf()
        var deleted: Int = 0

        override fun selectedText(): String? = selected

        override fun textBeforeCursor(maxUnits: Int): String? = before

        override fun insert(text: String) {
            inserted += text
        }

        override fun deleteBefore(units: Int) {
            deleted += units
        }
    }

    @Before
    fun requireTheSampleInstalled() {
        val installed = runCatching { context.packageManager.getPackageInfo(samplePackage, 0) }.isSuccess
        assumeTrue(
            "install the sample addon first: adb install -r addon-sample/build/outputs/apk/debug/addon-sample-debug.apk",
            installed,
        )
        store().write(StoredAddonState())
    }

    private fun registryWithSample(): AddonRegistry =
        kotlinx.coroutines.runBlocking {
            val registry = createAddonRegistry(targetContext)
            registry.scan(negotiate = true)
            registry.setEnabled(samplePackage, true)
            registry
        }

    @Test
    fun thePanelEmbedsReceivesTouchesAndSurvivesTheAddonDeath() {
        val registry = registryWithSample()
        val editor = FakeEditorAccess()
        val scenario = ActivityScenario.launch(AddonPanelHostProbeActivity::class.java)
        try {
            lateinit var controller: AddonPanelController
            lateinit var activity: AddonPanelHostProbeActivity
            scenario.onActivity { host ->
                activity = host
                controller =
                    AddonPanelController(
                        context = host,
                        container = host.container,
                        keyboardView = host.keyboardView,
                        editorAccess = editor,
                        editorInfo = {
                            EditorInfo().apply { inputType = InputType.TYPE_CLASS_TEXT }
                        },
                        registry = registry,
                    )
                assertTrue(controller.open(samplePackage))
                // keyboard, transparent surface, loading chip: the keyboard stays visible and a
                // chip says what is happening, so the window is never a blank rectangle.
                assertEquals(3, host.container.childCount)
                assertEquals(View.VISIBLE, host.keyboardView.visibility)
                assertTrue(hasText(host.container, host.getString(R.string.addon_panel_opening)))
            }
            assertTrue("the addon's surface never arrived", waitFor { controller.surfaceAttached() })
            // The surface fades in over the keyboard (180 ms); only then is the keyboard hidden and
            // the loading chip removed.
            var fadeDone = false
            assertTrue(
                "the panel never finished fading in over the keyboard",
                waitFor {
                    scenario.onActivity { host ->
                        val surface = host.container.getChildAt(1) as SurfaceView
                        fadeDone =
                            surface.alpha == 1f &&
                            host.keyboardView.visibility == View.GONE &&
                            host.container.childCount == 2
                    }
                    fadeDone
                },
            )

            // The panel of the sample addon: title, context line, then the first button (insert).
            val density = targetContext.resources.displayMetrics.density
            val location = IntArray(2)
            val size = IntArray(2)
            scenario.onActivity { host ->
                val surface = host.container.getChildAt(1) as SurfaceView
                surface.getLocationOnScreen(location)
                size[0] = surface.width
                size[1] = surface.height
            }
            assertTrue("the panel surface has no size", size[0] > 0 && size[1] > 0)
            val centerX = location[0] + size[0] / 2
            val insertY = location[1] + ((8 + 32 + 32 + 22) * density).toInt()
            tap(centerX, insertY)
            assertTrue("the panel's insert button never reached the host", waitFor { editor.inserted.isNotEmpty() })
            assertEquals("Hello from the panel", editor.inserted.first())

            // Kill the addon while its panel is on screen: the host must close the panel, show the
            // keyboard view again and stay alive.
            forceStop(samplePackage)
            assertTrue("the host did not close the panel after the addon died", waitFor { !controller.isShown })
            scenario.onActivity { host ->
                // The failed call leaves the keyboard visible plus an error chip with Retry.
                assertEquals(2, host.container.childCount)
                assertEquals(View.VISIBLE, host.keyboardView.visibility)
                assertTrue(hasText(host.container, host.getString(R.string.addon_panel_failed)))
            }
            assertTrue(
                "the failed panel call must be recorded",
                waitFor {
                    registry
                        .entries()
                        .singleOrNull { it.packageName == samplePackage }
                        ?.lastCall
                        ?.error != null
                },
            )
        } finally {
            scenario.close()
        }
    }

    @Test
    fun thePanelFadesInOverTheKeyboardWithoutABlankWindow() {
        val registry = registryWithSample()
        val scenario = ActivityScenario.launch(AddonPanelHostProbeActivity::class.java)
        try {
            lateinit var controller: AddonPanelController
            var loading = false
            var keyboardHeight = 0
            scenario.onActivity { host ->
                controller =
                    AddonPanelController(
                        context = host,
                        container = host.container,
                        keyboardView = host.keyboardView,
                        editorAccess = FakeEditorAccess(),
                        editorInfo = { EditorInfo() },
                        registry = registry,
                    )
                assertTrue(controller.open(samplePackage))
                val surface = host.container.getChildAt(1) as SurfaceView
                // While the addon binds, the keyboard is still drawn, the surface is transparent and
                // a loading chip explains the wait.
                loading =
                    host.keyboardView.visibility == View.VISIBLE &&
                    surface.alpha == 0f &&
                    hasText(host.container, host.getString(R.string.addon_panel_opening))
                keyboardHeight = host.keyboardView.height
            }
            assertTrue("the loading state must keep the keyboard visible", loading)

            assertTrue("the addon's surface never arrived", waitFor { controller.surfaceAttached() })
            var settled = false
            assertTrue(
                "the panel did not fade in and hide the keyboard",
                waitFor {
                    scenario.onActivity { host ->
                        val surface = host.container.getChildAt(1) as SurfaceView
                        settled =
                            surface.alpha == 1f &&
                            host.keyboardView.visibility == View.GONE &&
                            host.container.childCount == 2
                    }
                    settled
                },
            )
            scenario.onActivity { host ->
                // The panel covers the same area as the keyboard: no blank band between them.
                val surface = host.container.getChildAt(1) as SurfaceView
                assertEquals(keyboardHeight, surface.height)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun closingThePanelFromTheHostRestoresTheKeyboard() {
        val registry = registryWithSample()
        val scenario = ActivityScenario.launch(AddonPanelHostProbeActivity::class.java)
        try {
            lateinit var controller: AddonPanelController
            scenario.onActivity { host ->
                controller =
                    AddonPanelController(
                        context = host,
                        container = host.container,
                        keyboardView = host.keyboardView,
                        editorAccess = FakeEditorAccess(),
                        editorInfo = { EditorInfo() },
                        registry = registry,
                    )
                assertTrue(controller.open(samplePackage))
            }
            assertTrue(waitFor { controller.surfaceAttached() })
            scenario.onActivity {
                assertTrue(controller.close())
                assertEquals(1, it.container.childCount)
                assertEquals(android.view.View.VISIBLE, it.keyboardView.visibility)
            }
            assertFalse(controller.isShown)
            assertTrue(
                "the panel call must reach the registry",
                waitFor {
                    registry.entries().singleOrNull { it.packageName == samplePackage }?.lastCall != null
                },
            )
            val call = registry.entries().singleOrNull { it.packageName == samplePackage }?.lastCall
            assertEquals(AddonCallKinds.PANEL, call?.kind)
            assertNull(call?.error)
        } finally {
            scenario.close()
        }
    }

    private fun hasText(
        view: View,
        text: String,
    ): Boolean {
        if (view is TextView && view.text.toString() == text) return true
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                if (hasText(view.getChildAt(index), text)) return true
            }
        }
        return false
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

    private fun tap(
        x: Int,
        y: Int,
    ) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val downTime = SystemClock.uptimeMillis()
        val down =
            android.view.MotionEvent.obtain(
                downTime,
                downTime,
                android.view.MotionEvent.ACTION_DOWN,
                x.toFloat(),
                y.toFloat(),
                0,
            )
        uiAutomation.injectInputEvent(down, true)
        down.recycle()
        SystemClock.sleep(60)
        val up =
            android.view.MotionEvent.obtain(
                downTime,
                SystemClock.uptimeMillis(),
                android.view.MotionEvent.ACTION_UP,
                x.toFloat(),
                y.toFloat(),
                0,
            )
        uiAutomation.injectInputEvent(up, true)
        up.recycle()
        SystemClock.sleep(200)
    }

    private fun forceStop(packageName: String) {
        val descriptor: ParcelFileDescriptor =
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "am force-stop " + packageName,
            )
        descriptor.close()
        SystemClock.sleep(300)
    }
}
