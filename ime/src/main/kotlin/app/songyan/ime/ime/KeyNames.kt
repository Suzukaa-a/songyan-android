package app.songyan.ime.ime

import android.content.Context
import android.content.res.Configuration
import app.songyan.ime.keyboard.engine.InputLanguage
import java.util.Locale

/**
 * Drawn key names follow the scheme’s input language independently of the app UI locale: fixed
 * Traditional Chinese and Japanese forms use neutral resources so app translations cannot change
 * them, and the remaining drawn names keep their Simplified Chinese forms. What a screen reader says
 * is not here: it stays on the plain context and so follows the system or per-app language.
 */
internal class KeyNames(
    base: Context,
) {
    // Only string lookups go through this, so the locale override is the whole configuration.
    private val chinese: Context by lazy { localized(base, Locale.SIMPLIFIED_CHINESE) }

    /** The Simplified Chinese key names, also the fallback for entries the table does not rename. */
    fun chinese(): Context = chinese

    /** Whether [space] names the key differently while a composition is open. */
    fun spaceFollowsComposition(language: InputLanguage): Boolean = language != InputLanguage.ZH_HANS

    fun space(
        language: InputLanguage,
        composing: Boolean,
    ): String =
        chinese.getString(
            when (language) {
                InputLanguage.ZH_HANS -> R.string.ime_space
                InputLanguage.ZH_HANT ->
                    if (composing) R.string.input_space_composing_hant else R.string.input_space_hant
                InputLanguage.JA ->
                    if (composing) R.string.input_space_composing_ja else R.string.input_space_ja
            },
        )

    /** The automatic Enter face for one editor action (a [R.string.ime_enter] family resource). */
    fun enterAction(
        language: InputLanguage,
        action: Int,
    ): String =
        chinese.getString(
            when (language) {
                InputLanguage.ZH_HANS -> action
                InputLanguage.ZH_HANT -> hantAction(action)
                InputLanguage.JA -> japaneseAction(action)
            },
        )

    /** The Enter face while a composition is open; null keeps the language's ordinary action face. */
    fun enterComposing(language: InputLanguage): String? =
        when (language) {
            InputLanguage.JA -> chinese.getString(R.string.input_enter_composing_ja)
            else -> null
        }

    /** The drawn language key; null keeps the layout's own 中/英 face. */
    fun languageKeyDrawn(language: InputLanguage): String? =
        when (language) {
            InputLanguage.JA -> chinese.getString(R.string.input_language_key_ja)
            else -> null
        }

    fun symbolPageDrawn(language: InputLanguage): String =
        chinese.getString(
            when (language) {
                InputLanguage.ZH_HANS -> R.string.ime_symbol_key
                InputLanguage.ZH_HANT -> R.string.input_symbol_key_hant
                InputLanguage.JA -> R.string.input_symbol_key_ja
            },
        )

    /** The drawn return-to-letters face: Chinese 返回, Japanese 戻る; an English page names ABC. */
    fun returnToLettersDrawn(
        language: InputLanguage,
        english: Boolean = false,
    ): String =
        chinese.getString(
            when {
                english -> R.string.ime_back
                language == InputLanguage.JA -> R.string.input_back_ja
                else -> R.string.ime_back_chinese
            },
        )

    /** The drawn symbol-panel face (更多): the Chinese 更多, Japanese その他. */
    fun symbolPanelDrawn(language: InputLanguage): String =
        chinese.getString(
            when (language) {
                InputLanguage.ZH_HANS, InputLanguage.ZH_HANT -> R.string.input_more_chinese
                InputLanguage.JA -> R.string.input_more_ja
            },
        )

    private fun hantAction(action: Int): Int =
        when (action) {
            R.string.ime_enter -> R.string.input_enter_hant
            R.string.ime_go -> R.string.input_go_hant
            R.string.ime_search -> R.string.input_search_hant
            R.string.ime_send -> R.string.input_send_hant
            R.string.ime_next -> R.string.input_next_hant
            R.string.ime_done -> R.string.input_done_hant
            R.string.ime_previous -> R.string.input_previous_hant
            else -> action
        }

    private fun japaneseAction(action: Int): Int =
        when (action) {
            R.string.ime_enter -> R.string.input_enter_ja
            R.string.ime_go -> R.string.input_go_ja
            R.string.ime_search -> R.string.input_search_ja
            R.string.ime_send -> R.string.input_send_ja
            R.string.ime_next -> R.string.input_next_ja
            R.string.ime_done -> R.string.input_done_ja
            R.string.ime_previous -> R.string.input_previous_ja
            else -> action
        }

    /**
     * TalkBack names Enter in the system language: the drawn face is mapped back to its action.
     * A face the table does not know (an editor's own label, Japanese 確定) is read as drawn.
     */
    fun spokenEnter(
        spoken: Context,
        drawn: String,
    ): String {
        for (action in ENTER_ACTIONS) {
            for (language in InputLanguage.entries) {
                if (enterAction(language, action) == drawn) return spoken.getString(action)
            }
        }
        return drawn
    }

    companion object {
        private val ENTER_ACTIONS =
            intArrayOf(
                R.string.ime_enter,
                R.string.ime_go,
                R.string.ime_search,
                R.string.ime_send,
                R.string.ime_next,
                R.string.ime_done,
                R.string.ime_previous,
            )

        /** The spoken language key: it names the current side and the side a tap switches to. */
        fun languageKeySpoken(
            language: InputLanguage,
            symbols: Boolean,
            targetChinese: Boolean,
        ): Int =
            if (language == InputLanguage.JA) {
                when {
                    symbols && targetChinese -> R.string.ime_language_to_japanese_symbols
                    symbols -> R.string.ime_language_japanese_to_english_symbols
                    targetChinese -> R.string.ime_language_to_japanese
                    else -> R.string.ime_language_japanese_to_english
                }
            } else {
                when {
                    symbols && targetChinese -> R.string.ime_language_to_chinese_symbols
                    symbols -> R.string.ime_language_to_english_symbols
                    targetChinese -> R.string.ime_language_to_chinese
                    else -> R.string.ime_language_to_english
                }
            }
    }

    private fun localized(
        base: Context,
        locale: Locale,
    ): Context = base.createConfigurationContext(Configuration().apply { setLocale(locale) })
}
