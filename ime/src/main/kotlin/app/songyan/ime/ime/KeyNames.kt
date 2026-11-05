package app.songyan.ime.ime

import android.content.Context
import android.content.res.Configuration
import app.songyan.ime.keyboard.engine.InputLanguage
import java.util.Locale

/**
 * Automatic key names follow the scheme’s input language independently of the app UI locale.
 * Fixed Traditional Chinese and Japanese forms use neutral resources so app translations cannot
 * change them; the remaining key names retain their Simplified Chinese forms.
 */
internal class KeyNames(
    base: Context,
) {
    // Only string lookups go through this, so the locale override is the whole configuration.
    private val chinese: Context by lazy { localized(base, Locale.SIMPLIFIED_CHINESE) }

    /** The Simplified Chinese key names, also the fallback for entries the table does not rename. */
    fun chinese(): Context = chinese

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

    fun languageKeySpoken(
        language: InputLanguage,
        symbols: Boolean,
        targetChinese: Boolean,
    ): String {
        val id =
            when (language) {
                InputLanguage.ZH_HANS -> hansLanguageKey(symbols, targetChinese)
                InputLanguage.ZH_HANT ->
                    when {
                        symbols && targetChinese -> R.string.input_language_to_chinese_symbols_hant
                        symbols -> R.string.input_language_to_english_symbols_hant
                        targetChinese -> R.string.input_language_to_chinese_hant
                        else -> R.string.input_language_to_english_hant
                    }
                InputLanguage.JA ->
                    when {
                        symbols && targetChinese -> R.string.input_language_to_chinese_symbols_ja
                        symbols -> R.string.input_language_to_english_symbols_ja
                        targetChinese -> R.string.input_language_to_chinese_ja
                        else -> R.string.input_language_to_english_ja
                    }
            }
        return chinese.getString(id)
    }

    fun symbolPageDrawn(language: InputLanguage): String =
        chinese.getString(
            when (language) {
                InputLanguage.ZH_HANS -> R.string.ime_symbol_key
                InputLanguage.ZH_HANT -> R.string.input_symbol_key_hant
                InputLanguage.JA -> R.string.input_symbol_key_ja
            },
        )

    fun symbolPageSpoken(language: InputLanguage): String =
        chinese.getString(
            when (language) {
                InputLanguage.ZH_HANS -> R.string.ime_open_symbols_page
                InputLanguage.ZH_HANT -> R.string.input_open_symbols_page_hant
                InputLanguage.JA -> R.string.input_open_symbols_page_ja
            },
        )

    fun numbersPageSpoken(language: InputLanguage): String =
        chinese.getString(
            when (language) {
                InputLanguage.ZH_HANS -> R.string.ime_open_numbers_page
                InputLanguage.ZH_HANT -> R.string.input_open_numbers_page_hant
                InputLanguage.JA -> R.string.input_open_numbers_page_ja
            },
        )

    private fun hansLanguageKey(
        symbols: Boolean,
        targetChinese: Boolean,
    ): Int =
        when {
            symbols && targetChinese -> R.string.ime_language_to_chinese_symbols
            symbols -> R.string.ime_language_to_english_symbols
            targetChinese -> R.string.ime_language_to_chinese
            else -> R.string.ime_language_to_english
        }

    private fun hantAction(action: Int): Int =
        when (action) {
            R.string.ime_enter -> R.string.input_enter_hant
            R.string.ime_go -> R.string.input_go_hant
            R.string.ime_search -> R.string.input_search_hant
            R.string.ime_send -> R.string.input_send_hant
            R.string.ime_next -> R.string.input_next_hant
            R.string.ime_done -> R.string.input_done_hant
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
            else -> action
        }

    private fun localized(
        base: Context,
        locale: Locale,
    ): Context = base.createConfigurationContext(Configuration().apply { setLocale(locale) })
}
