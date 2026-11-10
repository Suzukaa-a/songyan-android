package app.songyan.ime.ime

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.songyan.ime.layout.KeyboardCompositionKey
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutRegistry
import kotlinx.coroutines.runBlocking
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The keyboard menu offers both Japanese boards, and a tap on a board chip stores the pairing's main
 * layout, moves to the 26-key shape and draws that board, over the real kagiroi package. The fixture
 * is the one [KanaFlickDeviceTest] documents (`-e kagiroiFixture <path>`); a missing package fails.
 */
@RunWith(AndroidJUnit4::class)
class JapaneseBoardMenuDeviceTest {
    @Test
    fun theMenuSwitchesBetweenTheRomajiAndKanaBoards() {
        val backend = KanaFixture.open(prepared, "board-menu")
        val host = FeatureHost(backend, layout = "qwerty26", schemaID = KanaFixture.SCHEMA)
        try {
            host.start()
            host.awaitIdle()
            val romaji = host.string(R.string.ime_layout_romaji26)
            val kana = host.string(R.string.ime_layout_kana12)
            assertEquals(ROMAJI, host.main2 { host.controller.mainDefinition().id })

            host.main { host.controller.openKeyboardMenu() }
            val chips = host.main2 { host.chipTexts() }
            assertTrue("both boards are offered: $chips", romaji in chips && kana in chips)
            assertTrue("the romaji chip is in use", host.main2 { host.chip(romaji).isSelected })
            assertTrue("the kana chip is not in use", !host.main2 { host.chip(kana).isSelected })
            assertEquals(
                host.context.getString(R.string.ime_item_selected, romaji),
                host.main2 { host.chip(romaji).contentDescription.toString() },
            )
            assertEquals(kana, host.main2 { host.chip(kana).contentDescription.toString() })

            host.main { host.chip(kana).performClick() }
            assertTrue("the kana board was never drawn", host.await { host.drawnKanaRows().isNotEmpty() })
            assertEquals(KANA, host.main2 { host.controller.mainDefinition().id })
            assertEquals(KANA, storedBoard())

            host.main { host.controller.openKeyboardMenu() }
            assertTrue("the kana chip is in use", host.main2 { host.chip(kana).isSelected })
            host.main { host.chip(romaji).performClick() }
            assertTrue("the romaji board was never drawn", host.await { host.drawnKanaRows().isEmpty() })
            assertTrue("the romaji keys were never drawn", host.drawnKeys().contains("q"))
            assertEquals(ROMAJI, host.main2 { host.controller.mainDefinition().id })
            assertEquals(ROMAJI, storedBoard())
        } finally {
            host.close()
            runBlocking { backend.shutdown() }
        }
    }

    private fun storedBoard(): String {
        val registry = KeyboardLayoutCatalog.snapshot.value.registry
        val key = KeyboardCompositionKey(KanaFixture.SCHEMA, LayoutInput.Builtin.QWERTY26)
        return registry.compositions.getValue(key.rawValue).mainLayoutID
    }

    /** The kana rows of the drawn board; the view keeps no rows once a board without the kana surface is drawn. */
    private fun FeatureHost.drawnKanaRows(): List<String> =
        main2 {
            val view = find(KeyboardView::class.java)
            if (view.kana12 == null) emptyList() else view.kana12RowIds.filterNotNull()
        }

    private fun FeatureHost.drawnKeys(): List<String> = main2 { find(KeyboardView::class.java).labels }

    private fun FeatureHost.chipTexts(): List<String> = chips().map { it.text.toString() }

    private fun FeatureHost.chip(text: String): TextView =
        requireNotNull(chips().firstOrNull { it.text.toString() == text }) { "no chip '$text' in the menu" }

    /** Every clickable text in the menu's layout section: the chips are the only clickable TextViews. */
    private fun FeatureHost.chips(): List<TextView> {
        val found = mutableListOf<TextView>()

        fun walk(view: View) {
            if (view is TextView && view.isClickable) found += view
            if (view is ViewGroup) for (i in 0 until view.childCount) walk(view.getChildAt(i))
        }
        walk(controller.menu)
        return found
    }

    companion object {
        private const val ROMAJI = "romaji26"
        private const val KANA = "kana12"
        private lateinit var prepared: KanaFixture.Prepared

        @BeforeClass
        @JvmStatic
        fun prepare() {
            prepared = KanaFixture.prepare()
            // No saved pairing: kagiroi starts on its preferred board, the romaji one.
            KeyboardLayoutCatalog.install(LayoutRegistry.create())
        }

        @AfterClass
        @JvmStatic
        fun cleanUp() {
            KeyboardLayoutCatalog.install(LayoutRegistry.create())
            if (::prepared.isInitialized) KanaFixture.cleanup(prepared)
        }
    }
}
