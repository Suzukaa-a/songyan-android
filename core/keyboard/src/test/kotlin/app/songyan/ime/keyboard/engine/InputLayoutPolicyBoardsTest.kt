package app.songyan.ime.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Every family lists the physical boards it may pick as its main layout, the preferred one first. */
class InputLayoutPolicyBoardsTest {
    @Test
    fun japaneseOffersRomajiFirstThenKana() {
        assertEquals(listOf("romaji26", "kana12"), InputLayoutPolicy.boardsOf(InputSchemeFamily.JAPANESE))
    }

    @Test
    fun zhuyinAndCangjieOfferTheirOneBoard() {
        assertEquals(listOf("zhuyin41"), InputLayoutPolicy.boardsOf(InputSchemeFamily.ZHUYIN))
        assertEquals(listOf("cangjie26"), InputLayoutPolicy.boardsOf(InputSchemeFamily.CANGJIE_RADICALS))
    }

    @Test
    fun theLatinFamiliesKeepTheShippedShapes() {
        assertEquals(emptyList<String>(), InputLayoutPolicy.boardsOf(InputSchemeFamily.LATIN_PHONETIC))
        assertEquals(emptyList<String>(), InputLayoutPolicy.boardsOf(InputSchemeFamily.GENERIC))
    }
}
