package app.songyan.ime.content

/**
 * The full-width/half-width mapping the iOS runner gets from Foundation's
 * `fullwidthToHalfwidth` transform.
 *
 * A blanket NFKC normalization is not a substitute: it also rewrites compatibility characters the
 * iOS transform leaves alone (circled numbers, squared katakana, Roman numerals), and it cannot
 * express the transform's asymmetric directions. The tables below hold every non-identity BMP
 * mapping of that transform (printable ASCII and the two spaces are handled arithmetically) and
 * the dakuten pairs it composes, so a width step produces the same text on both platforms.
 */
internal object ToolWidthTransform {
    fun transform(
        text: String,
        mode: ToolStep.WidthMode,
    ): String =
        when (mode) {
            ToolStep.WidthMode.TO_HALF -> toHalf(text)
            ToolStep.WidthMode.TO_FULL -> toFull(text)
        }

    /**
     * Maps full-width printable ASCII, the ideographic space, full-width katakana and the other
     * full-width forms to their half-width spellings. Half-width characters stay as they are.
     */
    private fun toHalf(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            when {
                codePoint == IDEOGRAPHIC_SPACE -> builder.append(' ')
                codePoint in FULLWIDTH_PRINTABLE_START..FULLWIDTH_PRINTABLE_END ->
                    builder.append((codePoint - FULLWIDTH_OFFSET).toChar())
                else -> {
                    val mapped = FORWARD_WIDTH[codePoint]
                    if (mapped != null) builder.append(mapped) else builder.appendCodePoint(codePoint)
                }
            }
            index += Character.charCount(codePoint)
        }
        return builder.toString()
    }

    /**
     * Maps printable ASCII, half-width katakana and the other half-width forms back. Adjacent
     * half-width kana and a dakuten are composed into the precomposed full-width kana when Unicode
     * has one, which is what Foundation does; an uncomposable pair keeps the combining mark.
     */
    private fun toFull(text: String): String {
        val builder = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = text.codePointAt(index)
            val width = Character.charCount(codePoint)
            val markIndex = index + width
            if (markIndex < text.length) {
                val mark = text.codePointAt(markIndex)
                if (mark == VOICED_SOUND_MARK || mark == SEMI_VOICED_SOUND_MARK) {
                    val composed = REVERSE_COMPOSED[pairKey(codePoint, mark)]
                    if (composed != null) {
                        builder.appendCodePoint(composed)
                        index = markIndex + Character.charCount(mark)
                        continue
                    }
                }
            }
            when {
                codePoint == ASCII_SPACE -> builder.append(IDEOGRAPHIC_SPACE.toChar())
                codePoint in ASCII_PRINTABLE_START..ASCII_PRINTABLE_END ->
                    builder.append((codePoint + FULLWIDTH_OFFSET).toChar())
                else -> {
                    val mapped = REVERSE_WIDTH[codePoint]
                    if (mapped != null) builder.appendCodePoint(mapped) else builder.appendCodePoint(codePoint)
                }
            }
            index += width
        }
        return builder.toString()
    }

    private fun pairKey(
        base: Int,
        mark: Int,
    ): Int = (base shl 16) or mark

    private const val ASCII_SPACE: Int = 0x20
    private const val ASCII_PRINTABLE_START: Int = 0x21
    private const val ASCII_PRINTABLE_END: Int = 0x7E
    private const val IDEOGRAPHIC_SPACE: Int = 0x3000
    private const val FULLWIDTH_PRINTABLE_START: Int = 0xFF01
    private const val FULLWIDTH_PRINTABLE_END: Int = 0xFF5E
    private const val FULLWIDTH_OFFSET: Int = 0xFEE0
    private const val VOICED_SOUND_MARK: Int = 0xFF9E
    private const val SEMI_VOICED_SOUND_MARK: Int = 0xFF9F

    private val FORWARD_WIDTH: Map<Int, String> =
        mapOf(
            0x1100 to "\uFFA1",
            0x1101 to "\uFFA2",
            0x1102 to "\uFFA4",
            0x1103 to "\uFFA7",
            0x1104 to "\uFFA8",
            0x1105 to "\uFFA9",
            0x1106 to "\uFFB1",
            0x1107 to "\uFFB2",
            0x1108 to "\uFFB3",
            0x1109 to "\uFFB5",
            0x110A to "\uFFB6",
            0x110B to "\uFFB7",
            0x110C to "\uFFB8",
            0x110D to "\uFFB9",
            0x110E to "\uFFBA",
            0x110F to "\uFFBB",
            0x1110 to "\uFFBC",
            0x1111 to "\uFFBD",
            0x1112 to "\uFFBE",
            0x111A to "\uFFB0",
            0x1121 to "\uFFB4",
            0x1160 to "\uFFA0",
            0x1161 to "\uFFC2",
            0x1162 to "\uFFC3",
            0x1163 to "\uFFC4",
            0x1164 to "\uFFC5",
            0x1165 to "\uFFC6",
            0x1166 to "\uFFC7",
            0x1167 to "\uFFCA",
            0x1168 to "\uFFCB",
            0x1169 to "\uFFCC",
            0x116A to "\uFFCD",
            0x116B to "\uFFCE",
            0x116C to "\uFFCF",
            0x116D to "\uFFD2",
            0x116E to "\uFFD3",
            0x116F to "\uFFD4",
            0x1170 to "\uFFD5",
            0x1171 to "\uFFD6",
            0x1172 to "\uFFD7",
            0x1173 to "\uFFDA",
            0x1174 to "\uFFDB",
            0x1175 to "\uFFDC",
            0x11AA to "\uFFA3",
            0x11AC to "\uFFA5",
            0x11AD to "\uFFA6",
            0x11B0 to "\uFFAA",
            0x11B1 to "\uFFAB",
            0x11B2 to "\uFFAC",
            0x11B3 to "\uFFAD",
            0x11B4 to "\uFFAE",
            0x11B5 to "\uFFAF",
            0x2190 to "\uFFE9",
            0x2191 to "\uFFEA",
            0x2192 to "\uFFEB",
            0x2193 to "\uFFEC",
            0x2502 to "\uFFE8",
            0x25A0 to "\uFFED",
            0x25CB to "\uFFEE",
            0x3001 to "\uFF64",
            0x3002 to "\uFF61",
            0x300C to "\uFF62",
            0x300D to "\uFF63",
            0x3099 to "\uFF9E",
            0x309A to "\uFF9F",
            0x30A1 to "\uFF67",
            0x30A2 to "\uFF71",
            0x30A3 to "\uFF68",
            0x30A4 to "\uFF72",
            0x30A5 to "\uFF69",
            0x30A6 to "\uFF73",
            0x30A7 to "\uFF6A",
            0x30A8 to "\uFF74",
            0x30A9 to "\uFF6B",
            0x30AA to "\uFF75",
            0x30AB to "\uFF76",
            0x30AC to "\uFF76\uFF9E",
            0x30AD to "\uFF77",
            0x30AE to "\uFF77\uFF9E",
            0x30AF to "\uFF78",
            0x30B0 to "\uFF78\uFF9E",
            0x30B1 to "\uFF79",
            0x30B2 to "\uFF79\uFF9E",
            0x30B3 to "\uFF7A",
            0x30B4 to "\uFF7A\uFF9E",
            0x30B5 to "\uFF7B",
            0x30B6 to "\uFF7B\uFF9E",
            0x30B7 to "\uFF7C",
            0x30B8 to "\uFF7C\uFF9E",
            0x30B9 to "\uFF7D",
            0x30BA to "\uFF7D\uFF9E",
            0x30BB to "\uFF7E",
            0x30BC to "\uFF7E\uFF9E",
            0x30BD to "\uFF7F",
            0x30BE to "\uFF7F\uFF9E",
            0x30BF to "\uFF80",
            0x30C0 to "\uFF80\uFF9E",
            0x30C1 to "\uFF81",
            0x30C2 to "\uFF81\uFF9E",
            0x30C3 to "\uFF6F",
            0x30C4 to "\uFF82",
            0x30C5 to "\uFF82\uFF9E",
            0x30C6 to "\uFF83",
            0x30C7 to "\uFF83\uFF9E",
            0x30C8 to "\uFF84",
            0x30C9 to "\uFF84\uFF9E",
            0x30CA to "\uFF85",
            0x30CB to "\uFF86",
            0x30CC to "\uFF87",
            0x30CD to "\uFF88",
            0x30CE to "\uFF89",
            0x30CF to "\uFF8A",
            0x30D0 to "\uFF8A\uFF9E",
            0x30D1 to "\uFF8A\uFF9F",
            0x30D2 to "\uFF8B",
            0x30D3 to "\uFF8B\uFF9E",
            0x30D4 to "\uFF8B\uFF9F",
            0x30D5 to "\uFF8C",
            0x30D6 to "\uFF8C\uFF9E",
            0x30D7 to "\uFF8C\uFF9F",
            0x30D8 to "\uFF8D",
            0x30D9 to "\uFF8D\uFF9E",
            0x30DA to "\uFF8D\uFF9F",
            0x30DB to "\uFF8E",
            0x30DC to "\uFF8E\uFF9E",
            0x30DD to "\uFF8E\uFF9F",
            0x30DE to "\uFF8F",
            0x30DF to "\uFF90",
            0x30E0 to "\uFF91",
            0x30E1 to "\uFF92",
            0x30E2 to "\uFF93",
            0x30E3 to "\uFF6C",
            0x30E4 to "\uFF94",
            0x30E5 to "\uFF6D",
            0x30E6 to "\uFF95",
            0x30E7 to "\uFF6E",
            0x30E8 to "\uFF96",
            0x30E9 to "\uFF97",
            0x30EA to "\uFF98",
            0x30EB to "\uFF99",
            0x30EC to "\uFF9A",
            0x30ED to "\uFF9B",
            0x30EF to "\uFF9C",
            0x30F2 to "\uFF66",
            0x30F3 to "\uFF9D",
            0x30F4 to "\uFF73\uFF9E",
            0x30F7 to "\uFF9C\uFF9E",
            0x30FA to "\uFF66\uFF9E",
            0x30FB to "\uFF65",
            0x30FC to "\uFF70",
            0xFFE0 to "\u00A2",
            0xFFE1 to "\u00A3",
            0xFFE2 to "\u00AC",
            0xFFE3 to "\u00AF",
            0xFFE4 to "\u00A6",
            0xFFE5 to "\u00A5",
            0xFFE6 to "\u20A9",
        )

    private val REVERSE_WIDTH: Map<Int, Int> =
        mapOf(
            0x00A2 to 0xFFE0,
            0x00A3 to 0xFFE1,
            0x00A5 to 0xFFE5,
            0x00A6 to 0xFFE4,
            0x00AC to 0xFFE2,
            0x00AF to 0xFFE3,
            0x20A9 to 0xFFE6,
            0xFF61 to 0x3002,
            0xFF62 to 0x300C,
            0xFF63 to 0x300D,
            0xFF64 to 0x3001,
            0xFF65 to 0x30FB,
            0xFF66 to 0x30F2,
            0xFF67 to 0x30A1,
            0xFF68 to 0x30A3,
            0xFF69 to 0x30A5,
            0xFF6A to 0x30A7,
            0xFF6B to 0x30A9,
            0xFF6C to 0x30E3,
            0xFF6D to 0x30E5,
            0xFF6E to 0x30E7,
            0xFF6F to 0x30C3,
            0xFF70 to 0x30FC,
            0xFF71 to 0x30A2,
            0xFF72 to 0x30A4,
            0xFF73 to 0x30A6,
            0xFF74 to 0x30A8,
            0xFF75 to 0x30AA,
            0xFF76 to 0x30AB,
            0xFF77 to 0x30AD,
            0xFF78 to 0x30AF,
            0xFF79 to 0x30B1,
            0xFF7A to 0x30B3,
            0xFF7B to 0x30B5,
            0xFF7C to 0x30B7,
            0xFF7D to 0x30B9,
            0xFF7E to 0x30BB,
            0xFF7F to 0x30BD,
            0xFF80 to 0x30BF,
            0xFF81 to 0x30C1,
            0xFF82 to 0x30C4,
            0xFF83 to 0x30C6,
            0xFF84 to 0x30C8,
            0xFF85 to 0x30CA,
            0xFF86 to 0x30CB,
            0xFF87 to 0x30CC,
            0xFF88 to 0x30CD,
            0xFF89 to 0x30CE,
            0xFF8A to 0x30CF,
            0xFF8B to 0x30D2,
            0xFF8C to 0x30D5,
            0xFF8D to 0x30D8,
            0xFF8E to 0x30DB,
            0xFF8F to 0x30DE,
            0xFF90 to 0x30DF,
            0xFF91 to 0x30E0,
            0xFF92 to 0x30E1,
            0xFF93 to 0x30E2,
            0xFF94 to 0x30E4,
            0xFF95 to 0x30E6,
            0xFF96 to 0x30E8,
            0xFF97 to 0x30E9,
            0xFF98 to 0x30EA,
            0xFF99 to 0x30EB,
            0xFF9A to 0x30EC,
            0xFF9B to 0x30ED,
            0xFF9C to 0x30EF,
            0xFF9D to 0x30F3,
            0xFF9E to 0x3099,
            0xFF9F to 0x309A,
            0xFFA0 to 0x1160,
            0xFFA1 to 0x1100,
            0xFFA2 to 0x1101,
            0xFFA3 to 0x11AA,
            0xFFA4 to 0x1102,
            0xFFA5 to 0x11AC,
            0xFFA6 to 0x11AD,
            0xFFA7 to 0x1103,
            0xFFA8 to 0x1104,
            0xFFA9 to 0x1105,
            0xFFAA to 0x11B0,
            0xFFAB to 0x11B1,
            0xFFAC to 0x11B2,
            0xFFAD to 0x11B3,
            0xFFAE to 0x11B4,
            0xFFAF to 0x11B5,
            0xFFB0 to 0x111A,
            0xFFB1 to 0x1106,
            0xFFB2 to 0x1107,
            0xFFB3 to 0x1108,
            0xFFB4 to 0x1121,
            0xFFB5 to 0x1109,
            0xFFB6 to 0x110A,
            0xFFB7 to 0x110B,
            0xFFB8 to 0x110C,
            0xFFB9 to 0x110D,
            0xFFBA to 0x110E,
            0xFFBB to 0x110F,
            0xFFBC to 0x1110,
            0xFFBD to 0x1111,
            0xFFBE to 0x1112,
            0xFFC2 to 0x1161,
            0xFFC3 to 0x1162,
            0xFFC4 to 0x1163,
            0xFFC5 to 0x1164,
            0xFFC6 to 0x1165,
            0xFFC7 to 0x1166,
            0xFFCA to 0x1167,
            0xFFCB to 0x1168,
            0xFFCC to 0x1169,
            0xFFCD to 0x116A,
            0xFFCE to 0x116B,
            0xFFCF to 0x116C,
            0xFFD2 to 0x116D,
            0xFFD3 to 0x116E,
            0xFFD4 to 0x116F,
            0xFFD5 to 0x1170,
            0xFFD6 to 0x1171,
            0xFFD7 to 0x1172,
            0xFFDA to 0x1173,
            0xFFDB to 0x1174,
            0xFFDC to 0x1175,
            0xFFE8 to 0x2502,
            0xFFE9 to 0x2190,
            0xFFEA to 0x2191,
            0xFFEB to 0x2192,
            0xFFEC to 0x2193,
            0xFFED to 0x25A0,
            0xFFEE to 0x25CB,
        )

    private val REVERSE_COMPOSED: Map<Int, Int> =
        mapOf(
            pairKey(0xFF76, 0xFF9E) to 0x30AC,
            pairKey(0xFF77, 0xFF9E) to 0x30AE,
            pairKey(0xFF78, 0xFF9E) to 0x30B0,
            pairKey(0xFF79, 0xFF9E) to 0x30B2,
            pairKey(0xFF7A, 0xFF9E) to 0x30B4,
            pairKey(0xFF7B, 0xFF9E) to 0x30B6,
            pairKey(0xFF7C, 0xFF9E) to 0x30B8,
            pairKey(0xFF7D, 0xFF9E) to 0x30BA,
            pairKey(0xFF7E, 0xFF9E) to 0x30BC,
            pairKey(0xFF7F, 0xFF9E) to 0x30BE,
            pairKey(0xFF80, 0xFF9E) to 0x30C0,
            pairKey(0xFF81, 0xFF9E) to 0x30C2,
            pairKey(0xFF82, 0xFF9E) to 0x30C5,
            pairKey(0xFF83, 0xFF9E) to 0x30C7,
            pairKey(0xFF84, 0xFF9E) to 0x30C9,
            pairKey(0xFF8A, 0xFF9E) to 0x30D0,
            pairKey(0xFF8A, 0xFF9F) to 0x30D1,
            pairKey(0xFF8B, 0xFF9E) to 0x30D3,
            pairKey(0xFF8B, 0xFF9F) to 0x30D4,
            pairKey(0xFF8C, 0xFF9E) to 0x30D6,
            pairKey(0xFF8C, 0xFF9F) to 0x30D7,
            pairKey(0xFF8D, 0xFF9E) to 0x30D9,
            pairKey(0xFF8D, 0xFF9F) to 0x30DA,
            pairKey(0xFF8E, 0xFF9E) to 0x30DC,
            pairKey(0xFF8E, 0xFF9F) to 0x30DD,
            pairKey(0xFF73, 0xFF9E) to 0x30F4,
            pairKey(0xFF9C, 0xFF9E) to 0x30F7,
            pairKey(0xFF66, 0xFF9E) to 0x30FA,
        )
}
