package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.InputLanguage
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/**
 * What the keyboard draws on the keys of a Chinese letters page, resolved from the layout data.
 * The keyboard and the layout editor's preview both call this, so a key shows the same face and
 * corner hint in both and the two cannot drift apart.
 */
object LetterPageKeys {
    val SHIFT: LayoutKey = LayoutKey("⇧", width = 1.4, action = LayoutAction(LayoutActionKind.SHIFT))

    /**
     * The merged pads' Shift slot holds a syllable separator, as on iOS: a pad of two-letter keys
     * has no capitals to shift, and a separator settles which letters of an ambiguous run start the
     * next syllable. It only acts on a composition; the label is the key face's own string.
     */
    val MERGED_SEPARATOR: LayoutKey = LayoutKey("分词", width = 1.4, action = LayoutAction.code("'"))

    val DELETE: LayoutKey = LayoutKey("⌫", width = 1.4, action = LayoutAction(LayoutActionKind.BACKSPACE))

    /** The key the shell adds before the last letter row. */
    fun leadingControl(layout: KeyboardLayoutKind): LayoutKey =
        if (layout == KeyboardLayoutKind.MERGED14 || layout == KeyboardLayoutKind.MERGED18) MERGED_SEPARATOR else SHIFT

    /**
     * The builtin merged layouts declare "合并键" as the corner hint, which is internal jargon; the
     * keyboard never draws it (the iOS preview applies the same rule). A real hint like the "1 !"
     * of the QW key stays. The face is lowercase like the 26-key letters; Shift uppercases it.
     */
    fun mergedFace(key: LayoutKey): LayoutKey {
        val hint = if (key.secondaryLabel == BuiltinLayouts.MERGED_KEY_HINT) null else key.secondaryLabel
        val face = if (key.action.type == LayoutActionKind.CODE) key.label.lowercase() else key.label
        return key.copy(label = face, secondaryLabel = hint)
    }

    /** [mergedFace] then [chineseLetterKey]: the key as the Chinese letters page draws it. */
    fun presented(
        key: LayoutKey,
        main: LayoutDefinition,
        language: InputLanguage,
    ): LayoutKey = chineseLetterKey(mergedFace(key), language, nativeLetterDefaults(main))

    /**
     * Whether the 26-key shell folds its own Shift and Delete into the last letter row. The shipped
     * shapes declare three letter rows, which is the shape the pair was made for. A taller pad that
     * declares Delete itself, in its footer or at the end of its last letter row (the zhuyin pad),
     * edits through that key: the shell would otherwise draw Delete twice and a Shift the pad has no
     * use for. Anything else keeps the old behavior, so custom layouts and the shipped shapes are
     * untouched.
     */
    fun shellEditsLastLetterRow(
        main: LayoutDefinition,
        footer: List<LayoutKey>,
        kanaBoard: Boolean,
    ): Boolean {
        if (kanaBoard) return false
        val bottom = LayoutBottomRow.index(main)
        val letters = main.rows.filterIndexed { index, _ -> index != bottom }
        if (letters.size < 4) return true
        val declaresDelete = (footer + letters.last().keys).any { it.action.type == LayoutActionKind.BACKSPACE }
        return !declaresDelete
    }

    /**
     * A letter key on the Chinese page carries the long-press marks of the input scheme: the
     * bundled rows show the script language's defaults (a: "、" or "，", s: "。" ...) in their
     * corner, while a page the author owns keeps its own literals and hint untouched. A merged
     * pair (14 / 18 keys) gets the marks of the letter it stands for.
     */
    fun chineseLetterKey(
        key: LayoutKey,
        language: InputLanguage,
        nativeDefaults: Boolean,
    ): LayoutKey {
        val label = key.label
        if (key.action.type != LayoutActionKind.CODE || label.isEmpty() || !label.all { it.isLetter() }) return key
        // The layout is the provenance: only untouched bundled rows get the script language's
        // defaults; a page the author owns keeps its literals as written, an empty list included.
        if (!nativeDefaults) return key
        if (label.length > 1) return mergedPairKey(key, language)
        val literals = AlternateKeyPolicy.defaultLetterLiterals(label, language)
        if (language == InputLanguage.ZH_HANS) {
            // Simplified Chinese keeps the faces the bundled rows already describe, completed the
            // way the original pages did it.
            val policy =
                AlternateKeyPolicy.options(
                    AlternateKeyPolicy.Request(
                        keyLabel = label,
                        secondaryLiterals = literals,
                        page = AlternateKeyPolicy.Page.LETTERS,
                        language = AlternateKeyPolicy.Language.CHINESE,
                    ),
                )
            val added =
                policy.mapNotNull { option ->
                    val text = (option.payload as? AlternateKeyPolicy.Payload.Literal)?.value ?: return@mapNotNull null
                    LayoutOption(option.label, LayoutAction.literal(text))
                }
            val options = (key.longPress.orEmpty() + added).distinctBy { it.label }
            return key.copy(
                secondaryLabel = key.secondaryLabel ?: options.firstOrNull()?.label,
                longPress = options.ifEmpty { null },
            )
        }
        // Keep the partner letters and codes a merged key must still send; replace only the marks
        // and the corner hint they own.
        val kept =
            key.longPress.orEmpty().filter { option ->
                option.action.type == LayoutActionKind.CODE ||
                    option.action.value?.let { it.length == 1 && it[0] in 'a'..'z' } == true
            }
        val added =
            literals
                .filter { it != label }
                .distinct()
                .map { LayoutOption(it, LayoutAction.literal(it)) }
        val options = (kept + added).distinctBy { it.label }
        return key.copy(
            secondaryLabel = kept.firstOrNull()?.label ?: options.firstOrNull()?.label,
            longPress = options.ifEmpty { null },
        )
    }

    /** Bundled rows only: a copy or an edited layout is the author's data even when it keeps the defaults. */
    fun nativeLetterDefaults(main: LayoutDefinition): Boolean {
        val builtin = main.input.builtinLayout ?: return false
        // The romaji board keeps the 26-key transport, so looking only the transport up would compare
        // it with the QWERTY board; its own bundled definition is the one an untouched board matches.
        val bundled =
            if (builtin == LayoutInput.Builtin.QWERTY26 && main.id == BuiltinLayouts.romaji26.id) {
                BuiltinLayouts.romaji26
            } else {
                BuiltinLayouts.byId(builtin.wire) ?: return false
            }
        return main.id == bundled.id && main.rows == bundled.rows
    }

    /**
     * A merged pair keeps its partner letter as the first alternate and adds its pair's marks
     * after it. The corner shows the first mark unless the layout's own hint is one of the
     * alternates: a flick types only an alternate the corner shows, so a decorative hint like
     * the QW key's "1 !" gives way to "1".
     */
    private fun mergedPairKey(
        key: LayoutKey,
        language: InputLanguage,
    ): LayoutKey {
        val marks = AlternateKeyPolicy.mergedPairLiterals(key.label, language)
        if (marks.isEmpty()) return key
        val options =
            (key.longPress.orEmpty() + marks.map { LayoutOption(it, LayoutAction.literal(it)) })
                .distinctBy { it.label }
        val hint = key.secondaryLabel?.takeIf { hint -> options.any { it.label == hint } } ?: marks.first()
        return key.copy(secondaryLabel = hint, longPress = options)
    }
}
