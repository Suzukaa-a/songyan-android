package app.songyan.ime.appearance

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A keyboard skin (`*.songyanskin.json`), wire-compatible with the iOS `SkinDefinition`.
 *
 * The skin owns every colour and font of the keyboard; the candidate area config only describes
 * layout. Built-in entries carry their id as [name]; the UI resolves their display names from string
 * resources, so no user-facing copy lives in this module.
 */
@Serializable
data class SkinDefinition(
    val formatVersion: Int = FORMAT_VERSION,
    val id: String,
    val name: String,
    val author: String? = null,
    val variants: Variants = Variants(),
    val typography: Typography = Typography(),
    val keycap: Keycap = Keycap(),
    val spacing: Spacing = Spacing(),
) {
    @Serializable
    data class Variants(
        val light: Variant? = null,
        val dark: Variant? = null,
    )

    @Serializable
    data class Variant(
        val colors: Map<String, String>? = null,
        val background: Background? = null,
    )

    /**
     * Background drawing of one variant: a solid colour or a vertical two-stop gradient. The
     * validation rule keeps exactly one shape populated, so the renderer never has to guess.
     */
    @Serializable
    data class Background(
        val type: String = "solid",
        val color: String? = null,
        val from: String? = null,
        val to: String? = null,
        val direction: String? = null,
    )

    @Serializable
    data class Typography(
        val family: String = "system",
        val weight: String = "medium",
        val keySize: Double = 22.0,
        val secondarySize: Double = 11.0,
        val candidateSize: Double = 20.0,
    )

    @Serializable
    data class Keycap(
        val cornerRadius: Double = 6.0,
        val borderWidth: Double = 0.0,
        val shadow: String = "subtle",
    )

    @Serializable
    data class Spacing(
        val visualInsetX: Double = 2.0,
        val visualInsetY: Double = 2.0,
    )

    fun validate() {
        requireField(formatVersion == FORMAT_VERSION, "$.formatVersion")
        requireField(id.isNotEmpty() && id.all { it in ID_CHARS }, "$.id")
        requireField(name.isNotBlank(), "$.name")
        requireField(typography.family in FAMILIES, "$.typography.family")
        requireField(typography.weight in WEIGHTS, "$.typography.weight")
        requireField(typography.keySize.isFinite() && typography.keySize in 16.0..28.0, "$.typography.keySize")
        requireField(
            typography.secondarySize.isFinite() && typography.secondarySize in 10.0..16.0,
            "$.typography.secondarySize",
        )
        requireField(
            typography.candidateSize.isFinite() && typography.candidateSize in 16.0..30.0,
            "$.typography.candidateSize",
        )
        requireField(keycap.cornerRadius.isFinite() && keycap.cornerRadius in 0.0..16.0, "$.keycap.cornerRadius")
        requireField(keycap.borderWidth.isFinite() && keycap.borderWidth in 0.0..2.0, "$.keycap.borderWidth")
        requireField(keycap.shadow in SHADOWS, "$.keycap.shadow")
        requireField(spacing.visualInsetX.isFinite() && spacing.visualInsetX in 0.0..6.0, "$.spacing.visualInsetX")
        requireField(spacing.visualInsetY.isFinite() && spacing.visualInsetY in 0.0..6.0, "$.spacing.visualInsetY")

        for ((mode, variant) in listOf("light" to variants.light, "dark" to variants.dark)) {
            for ((key, value) in variant?.colors ?: emptyMap()) {
                requireField(key in COLOR_NAMES, "$.variants." + mode + ".colors." + key)
                requireField(SkinColor.fromHex(value) != null, "$.variants." + mode + ".colors." + key)
            }
            val background = variant?.background ?: continue
            requireField(backgroundIsValid(background), "$.variants." + mode + ".background")
        }
    }

    private fun backgroundIsValid(background: SkinDefinition.Background): Boolean =
        when (background.type) {
            "solid" ->
                background.color?.let { SkinColor.fromHex(it) != null } == true &&
                    background.from == null &&
                    background.to == null &&
                    background.direction == null
            "linearGradient" ->
                background.from?.let { SkinColor.fromHex(it) != null } == true &&
                    background.to?.let { SkinColor.fromHex(it) != null } == true &&
                    background.direction == "vertical" &&
                    background.color == null
            else -> false
        }

    /**
     * Fills every colour role from the platform defaults, overlaid by the skin's own colours, and
     * forces the text roles to stay readable on their backgrounds.
     */
    fun resolvedColors(dark: Boolean): Map<String, SkinColor> {
        val defaults = if (dark) DARK_DEFAULTS else LIGHT_DEFAULTS
        val colors = defaults.toMutableMap()
        for ((key, value) in (if (dark) variants.dark else variants.light)?.colors ?: emptyMap()) {
            SkinColor.fromHex(value)?.let { colors[key] = it }
        }
        colors.getValue("text").let { colors["text"] = it.contrasting(colors.getValue("key")) }
        colors.getValue("secondaryText").let { colors["secondaryText"] = it.contrasting(colors.getValue("key")) }
        colors.getValue("accentText").let { colors["accentText"] = it.contrasting(colors.getValue("accent")) }
        return colors
    }

    /** Materializes the resolved colours into both variants so an export needs no defaults on import. */
    fun normalized(): SkinDefinition {
        var result = this
        for (dark in listOf(false, true)) {
            val variant = (if (dark) variants.dark else variants.light) ?: Variant()
            val resolved = variant.copy(colors = resolvedColors(dark = dark).mapValues { it.value.toHexString() })
            result =
                if (dark) {
                    result.copy(variants = result.variants.copy(dark = resolved))
                } else {
                    result.copy(variants = result.variants.copy(light = resolved))
                }
        }
        return result
    }

    /** A duplicate with a fresh identity and the caller's localized copy suffix. */
    fun duplicate(nameSuffix: String): SkinDefinition =
        copy(
            id =
                java.util.UUID
                    .randomUUID()
                    .toString(),
            name = name + nameSuffix,
        )

    fun encode(): String {
        validate()
        val text = StrictJsonShared.encoder.encodeToString(serializer(), normalized())
        if (text.encodeToByteArray().size > MAX_BYTES) {
            throw AppearanceError(AppearanceErrorCode.OVERSIZE, "$")
        }
        return text
    }

    companion object {
        const val FORMAT_VERSION: Int = 1
        const val SYSTEM_DEFAULT_ID: String = "system-default"

        /** Byte budget of one skin document. */
        const val MAX_BYTES: Int = 128 * 1024

        /**
         * The colour roles a skin file can carry. Kept identical to the iOS set: adding a role here
         * would make Android exports unreadable by iOS, so Android-only roles stay derived (see
         * [KeyboardPalette]) rather than persisted.
         */
        val COLOR_NAMES: List<String> =
            listOf(
                "panel",
                "key",
                "control",
                "accent",
                "text",
                "secondaryText",
                "accentText",
                "pressed",
                "selected",
                "border",
            )

        private val FAMILIES = setOf("system", "rounded", "serif", "monospaced")
        private val WEIGHTS = setOf("regular", "medium", "semibold")
        private val SHADOWS = setOf("none", "subtle")
        private const val ID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"

        private val LIGHT_DEFAULTS: Map<String, SkinColor> =
            listOf(
                "#D1D3D9",
                "#FFFFFF",
                "#ADB2BC",
                "#145ACC",
                "#000000",
                "#333333",
                "#FFFFFF",
                "#C2C5CC",
                "#ADB2BC",
                "#777777",
            ).mapIndexed { index, hex -> COLOR_NAMES[index] to SkinColor.fromHex(hex)!! }
                .toMap()

        private val DARK_DEFAULTS: Map<String, SkinColor> =
            listOf(
                "#202124",
                "#38393C",
                "#55565A",
                "#286CD8",
                "#FFFFFF",
                "#FFFFFF",
                "#FFFFFF",
                "#65666A",
                "#55565A",
                "#808080",
            ).mapIndexed { index, hex -> COLOR_NAMES[index] to SkinColor.fromHex(hex)!! }
                .toMap()

        private val TOP_LEVEL_FIELDS =
            setOf("formatVersion", "id", "name", "author", "variants", "typography", "keycap", "spacing")

        /**
         * The built-in system default skin. Its [name] stays the id because the localized display
         * name comes from string resources.
         */
        val systemDefault: SkinDefinition =
            SkinDefinition(
                id = SYSTEM_DEFAULT_ID,
                name = SYSTEM_DEFAULT_ID,
                // The numbers the Android keyboard draws: 1 pt is 1 dp (1 sp for text), the visible gap
                // between two keys is twice the inset (5 dp across, 6.5 dp between rows).
                typography =
                    Typography(
                        family = "system",
                        weight = "regular",
                        keySize = 22.0,
                        secondarySize = 11.0,
                        candidateSize = 18.0,
                    ),
                keycap = Keycap(cornerRadius = 8.0, shadow = "none"),
                spacing = Spacing(visualInsetX = 2.5, visualInsetY = 3.25),
                variants =
                    Variants(
                        light =
                            Variant(
                                colors =
                                    mapOf(
                                        "panel" to "#DFE2E7",
                                        "key" to "#FAFBFC",
                                        "control" to "#CBD0D8",
                                        "accent" to "#CCDBEE",
                                        "accentText" to "#284D7A",
                                        "text" to "#252930",
                                        "secondaryText" to "#505863",
                                        "selected" to "#CCDBEE",
                                        "pressed" to "#BEC8D6",
                                    ),
                            ),
                        dark =
                            Variant(
                                colors =
                                    mapOf(
                                        "panel" to "#25272C",
                                        "key" to "#44474E",
                                        "control" to "#363940",
                                        "accent" to "#3B536F",
                                        "accentText" to "#F0F5FC",
                                        "text" to "#F4F5F7",
                                        "secondaryText" to "#BDC3CD",
                                        "selected" to "#3B536F",
                                        "pressed" to "#5A626F",
                                    ),
                            ),
                    ),
            )

        private fun allowedNestedFields(path: String): Set<String> =
            when {
                path == "$" -> TOP_LEVEL_FIELDS
                path == "$.variants" -> setOf("light", "dark")
                path == "$.variants.light" || path == "$.variants.dark" -> setOf("colors", "background")
                path == "$.typography" -> setOf("family", "weight", "keySize", "secondarySize", "candidateSize")
                path == "$.keycap" -> setOf("cornerRadius", "borderWidth", "shadow")
                path == "$.spacing" -> setOf("visualInsetX", "visualInsetY")
                path.endsWith(".colors") -> COLOR_NAMES.toSet()
                path.endsWith(".background") -> setOf("type", "color", "from", "to", "direction")
                else -> emptySet()
            }

        /**
         * Strict decode: unknown fields, wrong versions and invalid values are rejected; omitted
         * fields keep the format's documented defaults.
         */
        fun decode(text: String): SkinDefinition {
            if (text.encodeToByteArray().size > MAX_BYTES) {
                throw AppearanceError(AppearanceErrorCode.OVERSIZE, "$")
            }
            val source =
                try {
                    StrictJsonShared.decoder.parseToJsonElement(text) as? JsonObject
                } catch (error: IllegalArgumentException) {
                    throw AppearanceError(AppearanceErrorCode.MALFORMED_JSON, "$")
                } ?: throw AppearanceError(AppearanceErrorCode.NOT_AN_OBJECT, "$")

            StrictJsonShared.rejectUnknownFields(source, "$", ::allowedNestedFields) { path ->
                throw AppearanceError(AppearanceErrorCode.UNKNOWN_FIELD, path)
            }
            val version = (source["formatVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            if (version != FORMAT_VERSION.toString()) {
                throw AppearanceError(AppearanceErrorCode.UNSUPPORTED_VERSION, "$.formatVersion")
            }

            val merged = mergeIntoDefaults(source, defaultWireForm())
            val decoded =
                try {
                    StrictJsonShared.decoder.decodeFromJsonElement(serializer(), merged)
                } catch (error: IllegalArgumentException) {
                    throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$")
                }
            decoded.validate()
            return decoded.normalized()
        }

        private fun defaultWireForm(): JsonObject {
            val full =
                StrictJsonShared.encoder
                    .encodeToJsonElement(
                        serializer(),
                        SkinDefinition(id = "", name = ""),
                    ).jsonObject
            return buildJsonObject {
                for ((key, value) in full) {
                    if (key == "id" || key == "name") continue
                    put(key, value)
                }
            }
        }

        private fun mergeIntoDefaults(
            input: JsonObject,
            base: JsonObject,
        ): JsonObject =
            buildJsonObject {
                for ((key, value) in base) put(key, input[key] ?: value)
                for ((key, value) in input) {
                    val baseValue = base[key]
                    if (value is JsonObject && baseValue is JsonObject) {
                        put(key, mergeIntoDefaults(value, baseValue))
                    } else {
                        put(key, value)
                    }
                }
            }
    }
}
