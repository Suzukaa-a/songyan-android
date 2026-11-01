package app.songyan.ime.appearance

/** The host text field's requested keyboard appearance, reduced to a UIKit/Android-free value. */
enum class HostKeyboardAppearance(
    val wire: String,
) {
    /** The field does not request an appearance. */
    UNSPECIFIED("unspecified"),
    LIGHT("light"),
    DARK("dark"), ;

    companion object {
        /**
         * Android mapping: `EditorInfo` carries no keyboard-appearance flag, so callers pass the
         * host view's night-mode state (`Configuration.UI_MODE_NIGHT_YES`) or [UNSPECIFIED].
         */
        fun of(systemDark: Boolean?): HostKeyboardAppearance =
            when (systemDark) {
                null -> UNSPECIFIED
                true -> DARK
                false -> LIGHT
            }
    }
}

/**
 * Decides which variant (light/dark) of the active skin to render.
 *
 * Rules (single source of truth):
 *  - The **system default** skin follows the actual keyboard surface, including a
 *    field-specific light/dark surface. A window proxy can lag behind a live theme change, so it
 *    is only a fallback before the surface trait is known.
 *  - A **custom** skin keeps the user's own [SkinAppearance]; the host field never repaints a
 *    user-authored skin.
 */
object KeyboardAppearancePolicy {
    fun resolvedDark(
        skinIsSystemDefault: Boolean,
        skinAppearance: SkinAppearance,
        hostAppearance: HostKeyboardAppearance,
        systemDark: Boolean,
        systemTraitKnown: Boolean = false,
    ): Boolean {
        if (skinIsSystemDefault) {
            if (systemTraitKnown) return systemDark
            return when (hostAppearance) {
                HostKeyboardAppearance.LIGHT -> false
                HostKeyboardAppearance.DARK -> true
                HostKeyboardAppearance.UNSPECIFIED -> systemDark
            }
        }
        return when (skinAppearance) {
            SkinAppearance.LIGHT -> false
            SkinAppearance.DARK -> true
            SkinAppearance.SYSTEM -> systemDark
        }
    }
}
