package app.songyan.ime.ime

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Visible key names (空格, 返回, 搜索) are Chinese in every mode and whatever the system language, as on
 * iOS: a Chinese keyboard on an English phone must not print "Space" beside 返回. What a screen reader
 * says stays on the plain context and so follows the system or per-app language.
 */
internal class KeyNames(
    base: Context,
) {
    // Only string lookups go through these, so the locale override is the whole configuration.
    private val chinese: Context by lazy { localized(base, Locale.SIMPLIFIED_CHINESE) }

    fun chinese(): Context = chinese

    private fun localized(
        base: Context,
        locale: Locale,
    ): Context = base.createConfigurationContext(Configuration().apply { setLocale(locale) })
}
