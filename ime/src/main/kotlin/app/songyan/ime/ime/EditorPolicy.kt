package app.songyan.ime.ime

import android.text.InputType
import android.view.inputmethod.EditorInfo
import androidx.emoji2.text.EmojiCompat
import app.songyan.ime.keyboard.session.InputMode

/**
 * Which direct-entry digit pad the editor asks for. [NONE] keeps the normal keyboard pages, so a
 * text editor never loses the letters.
 */
enum class NumericEditorKind {
    NONE,
    NUMBER,
    PHONE,
    DATETIME,
}

/**
 * The extra characters a number editor allows. Read from the editor's own input-type flags, so a
 * field that cannot accept a sign never shows a sign key.
 */
data class NumberInputFlags(
    val signed: Boolean = false,
    val decimal: Boolean = false,
)

/**
 * What a date or time editor accepts besides digits, read from its input-type variation.
 *
 * [date] keeps the date separators (`/`, `.`, `-`) and [time] the time separator (`:`). A field
 * that names no variation accepts both, and so does its pad.
 */
data class DateTimeFlags(
    val date: Boolean = false,
    val time: Boolean = false,
)

/** What a text field is for; the keys beside the space bar and the digit row follow it. */
enum class FieldKind {
    TEXT,
    EMAIL,
    URL,
    PASSWORD,
}

/** Editor restrictions are applied before sending the first key to the engine. */
data class EditorPolicy(
    val numeric: Boolean,
    val ascii: Boolean,
    /**
     * A password field or an editor that passed [EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING]: a
     * truly private editor whose text must never be learned, recorded or predicted.
     */
    val privateInput: Boolean,
    /**
     * The command the Enter key performs: an [EditorInfo.IME_MASK_ACTION] value,
     * [EditorInfo.IME_ACTION_NONE] for a plain Return, or [ACTION_CUSTOM_LABEL] when the editor
     * supplied its own action in [customActionLabel].
     */
    val action: Int,
    /** The direct-entry pad this editor needs; [NumericEditorKind.NONE] draws the normal pages. */
    val numericKind: NumericEditorKind = NumericEditorKind.NONE,
    /**
     * The editor passed [InputType.TYPE_TEXT_FLAG_MULTI_LINE]: Return inserts a line break. TextView
     * also forces [EditorInfo.IME_FLAG_NO_ENTER_ACTION] on such a field, so [action] is a Return for
     * it even when the app requested one.
     */
    val multiline: Boolean = false,
    /** Sign and decimal permission of a number editor; ignored by every other kind. */
    val numberFlags: NumberInputFlags = NumberInputFlags(),
    /** Separators a date or time editor accepts; ignored by every other kind. */
    val dateTimeFlags: DateTimeFlags = DateTimeFlags(),
    /**
     * The editor passed [InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS], as search and filter fields do.
     * It asks for no learning-derived output, not for direct input: such a field still expects the
     * input method to compose, and Chinese composition is how this keyboard types.
     */
    val noSuggestions: Boolean = false,
    /**
     * The editor's own label for the Enter key (`TextView#setImeActionLabel` fills
     * [EditorInfo.actionLabel] and [EditorInfo.actionId]). Non-null only while the Enter key
     * performs that action, so a Return key never borrows a label it cannot run.
     */
    val customActionLabel: String? = null,
    /** The field's purpose; [FieldKind.URL] starts in English but keeps the 中/英 key. */
    val fieldKind: FieldKind = FieldKind.TEXT,
    /**
     * The emoji metadata version the editor's renderer understands (EmojiCompat's
     * `EDITOR_INFO_METAVERSION_KEY`); 0 when it does not say, and every emoji the device draws is offered.
     */
    val emojiMetaVersion: Int = 0,
) {
    /** A URL field starts in English yet may switch to Chinese; every other `ascii` field may not. */
    val startsEnglish: Boolean get() = fieldKind == FieldKind.URL

    /** Marks typed by the symbol pages are half-width unless the user switched them. */
    val asciiMarks: Boolean get() = ascii || startsEnglish

    /**
     * A [EditorInfo.IME_ACTION_NONE] Return must land as a line break in the text, not as a raw
     * Enter key event: a multi-line field is guaranteed its line break, ordered with the commits
     * before it. Every other editor takes the key event, which is what the platform's own
     * `InputMethodService.sendKeyChar` does when there is no action to perform.
     */
    val returnsNewline: Boolean get() = multiline

    /** No learning-derived candidate (post-commit prediction) may reach this editor. */
    val suppressesPrediction: Boolean get() = privateInput || noSuggestions

    /**
     * The engine must stop writing learning data for this editor. Suppressing learning is a
     * privacy/behaviour switch: a no-suggestions editor keeps Chinese composition either way.
     */
    val suppressesLearning: Boolean get() = privateInput || noSuggestions

    /**
     * True when the editor has to fall back to direct (English) input because it is truly private
     * and the engine cannot stop learning while composition continues. Only [privateInput] may lose
     * Chinese input this way; a no-suggestions editor never does.
     */
    fun directInputRequired(learningProtectionEnforced: Boolean): Boolean = privateInput && !learningProtectionEnforced

    /**
     * The mode a session starts this editor in. Derived on every session preparation, so the
     * decision always follows the current editor and the current protection result instead of an
     * earlier editor's fallback.
     */
    fun modeFor(directInput: Boolean): InputMode =
        if (ascii || directInput || startsEnglish) InputMode.ENGLISH else InputMode.CHINESE

    companion object {
        /**
         * [action] value for an editor that labeled the Enter key itself
         * (`TextView#setImeActionLabel`): the front end must call
         * `performEditorAction(EditorInfo.actionId)` instead of a platform action code. Same
         * sentinel as AOSP's reference IME (`InputTypeUtils.IME_ACTION_CUSTOM_LABEL`).
         */
        const val ACTION_CUSTOM_LABEL: Int = EditorInfo.IME_MASK_ACTION + 1

        fun from(info: EditorInfo): EditorPolicy =
            fromInputType(info.inputType, info.imeOptions, info.actionLabel, info.actionId)
                .copy(emojiMetaVersion = info.extras?.getInt(EmojiCompat.EDITOR_INFO_METAVERSION_KEY, 0) ?: 0)

        /**
         * [rawInputType] with a missing class read as text when a text variation is set. Some apps
         * (Google Contacts' name fields: `TYPE_TEXT_VARIATION_PERSON_NAME | TYPE_TEXT_FLAG_CAP_WORDS`
         * with no class) drop the class bits of a real text field; taken literally that is
         * `TYPE_NULL`, which would lock the field to key events and English.
         */
        fun effectiveInputType(rawInputType: Int): Int =
            if (rawInputType and InputType.TYPE_MASK_CLASS == InputType.TYPE_NULL &&
                rawInputType and InputType.TYPE_MASK_VARIATION != 0
            ) {
                rawInputType or InputType.TYPE_CLASS_TEXT
            } else {
                rawInputType
            }

        /**
         * The policy of one editor, from its raw [rawInputType], [imeOptions] and optional custom
         * action label.
         *
         * Pure so the mapping stays unit-tested without an Android editor instance: every input type
         * and flag used here is a compile-time constant.
         */
        fun fromInputType(
            rawInputType: Int,
            imeOptions: Int,
            actionLabel: CharSequence? = null,
            actionId: Int = 0,
        ): EditorPolicy {
            val inputType = effectiveInputType(rawInputType)
            val inputClass = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            val password =
                (
                    inputClass == InputType.TYPE_CLASS_TEXT &&
                        variation in
                        setOf(
                            InputType.TYPE_TEXT_VARIATION_PASSWORD,
                            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                        )
                ) ||
                    (inputClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD)
            val numericKind =
                when (inputClass) {
                    InputType.TYPE_CLASS_NUMBER -> NumericEditorKind.NUMBER
                    InputType.TYPE_CLASS_PHONE -> NumericEditorKind.PHONE
                    InputType.TYPE_CLASS_DATETIME -> NumericEditorKind.DATETIME
                    else -> NumericEditorKind.NONE
                }
            val numberFlags =
                if (numericKind == NumericEditorKind.NUMBER) {
                    NumberInputFlags(
                        signed = inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0,
                        decimal = inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0,
                    )
                } else {
                    NumberInputFlags()
                }
            val dateTimeFlags =
                if (numericKind == NumericEditorKind.DATETIME) {
                    // TYPE_DATETIME_VARIATION_NORMAL names no variation and takes both a date and a
                    // time, so only an explicit variation narrows the separators.
                    when (variation) {
                        InputType.TYPE_DATETIME_VARIATION_DATE -> DateTimeFlags(date = true)
                        InputType.TYPE_DATETIME_VARIATION_TIME -> DateTimeFlags(time = true)
                        else -> DateTimeFlags(date = true, time = true)
                    }
                } else {
                    DateTimeFlags()
                }
            // A TYPE_NULL editor (a terminal) only understands key events and cannot show a
            // composition, so it takes what the keys say.
            val ascii =
                password ||
                    inputClass == InputType.TYPE_NULL ||
                    numericKind != NumericEditorKind.NONE ||
                    (
                        inputClass == InputType.TYPE_CLASS_TEXT &&
                            variation in
                            setOf(
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                            )
                    ) ||
                    imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII != 0
            // A password field and IME_FLAG_NO_PERSONALIZED_LEARNING are the only privacy signals.
            // TYPE_TEXT_FLAG_NO_SUGGESTIONS is a suggestion request, not a privacy signal: search
            // fields set it and must still compose Chinese.
            val privateInput =
                password ||
                    imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0
            val noSuggestions =
                inputClass == InputType.TYPE_CLASS_TEXT &&
                    inputType and InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
            // The Enter key follows the editor exactly as AOSP's reference IME resolves it
            // (LatinIME InputTypeUtils.getImeOptionsActionIdFromEditorInfo): an editor that asked
            // for a plain Return keeps it, an editor-supplied label wins over the action bits, and
            // everything else is the requested action. TextView supplies a label through
            // setImeActionLabel() while its action bits stay UNSPECIFIED (or DONE).
            // TYPE_TEXT_FLAG_IME_MULTI_LINE only asks the fullscreen extract view for multiple
            // lines; the Return key itself still follows the action bits there.
            val multiline =
                inputClass == InputType.TYPE_CLASS_TEXT &&
                    inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
            val customLabel = actionLabel?.toString()?.takeIf { it.isNotBlank() }
            val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
            val customAction = !noEnterAction && customLabel != null && actionId != 0
            val action =
                when {
                    noEnterAction -> EditorInfo.IME_ACTION_NONE
                    customAction -> ACTION_CUSTOM_LABEL
                    else -> imeOptions and EditorInfo.IME_MASK_ACTION
                }
            return EditorPolicy(
                numeric = numericKind != NumericEditorKind.NONE,
                ascii = ascii,
                privateInput = privateInput,
                action = action,
                multiline = multiline,
                numericKind = numericKind,
                numberFlags = numberFlags,
                dateTimeFlags = dateTimeFlags,
                noSuggestions = noSuggestions,
                customActionLabel = if (customAction) customLabel else null,
                fieldKind =
                    when {
                        password -> FieldKind.PASSWORD
                        inputClass != InputType.TYPE_CLASS_TEXT -> FieldKind.TEXT
                        variation == InputType.TYPE_TEXT_VARIATION_URI -> FieldKind.URL
                        variation in
                            setOf(
                                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                            ) -> FieldKind.EMAIL
                        else -> FieldKind.TEXT
                    },
            )
        }
    }
}
