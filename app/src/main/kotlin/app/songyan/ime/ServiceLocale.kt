package app.songyan.ime

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.LocaleList

/**
 * The language the keyboard's own resources speak: the user's per-app language when one is set,
 * the system language otherwise. The service is attached once and outlives many changes of either,
 * so this is re-read every time the keyboard shows instead of being fixed at attach time.
 */
internal object ServiceLocale {
    fun wanted(context: Context): LocaleList {
        val app =
            if (Build.VERSION.SDK_INT >= 33) {
                context.getSystemService(android.app.LocaleManager::class.java)?.applicationLocales
            } else {
                null
            }
        return if (app == null || app.isEmpty) Resources.getSystem().configuration.locales else app
    }

    /** Points [resources] at [wanted]; true when that changed anything. */
    @Suppress("DEPRECATION")
    fun apply(
        resources: Resources,
        wanted: LocaleList,
    ): Boolean {
        if (resources.configuration.locales == wanted) return false
        // The service has no activity to recreate: its own Resources are the only place the language lives.
        resources.updateConfiguration(
            Configuration(resources.configuration).apply {
                setLocales(wanted)
            },
            resources.displayMetrics,
        )
        return true
    }
}
