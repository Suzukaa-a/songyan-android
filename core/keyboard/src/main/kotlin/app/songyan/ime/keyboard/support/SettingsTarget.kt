package app.songyan.ime.keyboard.support

/**
 * Where the settings app opens when the keyboard sends the user there. Opening always lands on
 * the named page, so a previous visit's position never decides what the user sees.
 */
enum class SettingsTarget(
    val id: String,
) {
    HOME("home"),
    QUICK_CONTENT("quick_content"),

    /** The phrases page with the "add phrase" dialog already open. */
    ADD_PHRASE("add_phrase"),
    ;

    companion object {
        /** Intent extra that carries [id]. */
        const val EXTRA = "app.songyan.ime.extra.SETTINGS_TARGET"

        /** An unknown or missing id opens the home page. */
        fun fromId(id: String?): SettingsTarget = entries.firstOrNull { it.id == id } ?: HOME
    }
}
