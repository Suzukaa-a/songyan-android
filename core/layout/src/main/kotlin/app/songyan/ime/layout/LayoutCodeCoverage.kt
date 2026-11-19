package app.songyan.ime.layout

import app.songyan.ime.keyboard.engine.SchemaKeyProfile
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind

/** Which codes of a scheme a layout can send. */
object LayoutCodeCoverage {
    /**
     * Every code the layout's keys send: rows, split halves, auxiliary areas and long-press options.
     * A merged key also covers the codes grouped on it ([LayoutFold.groups]). A lowercase letter
     * covers its uppercase, as the shift state types it.
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
        val kind = LayoutFold.kindOf(layout)
        if (kind != null) {
            for (code in LayoutFold.groups(layout, kind).flatMap { it.codes }) {
                result += code.toString()
                if (code in 'a'..'z') result += code.uppercase()
            }
        }
        return result
    }

    /**
     * The missing codes worth telling the user about for a keyboard of [kind] drawing [layout]. A
     * 26-key layout reports [missingToReport]; a merged one (14, 18 or 9 keys) only what it covers
     * less than the bundled layout of its kind, since folding schemes are typed on those as they are.
     * Other kinds report nothing.
     */
    fun missingToReport(
        layout: LayoutDefinition,
        kind: KeyboardLayoutKind,
        profile: SchemaKeyProfile,
    ): List<String> =
        when {
            kind == KeyboardLayoutKind.QWERTY26 -> missingToReport(layout, profile)
            kind.compactFold != null -> {
                val bundled = BuiltinLayouts.all.firstOrNull { LayoutFold.kindOf(it) == kind }
                val already = bundled?.let { missingToReport(it, profile) }.orEmpty().toSet()
                missingToReport(layout, profile).filter { it !in already }
            }
            else -> emptyList()
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
     * The missing codes worth telling the user about: the required ones, or every missing code when
     * the profile does not tell required from optional (an older profile), so nothing is hidden.
     */
    fun missingToReport(
        layout: LayoutDefinition,
        profile: SchemaKeyProfile,
    ): List<String> = if (profile.optionalCodes == null) missing(layout, profile) else missingRequired(layout, profile)

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
