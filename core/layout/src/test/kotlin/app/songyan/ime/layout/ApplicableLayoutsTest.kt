package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ApplicableLayoutsTest {
    private val families = LayoutFamilyContext.UNKNOWN

    private fun ids(
        scheme: KeyboardScheme,
        registry: LayoutRegistry = LayoutRegistry.create(),
    ) = ApplicableLayouts.of(scheme, registry, families).map { it.id }

    @Test
    fun everyShapeTheEngineCanRunOffersItsOwnLayout() {
        assertEquals(
            listOf(
                "rime_ice:qwerty26#qwerty26",
                "rime_ice:merged14#merged14",
                "rime_ice:merged18#merged18",
                "rime_ice:phone9#phone9",
            ),
            ids(KeyboardScheme("rime_ice", KeyboardLayoutKind.entries.toSet())),
        )
        assertEquals(
            listOf("wubi86:qwerty26#qwerty26"),
            ids(KeyboardScheme("wubi86", setOf(KeyboardLayoutKind.QWERTY26))),
        )
    }

    @Test
    fun aFamilysBoardsComeFirstAndOtherFamiliesBoardsAreLeftOut() {
        val identity = setOf(KeyboardLayoutKind.QWERTY26)
        assertEquals(
            listOf("kagiroi:qwerty26#romaji26", "kagiroi:qwerty26#kana12", "kagiroi:qwerty26#qwerty26"),
            ids(KeyboardScheme("kagiroi", identity)),
        )
        assertEquals(
            listOf("bopomofo:qwerty26#zhuyin41", "bopomofo:qwerty26#qwerty26"),
            ids(KeyboardScheme("bopomofo", identity)),
        )
    }

    @Test
    fun theUsersLayoutsAreOfferedWhereTheyFit() {
        val mine = BuiltinLayouts.qwerty26.copyWithId("my-26", "mine")
        val nine = BuiltinLayouts.phone9.copyWithId("my-9", "mine 9")
        val bound =
            BuiltinLayouts.qwerty26
                .copyWithId("wubi-only", "wubi")
                .copy(input = LayoutInput(schemaID = "wubi86", builtinLayout = LayoutInput.Builtin.QWERTY26))
        val registry = LayoutRegistry.create(layouts = BuiltinLayouts.all + mine + nine + bound)
        assertEquals(
            listOf(
                "rime_ice:qwerty26#qwerty26",
                "rime_ice:qwerty26#my-26",
                "rime_ice:phone9#phone9",
                "rime_ice:phone9#my-9",
            ),
            ids(KeyboardScheme("rime_ice", setOf(KeyboardLayoutKind.QWERTY26, KeyboardLayoutKind.PHONE9)), registry),
        )
        assertEquals(
            listOf("wubi86:qwerty26#qwerty26", "wubi86:qwerty26#my-26", "wubi86:qwerty26#wubi-only"),
            ids(KeyboardScheme("wubi86", setOf(KeyboardLayoutKind.QWERTY26)), registry),
        )
    }
}
