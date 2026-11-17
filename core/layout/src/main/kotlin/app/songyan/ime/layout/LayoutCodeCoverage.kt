package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.SchemaKeyProfile

/** Which codes of a scheme a layout can send. */
object LayoutCodeCoverage {
    /**
     * Every code the layout's keys send: rows, split halves, auxiliary areas and long-press options.
     * A lowercase letter key covers its uppercase, as the shift state types it.
     */
    fun declared(layout: LayoutDefinition): Set<String> {
        val keys =
            layout.rows.flatMap { it.keys } +
                layout.split
                    ?.halves
                    .orEmpty()
                    .flatMap { it.keys } +
                layout.auxiliaryAreas.orEmpty().flatMap { it.keys }
        val result = LinkedHashSet<String>()
        for (key in keys) {
            val actions = listOf(key.action) + key.longPress.orEmpty().map { it.action }
            for (action in actions) {
                val code = action.value?.takeIf { action.type == LayoutActionKind.CODE } ?: continue
                result += code
                if (code.length == 1 && code[0] in 'a'..'z') result += code.uppercase()
            }
        }
        return result
    }

    /** The codes [profile] needs that [layout] cannot send, in the profile's order. */
    fun missing(
        layout: LayoutDefinition,
        profile: SchemaKeyProfile,
    ): List<String> {
        val declared = declared(layout)
        return profile.codes.filter { it !in declared }
    }

    /**
     * The codes [profile] needs to type the scheme that [layout] cannot send: its speller alphabet
     * and initials, without the delimiter and the prefix or suffix codes of reverse lookups, which a
     * layout can go without. Empty when the profile does not tell them apart.
     */
    fun missingRequired(
        layout: LayoutDefinition,
        profile: SchemaKeyProfile,
    ): List<String> {
        val optional = profile.optionalCodes ?: return emptyList()
        return missing(layout, profile).filter { it !in optional }
    }
}
