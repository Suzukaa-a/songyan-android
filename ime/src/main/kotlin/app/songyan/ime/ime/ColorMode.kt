package app.songyan.ime.ime

/** The keyboard's light/dark choice; [wire] is what the owner stores. */
enum class ColorMode(
    val wire: String,
) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    ;

    companion object {
        /** Unknown or missing stored values follow the system. */
        fun resolve(storedValue: String?): ColorMode = entries.firstOrNull { it.wire == storedValue } ?: SYSTEM
    }
}
