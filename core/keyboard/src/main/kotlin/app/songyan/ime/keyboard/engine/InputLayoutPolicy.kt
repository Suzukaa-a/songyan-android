package app.songyan.ime.keyboard.engine

import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/**
 * Which keyboard a scheme family may be typed on.
 *
 * The shipped shapes (26 / 14 / 18 / 9 keys) fold Latin letters, so a scheme whose own key map is
 * not the Latin alphabet keeps the identity shape; a Latin phonetic scheme folds like any other. A scheme-driven keyboard (zhuyin, cangjie
 * radicals, Japanese kana) is a physical layout of its own and belongs to exactly one family.
 *
 * Everything this table does not name keeps today's behavior: a scheme with no known family may use
 * every shipped shape, and a physical layout this table does not know stays available to everyone.
 * Callers resolve the family from the schema facts once and pass it in; the key path never asks.
 */
object InputLayoutPolicy {
    /** The physical layouts the extended keyboards will register, one family each. */
    const val ZHUYIN_LAYOUT = "zhuyin41"
    const val CANGJIE_LAYOUT = "cangjie26"
    const val ROMAJI_LAYOUT = "romaji26"
    const val KANA_LAYOUT = "kana12"

    /**
     * The scheme family of [schemaID]: its id first, then the speller alphabet it declares. A
     * declared script conversion never changes the family, so the caller passes none.
     */
    fun familyOf(
        schemaID: String,
        alphabet: String = "",
    ): InputSchemeFamily = InputLanguageResolver.resolve(schemaID, alphabet, declaredConversion = null).family

    /** The physical layout the family types on; null keeps the shipped shapes. */
    fun preferredLayoutID(family: InputSchemeFamily): String? =
        when (family) {
            InputSchemeFamily.ZHUYIN -> ZHUYIN_LAYOUT
            InputSchemeFamily.CANGJIE_RADICALS -> CANGJIE_LAYOUT
            InputSchemeFamily.JAPANESE -> ROMAJI_LAYOUT
            InputSchemeFamily.LATIN_PHONETIC, InputSchemeFamily.GENERIC -> null
        }

    /**
     * Whether the family may use one of the shipped key shapes. A Latin-letter scheme such as
     * Jyutping folds like pinyin, so only the families with keys of their own keep the identity shape.
     */
    fun allows(
        kind: KeyboardLayoutKind,
        family: InputSchemeFamily,
    ): Boolean =
        family == InputSchemeFamily.GENERIC ||
            family == InputSchemeFamily.LATIN_PHONETIC ||
            kind == KeyboardLayoutKind.QWERTY26

    /** Whether the family may use the physical layout [layoutID]. Unknown ids keep today's answer. */
    fun allows(
        layoutID: String,
        family: InputSchemeFamily,
    ): Boolean = OWNER_OF[layoutID]?.let { it == family } ?: true

    private val OWNER_OF =
        mapOf(
            ZHUYIN_LAYOUT to InputSchemeFamily.ZHUYIN,
            CANGJIE_LAYOUT to InputSchemeFamily.CANGJIE_RADICALS,
            ROMAJI_LAYOUT to InputSchemeFamily.JAPANESE,
            KANA_LAYOUT to InputSchemeFamily.JAPANESE,
        )
}
