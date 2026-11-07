package app.songyan.ime.ime

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.ime.panels.EmojiPanelView
import app.songyan.ime.ime.panels.KeyboardSettingsPanelView
import app.songyan.ime.ime.panels.PhraseClipboardPanelView
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/** The keyboard settings panel: open it from the toolbar, change settings, see the keyboard follow. */
@RunWith(AndroidJUnit4::class)
class KeyboardSettingsPanelDeviceTest {
    private fun withHost(block: (FeatureHost) -> Unit) {
        val host = FeatureHost(engine.backend())
        try {
            block(host)
        } finally {
            host.close()
            kotlinx.coroutines.runBlocking { host.backend.shutdown() }
        }
    }

    private fun FeatureHost.openPanel() {
        tapToolbar(string(R.string.ime_menu_keyboard_settings))
        assertNotNull(findOrNullOnMain(KeyboardSettingsPanelView::class.java))
    }

    private fun <V : android.view.View> FeatureHost.findOrNullOnMain(type: Class<V>): V? = main2 { findOrNull(type) }

    private fun FeatureHost.tile(setting: Int) =
        tap(string(setting), KeyboardSettingsPanelView::class.java, prefix = true)

    @Test
    fun theGearOpensAGridWithTheHighFrequencySettings() =
        withHost { host ->
            host.start()
            host.openPanel()
            val labels = host.labelsOf(KeyboardSettingsPanelView::class.java)
            for (
            name in
            listOf(
                R.string.ime_qs_one_hand,
                R.string.ime_qs_key_height,
                R.string.ime_qs_layout,
                R.string.ime_qs_appearance,
                R.string.ime_qs_key_sound,
                R.string.ime_qs_key_haptics,
                R.string.ime_qs_key_preview,
            )
            ) {
                assertTrue("no tile ${host.string(name)} in $labels", labels.any { it.startsWith(host.string(name)) })
            }
            assertTrue(host.string(R.string.ime_qs_more) in labels)
        }

    @Test
    fun choosingOneHandNarrowsTheKeyboardAndIsStored() =
        withHost { host ->
            host.start()
            assumeTrue(
                "one-handed mode needs a phone-width portrait window",
                host.main2 { host.controller.shell.isAvailable() },
            )
            host.openPanel()
            host.tile(R.string.ime_qs_one_hand)
            host.tap(host.string(R.string.ime_qs_left), KeyboardSettingsPanelView::class.java)
            assertEquals("left", host.oneHandFlow.value)
            assertTrue(host.await { host.main2 { host.controller.oneHandMode } == KeyboardOneHandMode.LEFT })
            assertEquals(KeyboardOneHandMode.LEFT, host.main2 { host.controller.shell.mode })
            // The tile now names the value, and the panel stayed open.
            val labels = host.labelsOf(KeyboardSettingsPanelView::class.java)
            assertTrue(
                labels.toString(),
                labels.contains(
                    host.string(R.string.ime_qs_label_value).format(
                        host.string(R.string.ime_qs_one_hand),
                        host.string(R.string.ime_qs_left),
                    ),
                ),
            )
        }

    @Test
    fun theToolbarButtonTurnsOneHandOnAndOff() =
        withHost { host ->
            host.start()
            assumeTrue(
                "one-handed mode needs a phone-width portrait window",
                host.main2 { host.controller.shell.isAvailable() },
            )
            val oneHand = host.string(R.string.ime_qs_one_hand)
            host.tap(oneHand, ToolbarView::class.java, prefix = true)
            assertTrue(host.await { host.main2 { host.controller.shell.mode } == KeyboardOneHandMode.RIGHT })
            assertEquals("right", host.oneHandFlow.value)
            val label = host.string(R.string.ime_qs_label_value).format(oneHand, host.string(R.string.ime_qs_right))
            assertTrue(host.toolbarLabels().toString(), host.await { label in host.toolbarLabels() })
            host.tap(oneHand, ToolbarView::class.java, prefix = true)
            assertTrue(host.await { host.main2 { host.controller.shell.mode } == KeyboardOneHandMode.OFF })
            assertEquals("off", host.oneHandFlow.value)
        }

    /** The menu's visible rows, label to the row that takes the tap. */
    private fun FeatureHost.menuRows(): Map<String, android.view.View> =
        main2 {
            val rows = LinkedHashMap<String, android.view.View>()

            fun walk(view: android.view.View) {
                if (view.visibility != android.view.View.VISIBLE) return
                if (view is android.widget.TextView && view.parent is android.widget.LinearLayout) {
                    rows[view.text.toString()] = view.parent as android.view.View
                }
                if (view is android.view.ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
            }
            walk(controller.menu)
            rows
        }

    @Test
    fun aHiddenToolbarLeavesTheRowEmptyAndTheMenuCarriesItsEntries() =
        withHost { host ->
            host.hideToolbarFlow.value = true
            host.start()
            assertTrue(host.await { host.toolbarLabels().isEmpty() })
            host.main2 { host.controller.openKeyboardMenu() }
            assertTrue(host.await { host.main2 { host.controller.menuOpen } })
            val rows = host.menuRows()
            val emoji = host.string(R.string.ime_toolbar_emoji)
            for (label in listOf(
                emoji,
                host.string(R.string.ime_toolbar_edit),
                host.string(R.string.ime_menu_quick_content),
            )) {
                assertTrue("no row $label in ${rows.keys}", label in rows)
            }
            val oneHandAvailable = host.main2 { host.controller.shell.isAvailable() }
            assertEquals(
                rows.keys.toString(),
                oneHandAvailable,
                rows.keys.any { it.startsWith(host.string(R.string.ime_qs_one_hand)) },
            )
            host.main2 { rows.getValue(emoji).performClick() }
            assertTrue(host.await { host.findOrNullOnMain(EmojiPanelView::class.java) != null })

            // Turning the switch off brings the toolbar back and the extra rows leave the menu.
            host.main2 { host.controller.closePanels() }
            host.hideToolbarFlow.value = false
            assertTrue(host.await { host.toolbarLabels().isNotEmpty() })
            host.main2 { host.controller.openKeyboardMenu() }
            assertTrue(host.await { emoji !in host.menuRows() })
        }

    @Test
    fun choosingAKeyHeightResizesTheKeysAndIsStored() =
        withHost { host ->
            host.start()
            host.openPanel()
            host.tile(R.string.ime_qs_key_height)
            host.tap(host.string(R.string.ime_qs_height_tall), KeyboardSettingsPanelView::class.java)
            assertEquals("tall", host.keyHeightFlow.value)
            assertTrue(host.await { KeyboardGeometry.keyHeight == KeyHeight.TALL })
            host.tile(R.string.ime_qs_key_height)
            host.tap(host.string(R.string.ime_qs_height_compact), KeyboardSettingsPanelView::class.java)
            assertTrue(host.await { KeyboardGeometry.keyHeight == KeyHeight.COMPACT })
            assertEquals("compact", host.keyHeightFlow.value)
            KeyboardGeometry.keyHeight = KeyHeight.DEFAULT
        }

    @Test
    fun theAppearanceChoiceIsStored() =
        withHost { host ->
            host.start()
            host.openPanel()
            host.tile(R.string.ime_qs_appearance)
            host.tap(host.string(R.string.ime_qs_mode_dark), KeyboardSettingsPanelView::class.java)
            assertEquals("dark", host.colorModeFlow.value)
        }

    @Test
    fun theKeyPreviewTogglesAtOnce() =
        withHost { host ->
            host.start()
            host.openPanel()
            assertTrue(host.main2 { host.controller.keyboard.keyPreviewEnabled })
            host.tile(R.string.ime_qs_key_preview)
            assertTrue(host.await { !host.main2 { host.controller.keyboard.keyPreviewEnabled } })
        }

    @Test
    fun backLeavesAnOpenChoiceBeforeThePanel() =
        withHost { host ->
            host.start()
            host.openPanel()
            host.tile(R.string.ime_qs_key_height)
            assertTrue(host.main2 { host.controller.closePanels() })
            assertNotNull(host.findOrNullOnMain(KeyboardSettingsPanelView::class.java))
            assertTrue(
                host.labelsOf(KeyboardSettingsPanelView::class.java).any {
                    it.startsWith(host.string(R.string.ime_qs_one_hand))
                },
            )
            assertTrue(host.main2 { host.controller.closePanels() })
            assertTrue(host.await { host.findOrNullOnMain(KeyboardSettingsPanelView::class.java) == null })
        }

    @Test
    fun closingAPanelRestoresTheKeysBeforeItsExitAnimationEnds() =
        withHost { host ->
            ImeMotion.scaleOverride = 1f
            try {
                host.start()
                host.tap(host.string(R.string.ime_language_to_english))
                host.openPanel()
                host.main {
                    assertTrue(host.controller.closePanels())
                    // Check in the closing event itself, before an animation frame can restore the keys.
                    assertEquals(android.view.View.VISIBLE, host.controller.keyboard.visibility)
                }
                host.tap("a")
                host.assertText("a")
            } finally {
                ImeMotion.scaleOverride = null
            }
        }

    @Test
    fun theLayoutTileOpensTheMenuInPlaceOfTheKeys() =
        withHost { host ->
            // The panel's exit has to animate for its end to race the menu.
            ImeMotion.scaleOverride = 1f
            try {
                host.start()
                host.openPanel()
                host.tile(R.string.ime_qs_layout)
                assertTrue(host.await { host.main2 { host.controller.menuOpen } })
                // Past the panel's exit: its end must not bring the keys back under the menu.
                Thread.sleep(ImeMotion.PANEL_MS * 4)
                host.awaitIdle()
                host.main2 {
                    assertEquals(android.view.View.VISIBLE, host.controller.menu.visibility)
                    assertEquals(android.view.View.GONE, host.controller.keyboard.visibility)
                    assertEquals(android.view.View.GONE, host.controller.barRow.visibility)
                }
            } finally {
                ImeMotion.scaleOverride = null
            }
        }

    @Test
    fun moreSettingsOpensTheSettingsApp() =
        withHost { host ->
            host.start()
            host.openPanel()
            host.tap(host.string(R.string.ime_qs_more), KeyboardSettingsPanelView::class.java)
            assertEquals(1, host.settingsOpened)
            assertTrue(host.await { host.findOrNullOnMain(KeyboardSettingsPanelView::class.java) == null })
        }

    @Test
    fun thePhrasesTileOpensPhrasesWithTheQuickInsertSection() =
        withHost { host ->
            host.start()
            host.openPanel()
            host.tap(host.string(R.string.ime_panel_quick_content), KeyboardSettingsPanelView::class.java)
            assertNotNull(host.findOrNullOnMain(PhraseClipboardPanelView::class.java))
        }

    companion object {
        private lateinit var engine: DeployedEngine

        @BeforeClass
        @JvmStatic
        fun deploy() {
            engine = DeployedEngine("settings-panel", english = false)
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            engine.delete()
        }
    }
}
