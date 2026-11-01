package app.songyan.ime.ime

import android.view.View
import android.view.ViewConfiguration
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** The long-press delay and the bottom gap reach the running keyboard, from the stored preferences. */
@RunWith(AndroidJUnit4::class)
class KeyboardFeelDeviceTest {
    @After
    fun resetGeometry() {
        KeyboardGeometry.bottomGapDp = 0
    }

    private fun withHost(
        layout: String = "qwerty26",
        block: (FeatureHost) -> Unit,
    ) {
        val host = FeatureHost(engine.backend(), layout)
        try {
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun FeatureHost.keyHeightPx(): Int =
        main2 {
            controller.view.measure(
                View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(4000, View.MeasureSpec.AT_MOST),
            )
            controller.view.measuredHeight
        }

    @Test
    fun theKeyFlickPreferenceReachesAShownKeyboard() =
        withHost { host ->
            host.start()
            assertTrue(host.main2 { host.controller.keyboard.keyFlickEnabled })
            host.keyFlickFlow.value = false
            assertTrue(host.await { !host.main2 { host.controller.keyboard.keyFlickEnabled } })
        }

    @Test
    fun aHoldShorterThanTheSystemDelayOpensThePopupOnceTheUserShortenedIt() =
        withHost { host ->
            host.start()
            val system = ViewConfiguration.getLongPressTimeout().toLong()
            assumeTrue("the system delay must leave room for a shorter hold", system >= 350)
            val hold = 250L

            // Following the system: a hold shorter than the system delay is still a tap.
            assertFalse(host.hold("q", hold) { host.controller.keyboard.popupOpen })

            host.longPressFlow.value = 150
            assertTrue(host.await { host.main2 { host.controller.keyboard.longPressOverrideMs } == 150 })
            assertTrue(host.hold("q", hold) { host.controller.keyboard.popupOpen })

            // And back to the system's delay.
            host.longPressFlow.value = null
            assertTrue(host.await { host.main2 { host.controller.keyboard.longPressOverrideMs } == null })
            assertFalse(host.hold("q", hold) { host.controller.keyboard.popupOpen })
        }

    @Test
    fun slidingAlongTheKeyRowPicksAnAlternateWithoutReachingThePopup() =
        withHost { host ->
            host.start()
            var mirrored = false
            val labels =
                host.hold("b", ViewConfiguration.getLongPressTimeout() + 200L) {
                    mirrored = host.controller.keyboard.popupMirrored
                    host.controller.keyboard.popupOptions
                        .map { it.label }
                }
            assumeTrue("b needs three alternates: $labels", labels.size >= 3)
            // Lifting a finger that rested on the key committed the first alternate.
            assertTrue("committed: ${host.text()}", host.await { host.text() == labels[0] })
            val keyboard = host.main2 { host.controller.keyboard }
            val dp = host.main2 { keyboard.density }
            // Near the right edge the alternates run leftwards, and so does the slide.
            val cell = host.main2 { keyboard.metrics.popupCellWidthDp * dp } * (if (mirrored) -1 else 1)
            val drift = if (mirrored) -4 * dp else 4 * dp
            // The thumb stays at the key's height, drifts a little down, and never enters the popup.
            val seen =
                host.holdAndSlide(
                    "b",
                    listOf(drift to 0f, cell to 4 * dp, 2 * cell to 20 * dp, 2 * cell + drift to 24 * dp),
                ) { keyboard.popupIndex }
            assertEquals(listOf(0, 1, 2, 2), seen)
            assertTrue("committed: ${host.text()}", host.await { host.text() == labels[0] + labels[2] })
        }

    @Test
    fun aLongerChosenDelayKeepsAHoldThatTheSystemWouldHaveOpened() =
        withHost { host ->
            host.start()
            val system = ViewConfiguration.getLongPressTimeout().toLong()
            host.longPressFlow.value = 1000
            assertTrue(host.await { host.main2 { host.controller.keyboard.longPressOverrideMs } == 1000 })
            assertFalse(host.hold("q", system + 300) { host.controller.keyboard.popupOpen })
        }

    @Test
    fun theBottomGapRaisesTheKeyboardByItsSizeAndKeepsTheKeys() =
        withHost { host ->
            host.start()
            val screenHeightDp = host.context.resources.configuration.screenHeightDp
            assumeTrue("a short window caps the gap", screenHeightDp > KeyboardGeometry.SHORT_WINDOW_DP)
            val density = host.context.resources.displayMetrics.density
            val before = host.keyHeightPx()
            val rowBefore = host.main2 { host.controller.keyboard.metrics.rowHeightDp }

            host.bottomGapFlow.value = 24
            assertTrue(host.await { KeyboardGeometry.bottomGapDp == 24 })
            val after = host.keyHeightPx()

            assertEquals((24 * density).toInt().toFloat(), (after - before).toFloat(), 2f)
            assertEquals(rowBefore, host.main2 { host.controller.keyboard.metrics.rowHeightDp })

            host.bottomGapFlow.value = 0
            assertTrue(host.await { KeyboardGeometry.bottomGapDp == 0 })
            assertEquals(before, host.keyHeightPx())
        }

    @Test
    fun theSwitchKeyAppearsOnlyWhileTheUserHasItOnAndSwitchesOnATap() =
        withHost { host ->
            host.start()
            val name = host.string(R.string.ime_toolbar_switch_input_method)
            assertFalse(host.labelsOf(KeyboardView::class.java).contains(name))

            host.switchKeyFlow.value = true
            assertTrue(host.await { host.labelsOf(KeyboardView::class.java).contains(name) })
            host.tap(name)
            assertEquals(listOf("switch"), host.inputMethodCalls)

            host.longPress(name, KeyboardView::class.java)
            assertEquals(listOf("switch", "pick"), host.inputMethodCalls)

            host.switchKeyFlow.value = false
            assertTrue(host.await { !host.labelsOf(KeyboardView::class.java).contains(name) })
        }

    @Test
    fun theSwitchKeyAlsoAppearsOnTheNineKeyPad() =
        withHost("phone9") { host ->
            host.start()
            val name = host.string(R.string.ime_toolbar_switch_input_method)
            assertFalse(host.labelsOf(KeyboardView::class.java).contains(name))

            host.switchKeyFlow.value = true
            assertTrue(
                "${host.labelsOf(KeyboardView::class.java)}",
                host.await { host.labelsOf(KeyboardView::class.java).contains(name) },
            )
            host.tap(name)
            assertEquals(listOf("switch"), host.inputMethodCalls)
        }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("feel", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
