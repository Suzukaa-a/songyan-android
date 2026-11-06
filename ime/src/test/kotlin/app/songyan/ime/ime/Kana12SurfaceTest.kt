package app.songyan.ime.ime

import app.songyan.ime.layout.LayoutAction
import app.songyan.ime.layout.LayoutActionKind
import app.songyan.ime.layout.LayoutAuxiliaryArea
import app.songyan.ime.layout.LayoutDefinition
import app.songyan.ime.layout.LayoutInput
import app.songyan.ime.layout.LayoutKey
import app.songyan.ime.layout.LayoutKeyTitleMode
import app.songyan.ime.layout.LayoutPageTarget
import app.songyan.ime.layout.LayoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Kana12SurfaceTest {
    private fun kanaKey(
        label: String,
        rowId: String,
    ): LayoutKey = LayoutKey(label = label, action = LayoutAction.kana(rowId))

    private fun automaticKey(
        label: String,
        action: LayoutAction,
        width: Double? = null,
    ): LayoutKey = LayoutKey(label = label, width = width, action = action, titleMode = LayoutKeyTitleMode.AUTOMATIC)

    private val grid: List<LayoutRow> =
        listOf(
            LayoutRow(listOf(kanaKey("あ", "a"), kanaKey("か", "ka"), kanaKey("さ", "sa"))),
            LayoutRow(listOf(kanaKey("た", "ta"), kanaKey("な", "na"), kanaKey("は", "ha"))),
            LayoutRow(listOf(kanaKey("ま", "ma"), kanaKey("や", "ya"), kanaKey("ら", "ra"))),
            LayoutRow(
                listOf(
                    LayoutKey("小゛゜", action = LayoutAction.kanaModifier),
                    kanaKey("わ", "wa"),
                    LayoutKey("、。?!", action = LayoutAction.literal("、")),
                ),
            ),
        )

    private val leading: LayoutAuxiliaryArea =
        LayoutAuxiliaryArea(
            position = LayoutAuxiliaryArea.Position.LEADING,
            role = LayoutAuxiliaryArea.Role.EDITING,
            keys =
                listOf(
                    LayoutKey("←", action = LayoutAction.cursorLeft),
                    automaticKey("記号", LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS)),
                    automaticKey("あ/A", LayoutAction.languageSwitch()),
                    automaticKey("🌐", LayoutAction(LayoutActionKind.GLOBE)),
                ),
        )

    private val trailing: LayoutAuxiliaryArea =
        LayoutAuxiliaryArea(
            position = LayoutAuxiliaryArea.Position.TRAILING,
            role = LayoutAuxiliaryArea.Role.EDITING,
            keys =
                listOf(
                    automaticKey("⌫", LayoutAction(LayoutActionKind.BACKSPACE)),
                    automaticKey("空格", LayoutAction(LayoutActionKind.SPACE)),
                    automaticKey("⏎", LayoutAction(LayoutActionKind.ENTER), width = 2.0),
                ),
        )

    private fun definition(
        rows: List<LayoutRow> = grid,
        areas: List<LayoutAuxiliaryArea>? = listOf(leading, trailing),
    ): LayoutDefinition =
        LayoutDefinition(
            id = "kana12",
            name = "kana12",
            input = LayoutInput(),
            rows = rows,
            auxiliaryAreas = areas,
        )

    @Test
    fun `the twelve-key board and its two rails are read from the layout`() {
        val surface = Kana12Surface.of(definition()) ?: error("the board was not recognized")
        assertEquals(12, surface.gridKeys.size)
        assertEquals(
            listOf("a", "ka", "sa", "ta", "na", "ha", "ma", "ya", "ra", null, "wa", "punctuation"),
            surface.gridRowIds,
        )
        assertEquals(listOf("←", "記号", "あ/A", "🌐"), surface.leading.map { it.label })
        assertEquals(listOf("⌫", "空格", "⏎"), surface.trailing.map { it.label })
    }

    @Test
    fun `the authored kana labels are kept as they were drawn`() {
        val surface = Kana12Surface.of(definition()) ?: error("the board was not recognized")
        assertEquals("あ", surface.gridKeys.first().label)
        assertEquals("、。?!", surface.gridKeys.last().label)
    }

    @Test
    fun `an authored extra row remains in the ordinary layout`() {
        val footer =
            LayoutRow(
                listOf(
                    automaticKey("123", LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC)),
                    automaticKey("空格", LayoutAction(LayoutActionKind.SPACE)),
                    automaticKey("⏎", LayoutAction(LayoutActionKind.ENTER)),
                ),
            )
        assertNull(Kana12Surface.of(definition(rows = grid + listOf(footer))))
    }

    @Test
    fun `a board without both side rails is not a twelve-key surface`() {
        assertNull(Kana12Surface.of(definition(areas = null)))
        assertNull(Kana12Surface.of(definition(areas = listOf(leading))))
        assertNull(Kana12Surface.of(definition(areas = listOf(trailing))))
    }

    @Test
    fun `a shorter or wider board is not a twelve-key surface`() {
        assertNull(Kana12Surface.of(definition(rows = grid.take(3))))
        val wide = grid.map { row -> LayoutRow(row.keys + kanaKey("ん", "wa")) }
        assertNull(Kana12Surface.of(definition(rows = wide)))
    }

    @Test
    fun `a grid key that types no kana row is not a twelve-key surface`() {
        val rows =
            listOf(
                LayoutRow(
                    listOf(
                        kanaKey("あ", "a"),
                        LayoutKey("x", action = LayoutAction.code("x")),
                        kanaKey("さ", "sa"),
                    ),
                ),
            ) + grid.drop(1)
        assertNull(Kana12Surface.of(definition(rows = rows)))
    }

    @Test
    fun `a second kana grid below the first is not one surface`() {
        assertNull(Kana12Surface.of(definition(rows = grid + grid.take(1))))
    }

    @Test
    fun `the selected main layout supplies the rails a page dropped`() {
        val surface = Kana12Surface.of(definition(areas = null), mainLayout = definition())
        assertEquals(4, surface?.leading?.size)
        assertEquals(3, surface?.trailing?.size)
    }

    @Test
    fun `the punctuation literal still types the kana model's punctuation row`() {
        val key = LayoutKey("、。?!", action = LayoutAction.literal("、"))
        assertEquals("punctuation", kana12GridRowId(key))
        // A mark no kana row taps is left to the ordinary literal dispatch.
        assertNull(kana12GridRowId(LayoutKey("x", action = LayoutAction.literal("x"))))
    }

    @Test
    fun `every kana row key types its row in the released direction`() {
        assertEquals(Kana12Action.Kana("ka", Kana12Direction.Center), kana12Intent(LayoutAction.kana("ka")))
        assertEquals(
            Kana12Action.Kana("ka", Kana12Direction.Up),
            kana12Intent(LayoutAction.kana("ka"), Kana12Direction.Up),
        )
        assertEquals(
            Kana12Action.Kana("punctuation", Kana12Direction.Down),
            kana12Intent(LayoutAction.literal("、"), Kana12Direction.Down),
        )
    }

    @Test
    fun `the surrounding keys report their own intent`() {
        assertEquals(Kana12Action.Modify, kana12Intent(LayoutAction.kanaModifier))
        assertEquals(Kana12Action.CursorLeft, kana12Intent(LayoutAction.cursorLeft))
        assertEquals(Kana12Action.Symbols, kana12Intent(LayoutAction.pageSwitch(LayoutPageTarget.SYMBOLS)))
        assertNull(kana12Intent(LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC)))
        assertEquals(Kana12Action.Language, kana12Intent(LayoutAction.languageSwitch()))
        assertEquals(Kana12Action.Backspace, kana12Intent(LayoutAction(LayoutActionKind.BACKSPACE)))
        assertEquals(Kana12Action.Space, kana12Intent(LayoutAction(LayoutActionKind.SPACE)))
        assertEquals(Kana12Action.Enter, kana12Intent(LayoutAction(LayoutActionKind.ENTER)))
        assertEquals(Kana12Action.Globe, kana12Intent(LayoutAction(LayoutActionKind.GLOBE)))
        assertNull(kana12Intent(LayoutAction.code("a")))
    }

    @Test
    fun `only the kana faces draw as keycaps`() {
        assertEquals(true, isKana12KeyFace(LayoutAction.kana("a")))
        assertEquals(true, isKana12KeyFace(LayoutAction.kanaModifier))
        assertEquals(false, isKana12KeyFace(LayoutAction.cursorLeft))
        assertEquals(false, isKana12KeyFace(LayoutAction(LayoutActionKind.BACKSPACE)))
    }
}
