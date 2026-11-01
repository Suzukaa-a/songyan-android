package app.songyan.ime.content

import android.content.Context

/**
 * The last quick-insert format the user picked, kept in SharedPreferences so the keyboard can read
 * it synchronously.
 *
 * Construction loads the preferences file, which is disk IO: create the instance on a background
 * thread while the keyboard is being set up and keep it warm afterwards, the way the keyboard
 * keeps every other engine-adjacent value warm.
 */
class SharedPreferencesQuickInsertFormatPreferences(
    context: Context,
) : QuickInsertFormatPreferences {
    private val preferences = context.applicationContext.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE)

    override fun read(): QuickInsertDateFormat? =
        preferences.getString(QuickInsertCore.PREFERENCE_KEY, null)?.let(QuickInsertDateFormat::fromId)

    override fun write(format: QuickInsertDateFormat) {
        preferences.edit().putString(QuickInsertCore.PREFERENCE_KEY, format.id).apply()
    }

    private companion object {
        const val FILE_NAME: String = "quick_insert"
    }
}
