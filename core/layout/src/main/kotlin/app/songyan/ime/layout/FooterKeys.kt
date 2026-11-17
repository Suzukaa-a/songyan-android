package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLanguage

/**
 * The generated bottom row of the row pages: `page | 中/英 | mark | 空格 | mark | 回车`. The keyboard
 * builds every page's footer from these pieces, and the layout editor shows and edits a layout
 * without a bottom row of its own as [letters], so the editor starts from the row the keyboard draws.
 */
object FooterKeys {
    /**
     * The width shares of every footer key. The two keys left of the space bar and the two right of
     * it add up to the same share, so the space bar sits on the keyboard's centre line on every
     * page and layout.
     */
    object Weight {
        const val PAGE = 1.4
        const val LANGUAGE = 1.2
        const val MARK = 1.0
        const val ENTER = 1.6
        const val SPACE = 4.8
        const val GLOBE = 1.0

        /** The second key alone on the left (letters unreachable, so no page key) keeps the left side's share. */
        const val PAGE_ALONE = PAGE + LANGUAGE
    }

    /**
     * A generated footer key. Control keys carry the automatic title mode: their face is the host's
     * to name from the input language, and the label is only the fallback the editor shows. A key
     * whose label is the text it types (the marks) leaves the mode unset.
     */
    fun key(
        label: String,
        action: LayoutAction,
        weight: Double,
        longPress: List<LayoutOption>? = null,
        titleMode: LayoutKeyTitleMode? = null,
    ) = LayoutKey(label, width = weight, action = action, longPress = longPress, titleMode = titleMode)

    val SPACE: LayoutKey =
        key("空格", LayoutAction(LayoutActionKind.SPACE), Weight.SPACE, titleMode = LayoutKeyTitleMode.AUTOMATIC)
    val GLOBE: LayoutKey =
        key("🌐", LayoutAction(LayoutActionKind.GLOBE), Weight.GLOBE, titleMode = LayoutKeyTitleMode.AUTOMATIC)
    val ENTER: LayoutKey =
        key("⏎", LayoutAction(LayoutActionKind.ENTER), Weight.ENTER, titleMode = LayoutKeyTitleMode.AUTOMATIC)

    /** The 中/英 key: a tap switches the language, a long press opens the keyboard menu. */
    val LANGUAGE: LayoutKey =
        key("中/英", LayoutAction.languageSwitch(), Weight.LANGUAGE, titleMode = LayoutKeyTitleMode.AUTOMATIC)

    /** The 123 key of the letters footer. */
    val PAGE: LayoutKey =
        key(
            "123",
            LayoutAction.pageSwitch(LayoutPageTarget.NUMERIC),
            Weight.PAGE,
            titleMode = LayoutKeyTitleMode.AUTOMATIC,
        )

    private fun option(text: String) = LayoutOption(text, LayoutAction.literal(text))

    /** The mark beside the space bar: a literal that long-presses into its sibling marks. */
    fun mark(
        text: String,
        alternates: List<String> = emptyList(),
    ) = key(text, LayoutAction.literal(text), Weight.MARK, alternates.map { option(it) }.ifEmpty { null })

    /** The key that stands in for 中/英 in an editor without it, outside a URL field. */
    fun atMark(): LayoutKey = mark("@", listOf("#", "&", "*")).copy(width = Weight.LANGUAGE)

    /**
     * One footer shape for every row page: `page | 中/英 | mark | 空格 | mark | 回车`. [beside] stands in
     * for 中/英 when the editor has no language switch; only an editor whose letters are unreachable
     * lets the page key take both shares.
     */
    fun row(
        page: LayoutKey,
        pageReachable: Boolean,
        beside: LayoutKey,
        languageSwitch: Boolean,
        right: LayoutKey,
        globe: Boolean,
        spaceLeft: LayoutKey,
    ): List<LayoutKey> =
        buildList {
            // The switch key sits after the page key, as on the system keyboard (in the corner it
            // took the thumb's aim for the page key), and takes its share from the space bar.
            val second =
                if (languageSwitch) {
                    LANGUAGE
                } else {
                    beside
                }
            if (pageReachable) add(page)
            if (globe) add(GLOBE)
            add(if (pageReachable) second else second.copy(width = Weight.PAGE_ALONE))
            add(spaceLeft)
            add(if (globe) SPACE.copy(width = Weight.SPACE - Weight.GLOBE) else SPACE)
            add(right)
            // The mark left of the space bar is balanced by a wider Return, so the space bar stays
            // centred and the marks either side of it are the same size.
            add(ENTER.copy(width = Weight.ENTER + Weight.MARK))
        }

    /**
     * The marks either side of the space bar in a text field: the comma and full stop of [ascii] or
     * of the script language. The full stop has its own key, so each long press holds the marks of
     * its kind: the pauses under the comma, the sentence ends under the full stop.
     */
    fun textMarks(
        language: InputLanguage,
        ascii: Boolean,
    ): Pair<LayoutKey, LayoutKey> {
        if (ascii) return mark(",", listOf("'", ";", ":")) to mark(".", listOf("?", "!", "…"))
        val japanese = language == InputLanguage.JA
        val comma = if (japanese) "、" else "，"
        val pauses = if (japanese) listOf("・", "：", "；") else listOf("、", "：", "；")
        return mark(comma, pauses) to mark("。", listOf("？", "！", "…"))
    }

    /** The letters footer of a text field, which a layout without a bottom row of its own gets. */
    fun letters(language: InputLanguage = InputLanguage.ZH_HANS): List<LayoutKey> {
        val (left, right) = textMarks(language, ascii = false)
        return row(
            page = PAGE,
            pageReachable = true,
            beside = atMark(),
            languageSwitch = true,
            right = right,
            globe = false,
            spaceLeft = left,
        )
    }
}
