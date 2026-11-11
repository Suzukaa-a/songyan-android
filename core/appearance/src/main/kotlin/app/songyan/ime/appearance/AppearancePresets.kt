package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateBarDefinition

/**
 * Built-in appearances besides the system default.
 *
 * Their identities are fixed and they are not deletable; the user duplicates one to get an editable
 * copy. The names stay equal to the ids on purpose: the display names come from string resources.
 */
object AppearancePresets {
    /**
     * The Android visual default: "松烟墨韵" (pine-soot ink on xuan paper).
     *
     * Only the iOS-compatible roles are stored; the keyboard's other surfaces (candidate bar, divider,
     * popup) are derived by [KeyboardPalette], so the file stays readable by iOS.
     */
    const val SONGYAN_ID: String = "builtin-songyan"

    val BUILT_IN: List<AppearanceDocument> =
        listOf(
            create(
                id = SONGYAN_ID,
                light =
                    mapOf(
                        "panel" to "#ECEFEA",
                        "key" to "#FFFFFF",
                        "control" to "#DDE3DA",
                        "accent" to "#2A593F",
                        "accentText" to "#FFFFFF",
                        "text" to "#191D1A",
                        "secondaryText" to "#586159",
                        "selected" to "#D8E6DB",
                        "pressed" to "#D2DDD3",
                        "border" to "#D5DDD4",
                    ),
                dark =
                    mapOf(
                        "panel" to "#121513",
                        "key" to "#232724",
                        "control" to "#1B1E1C",
                        "accent" to "#8FD7AA",
                        "accentText" to "#003820",
                        "text" to "#E2E5E0",
                        "secondaryText" to "#959F96",
                        "selected" to "#1C3426",
                        "pressed" to "#3B433D",
                        "border" to "#272C28",
                    ),
                // Dark-mode key separation relies on the border stroke as well as on contrast.
                borderWidth = 1.0,
            ),
            create(
                id = "builtin-ink",
                light =
                    mapOf(
                        "panel" to "#E5E7EB",
                        "key" to "#FFFFFF",
                        "control" to "#D1D5DB",
                        "accent" to "#2B2D33",
                        "accentText" to "#FFFFFF",
                        "text" to "#111827",
                        "secondaryText" to "#4B5563",
                        "selected" to "#D1D5DB",
                        "pressed" to "#9CA3AF",
                    ),
                dark =
                    mapOf(
                        "panel" to "#141518",
                        "key" to "#2B2D33",
                        "control" to "#1F2126",
                        "accent" to "#3A6EA5",
                        "accentText" to "#FFFFFF",
                        "text" to "#F2F3F5",
                        "secondaryText" to "#A7ADB8",
                        "selected" to "#3A6EA5",
                        "pressed" to "#454952",
                    ),
            ),
            create(
                id = "builtin-bamboo",
                light =
                    mapOf(
                        "panel" to "#DDE8E0",
                        "key" to "#FBFDFB",
                        "control" to "#C3D6C8",
                        "accent" to "#2E7D5B",
                        "accentText" to "#FFFFFF",
                        "text" to "#1E2A23",
                        "secondaryText" to "#4C5E53",
                        "selected" to "#BFDCCB",
                        "pressed" to "#B3CBBB",
                    ),
                dark =
                    mapOf(
                        "panel" to "#1B2520",
                        "key" to "#34443B",
                        "control" to "#27342D",
                        "accent" to "#3F9A71",
                        "accentText" to "#FFFFFF",
                        "text" to "#EEF5F0",
                        "secondaryText" to "#AFC2B6",
                        "selected" to "#2F5F48",
                        "pressed" to "#4A5E52",
                    ),
            ),
            create(
                id = "builtin-paper",
                light =
                    mapOf(
                        "panel" to "#EDE6DA",
                        "key" to "#FFFCF6",
                        "control" to "#DCD1BF",
                        "accent" to "#B5652B",
                        "accentText" to "#FFFFFF",
                        "text" to "#2E261D",
                        "secondaryText" to "#6A5C4B",
                        "selected" to "#EAD3BC",
                        "pressed" to "#D6C9B4",
                    ),
                dark =
                    mapOf(
                        "panel" to "#2A2520",
                        "key" to "#4A423A",
                        "control" to "#3A332C",
                        "accent" to "#C8773A",
                        "accentText" to "#FFFFFF",
                        "text" to "#F6EFE6",
                        "secondaryText" to "#C9BBAA",
                        "selected" to "#6A4A30",
                        "pressed" to "#5C534A",
                    ),
            ),
            create(
                id = "builtin-galaxy",
                light =
                    mapOf(
                        "panel" to "#DCE4EC",
                        "key" to "#FFFFFF",
                        "control" to "#C5D3E3",
                        "accent" to "#1A56DB",
                        "accentText" to "#FFFFFF",
                        "text" to "#0F1E36",
                        "secondaryText" to "#465975",
                        "selected" to "#C7D8ED",
                        "pressed" to "#B5C8DE",
                    ),
                dark =
                    mapOf(
                        "panel" to "#111827",
                        "key" to "#1F2937",
                        "control" to "#182234",
                        "accent" to "#3B82F6",
                        "accentText" to "#FFFFFF",
                        "text" to "#F3F4F6",
                        "secondaryText" to "#8FA3BF",
                        "selected" to "#28436B",
                        "pressed" to "#364761",
                    ),
            ),
            create(
                id = "builtin-twilight",
                light =
                    mapOf(
                        "panel" to "#E8E3EE",
                        "key" to "#FFFFFF",
                        "control" to "#D5CDDE",
                        "accent" to "#7E3AF2",
                        "accentText" to "#FFFFFF",
                        "text" to "#261A35",
                        "secondaryText" to "#635570",
                        "selected" to "#DDD4E8",
                        "pressed" to "#C5BDD0",
                    ),
                dark =
                    mapOf(
                        "panel" to "#1A1625",
                        "key" to "#2D243A",
                        "control" to "#221C2E",
                        "accent" to "#A855F7",
                        "accentText" to "#FFFFFF",
                        "text" to "#F3EEF9",
                        "secondaryText" to "#AFA3BF",
                        "selected" to "#4E3A63",
                        "pressed" to "#493F59",
                    ),
            ),
        )

    /** Identities that cannot be deleted because they ship with the app. */
    val BUILT_IN_IDS: Set<String> = BUILT_IN.map { it.id }.toSet()

    /** Every built-in appearance (system default plus the presets), for a picker list. */
    val ALL_BUILT_IN: List<AppearanceDocument> = listOf(AppearanceDocument.systemDefault) + BUILT_IN

    /** Whether [document] is shipped with the app and therefore read-only. */
    fun isBuiltIn(document: AppearanceDocument): Boolean = document.isSystemDefault || document.id in BUILT_IN_IDS

    /** The localized display name of a built-in id, or null for a user document. */
    fun builtInNameResID(id: String): String? =
        if (id == AppearanceDocument.SYSTEM_DEFAULT_ID ||
            id in BUILT_IN_IDS
        ) {
            id
        } else {
            null
        }

    /** The Android-default appearance: the design-token palette, for P5 to select as the initial value. */
    val androidDefault: AppearanceDocument get() = BUILT_IN.first { it.id == SONGYAN_ID }

    private fun create(
        id: String,
        light: Map<String, String>,
        dark: Map<String, String>,
        borderWidth: Double = 0.0,
    ): AppearanceDocument {
        val skin =
            SkinDefinition.systemDefault.copy(
                id = id,
                name = id,
                keycap = SkinDefinition.systemDefault.keycap.copy(borderWidth = borderWidth),
                variants =
                    SkinDefinition.Variants(
                        light = SkinDefinition.Variant(colors = light),
                        dark = SkinDefinition.Variant(colors = dark),
                    ),
            )
        return AppearanceDocument
            .create(
                id = id,
                name = id,
                skin = skin,
                candidateBar = CandidateBarDefinition.androidDefault,
            ).normalized()
    }
}
