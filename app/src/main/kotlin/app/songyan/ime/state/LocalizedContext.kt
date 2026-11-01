package app.songyan.ime.state

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList

/**
 * [context] in the app's own language (Android 13+ per-app language). The application context keeps
 * the system language, so text built from it for the settings screens would come out in the wrong
 * language when the two differ.
 */
internal fun localizedContext(context: Context): Context {
    if (Build.VERSION.SDK_INT < 33) return context
    val manager = context.getSystemService(LocaleManager::class.java) ?: return context
    val locales: LocaleList = manager.applicationLocales
    if (locales.isEmpty) return context
    val configuration = Configuration(context.resources.configuration)
    configuration.setLocales(locales)
    return context.createConfigurationContext(configuration)
}
