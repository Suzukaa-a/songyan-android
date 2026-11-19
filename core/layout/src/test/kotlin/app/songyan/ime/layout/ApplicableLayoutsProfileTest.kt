package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputSchemeFamily
import app.songyan.ime.keyboard.engine.LayoutFamilyContext
import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.engine.SchemaKeyProfile.Kind
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Layouts offered by what the scheme's speller needs, with the shapes the wubi, stroke and pinyin fixtures stand for. */
class ApplicableLayoutsProfileTest {
    private val families = LayoutFamilyContext.UNKNOWN
    private val registry = LayoutRegistry.create()
    private val all = KeyboardLayoutKind.entries.toSet()
    private val letters = ('a'..'z').map { it.toString() }

    private fun entries(
        id: String,
        profile: SchemaKeyProfile?,
        reg: LayoutRegistry = registry,
    ) = ApplicableLayouts.entries(KeyboardScheme(id, all, profile), reg, families)

    private fun boards(list: List<ApplicableLayouts.Entry>) =
        list.map {
            it.keyboard.layout.wire + "#" +
                it.keyboard.board
        }

    @Test
    fun aShapeCodeSchemeGetsOnlyTwentySixKeyLayoutsWithTheCodeBoardFirst() {
        val wubi = SchemaKeyProfile(Kind.SHAPE_CODE, letters + listOf(";", "'"))
        val list = entries("wubi86", wubi)
        assertEquals(listOf("qwerty26#auto.wubi86.keys26", "qwerty26#qwerty26"), boards(list))
        assertEquals(ApplicableLayouts.Generated.KEYS26, list[0].generated)
        assertEquals(emptyList<String>(), list[0].missingCodes)
        assertEquals(listOf(";", "'"), list[1].missingCodes)
    }

    @Test
    fun aStrokeSchemeGetsThePadFirstThenPlainAndCodeBoards() {
        val strokes =
            listOf("h", "s", "p", "n", "z").zip(listOf("⼀", "⼁", "⼃", "⼂", "⼄")).map {
                SchemaKeyProfile.Stroke(it.first, it.second)
            }
        val profile =
            SchemaKeyProfile(
                Kind.STROKES,
                strokes.map { it.code } + listOf("'", "`"),
                strokes,
                optionalCodes = listOf("'", "`"),
            )
        val list = entries("stroke", profile)
        assertEquals(
            listOf("qwerty26#auto.stroke.strokes", "qwerty26#qwerty26", "qwerty26#auto.stroke.keys26"),
            boards(list),
        )
        assertEquals(ApplicableLayouts.Generated.STROKES, list[0].generated)
        // Only keys the scheme can go without are missing, so the plain board carries no warning.
        assertEquals(emptyList<String>(), list[1].missingCodes)
    }

    @Test
    fun aSyllabicSchemeKeepsEveryShapeAndGetsTheCodeBoardOnlyAsAnExtra() {
        val ice = SchemaKeyProfile(Kind.SYLLABIC, letters + listOf("`", "'"))
        assertEquals(
            listOf(
                "qwerty26#qwerty26",
                "merged14#merged14",
                "merged18#merged18",
                "phone9#phone9",
                "qwerty26#auto.rime_ice.keys26",
            ).sorted(),
            boards(entries("rime_ice", ice)).sorted(),
        )
        assertEquals("qwerty26#qwerty26", boards(entries("rime_ice", ice)).first())
        val plain = SchemaKeyProfile(Kind.SYLLABIC, letters)
        assertEquals(4, entries("pinyin", plain).size)
    }

    @Test
    fun aLayoutWithoutKeysForTheSpellerAlphabetIsNotOffered() {
        val alphabet = "1qaz2wsxedcrfv5tgbyhnujm8ik,9ol.0p;/-".map { it.toString() }
        val zhuyin = SchemaKeyProfile(Kind.SYLLABIC, alphabet + "'", optionalCodes = listOf("'"))
        val family = LayoutFamilyContext.of("bopomofo" to InputSchemeFamily.ZHUYIN)
        val offered =
            ApplicableLayouts.entries(
                KeyboardScheme("bopomofo", setOf(KeyboardLayoutKind.QWERTY26), zhuyin),
                registry,
                family,
            )
        assertEquals(listOf("zhuyin41"), offered.map { it.keyboard.board })

        // Only the delimiter missing: still offered, and nothing to warn about.
        val pinyin = SchemaKeyProfile(Kind.SYLLABIC, letters + "'", optionalCodes = listOf("'"))
        val plain = entries("pinyin", pinyin).first { it.keyboard.layout == KeyboardLayoutKind.QWERTY26 }
        assertEquals(emptyList<String>(), plain.missingCodes)
    }

    @Test
    fun anAuxiliaryKeyTheSpellingNeverUsesDoesNotHidePlainTwentySix() {
        val ice = SchemaKeyProfile(Kind.SYLLABIC, letters + listOf("`", "'"), optionalCodes = listOf("`", "'"))
        val list = entries("rime_ice", ice).filter { it.keyboard.layout == KeyboardLayoutKind.QWERTY26 }
        assertEquals(listOf("qwerty26#qwerty26", "qwerty26#auto.rime_ice.keys26"), boards(list))
        assertEquals(emptyList<String>(), list[0].missingCodes)
    }

    @Test
    fun aSymbolTheSpellingUsesHidesPlainTwentySix() {
        val microsoft = SchemaKeyProfile(Kind.SYLLABIC, letters + listOf(";", "'"), optionalCodes = listOf("'"))
        val list = entries("microsoft", microsoft).filter { it.keyboard.layout == KeyboardLayoutKind.QWERTY26 }
        assertEquals(listOf("qwerty26#auto.microsoft.keys26"), boards(list))
    }

    @Test
    fun anUnknownProfileOffersPlainTwentySixOnly() {
        assertEquals(listOf("qwerty26#qwerty26"), boards(entries("lua_only", SchemaKeyProfile(Kind.UNKNOWN))))
    }

    @Test
    fun aSchemeWithoutAProfileIsOfferedAsBefore() {
        assertEquals(4, entries("old", null).size)
        assertNull(entries("old", null).first().generated)
    }

    @Test
    fun aLayoutTheUserCopiedIsListedWithItsOwnMissingCodes() {
        val copy = BuiltinLayouts.qwerty26.copyWithId("mine", "副本")
        val withCopy = registry.install(copy.encoded())
        val wubi = SchemaKeyProfile(Kind.SHAPE_CODE, letters + listOf(";"))
        val mine = entries("wubi86", wubi, withCopy).single { it.keyboard.board == "mine" }
        assertEquals(listOf(";"), mine.missingCodes)
    }

    @Test
    fun anInstalledGeneratedLayoutIsNotListedTwice() {
        val wubi = SchemaKeyProfile(Kind.SHAPE_CODE, letters + listOf(";", "'"))
        val installed = registry.install(GeneratedLayouts.keys26("wubi86", "26+", wubi).encoded())
        val list = entries("wubi86", wubi, installed)
        assertEquals(listOf("qwerty26#auto.wubi86.keys26", "qwerty26#qwerty26"), boards(list))
        assertEquals(ApplicableLayouts.Generated.KEYS26, list[0].generated)
    }
}
