package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.engine.SchemaKeyProfile.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeneratedLayoutsTest {
    private val letters = ('a'..'z').map { it.toString() }

    @Test
    fun theBuiltInBoardMissesWhatItNeverSends() {
        val wubi = SchemaKeyProfile(Kind.SHAPE_CODE, letters + listOf(";", "'"))
        val missing = LayoutCodeCoverage.missing(BuiltinLayouts.qwerty26, wubi)
        assertTrue(";" in missing)
        assertTrue(letters.none { it in missing })
    }

    @Test
    fun aKeys26BoardSendsEveryCodeTheSchemeNeeds() {
        val profile = SchemaKeyProfile(Kind.SHAPE_CODE, letters + listOf(";", "'"))
        val board = GeneratedLayouts.keys26("wubi86", "26 键＋编码键", profile)
        assertEquals("auto.wubi86.keys26", board.id)
        assertEquals(LayoutInput(schemaID = "wubi86", builtinLayout = LayoutInput.Builtin.QWERTY26), board.input)
        assertEquals(emptyList<String>(), LayoutCodeCoverage.missing(board, profile))
        board.validate()
        assertEquals(
            listOf(";", "'"),
            board.rows[1]
                .keys
                .map { it.label }
                .takeLast(2),
        )
    }

    @Test
    fun neededDigitsBecomeATopRowAndExtraSymbolsJoinIt() {
        val profile = SchemaKeyProfile(Kind.SHAPE_CODE, letters + listOf("3", "1", ";", "'", "`"))
        val board = GeneratedLayouts.keys26("x", "n", profile)
        assertEquals(
            listOf("1", "3", "`"),
            board.rows
                .first()
                .keys
                .map { it.label },
        )
        assertEquals(BuiltinLayouts.qwerty26.rows.size + 1, board.rows.size)
        assertEquals(emptyList<String>(), LayoutCodeCoverage.missing(board, profile))
    }

    @Test
    fun theStrokePadHasOneKeyPerStrokeWithItsCaption() {
        val strokes =
            listOf("h", "s", "p", "n", "z").zip(listOf("⼀", "⼁", "⼃", "⼂", "⼄")).map {
                SchemaKeyProfile.Stroke(it.first, it.second)
            }
        val profile = SchemaKeyProfile(Kind.STROKES, strokes.map { it.code } + listOf("'"), strokes)
        val pad = GeneratedLayouts.strokes("stroke", "笔画", profile)
        assertEquals(listOf("⼀", "⼁", "⼃"), pad.rows[0].keys.map { it.label })
        assertEquals(listOf("⼂", "⼄", "⌫"), pad.rows[1].keys.map { it.label })
        assertEquals(listOf("h", "s", "p"), pad.rows[0].keys.map { it.action.value })
        assertEquals(setOf("h", "s", "p", "n", "z"), LayoutCodeCoverage.declared(pad).filter { it in letters }.toSet())
        pad.validate()
    }

    @Test
    fun aLowercaseKeyCoversItsUppercase() {
        val covered = LayoutCodeCoverage.declared(BuiltinLayouts.qwerty26)
        assertTrue("A" in covered && "a" in covered)
    }
}
