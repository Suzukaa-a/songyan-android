package app.songyan.ime.ime

import android.os.LocaleList
import app.songyan.ime.keyboard.engine.InputLanguage

/**
 * The glyph locales a language asks text runtimes for. An unspecified language forces none, so the
 * system's own locale (and its default fonts) draws the text.
 */
internal fun InputLanguage.localeList(): LocaleList =
    if (this == InputLanguage.UNSPECIFIED) LocaleList.getDefault() else LocaleList.forLanguageTags(languageTag)
