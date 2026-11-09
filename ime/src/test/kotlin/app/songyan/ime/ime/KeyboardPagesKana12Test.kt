package app.songyan.ime.ime

import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import app.songyan.ime.layout.BuiltinLayouts
import app.songyan.ime.layout.KeyboardComposition
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

class KeyboardPagesKana12Test {
    @Before
    fun installCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    @After
    fun restoreCatalog() {
        KeyboardLayoutCatalog.install(LayoutRegistry.create())
    }

    private fun config(
        english: Boolean = false,
        mainLayoutID: String = "kana12",
    ) = KeyboardPages.Config(
        layout = KeyboardLayoutKind.QWERTY26,
        englishDirectInput = english,
        symbolMarksAscii = false,
        languageSwitch = true,
        lettersReachable = true,
        catalogRevision = KeyboardLayoutCatalog.snapshot.value.revision,
        pairing = KeyboardComposition(mainLayoutID = mainLayoutID),
        inputLanguage = InputLanguage.JA,
    )

    @Test
    fun canonicalKanaKeepsFourRowsAndItsOwnSideControls() {
        val page = KeyboardPages.definition(null, config())
        assertEquals(BuiltinLayouts.kana12.rows, page.rows)
        assertEquals(BuiltinLayouts.kana12.auxiliaryAreas, page.auxiliaryAreas)
        val surface = Kana12Surface.of(page) ?: error("the canonical board must reach the kana renderer")
        assertEquals(12, surface.gridKeys.size)
        assertEquals(3, surface.trailing.size)
    }

    @Test
    fun aCopiedKanaBoardKeepsItsGridAndSideControls() {
        val copy = BuiltinLayouts.kana12.copy(id = "my-kana", name = "My kana")
        KeyboardLayoutCatalog.install(LayoutRegistry.create(layouts = BuiltinLayouts.all + copy))
        val page = KeyboardPages.definition(null, config(mainLayoutID = "my-kana"))
        assertEquals(copy.rows, page.rows)
        val surface = Kana12Surface.of(page, copy) ?: error("a copy must reach the kana renderer")
        assertEquals(12, surface.gridKeys.size)
    }

    @Test
    fun switchingToEnglishUsesLettersInsteadOfInertKana() {
        val page = KeyboardPages.definition(null, config(english = true))
        assertFalse(page.rows.flatMap { it.keys }.any { it.action.type == LayoutActionKind.KANA })
    }
}
