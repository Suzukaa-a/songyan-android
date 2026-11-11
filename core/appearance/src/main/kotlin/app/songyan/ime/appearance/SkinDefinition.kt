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
     * Background drawing of one variant: a solid colour, a gradient or an image. The validation rule
     * keeps exactly one shape populated, so the renderer never has to guess. [colors], [image] and
     * [align] are format version 2.
     */
    @Serializable
    data class Background(
        val type: String = "solid",
        val color: String? = null,
        val from: String? = null,
        val to: String? = null,
        val direction: String? = null,
        val colors: List<String>? = null,
        val image: String? = null,
        val align: String? = null,
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

    /** Path of the first format version 2 feature in use, or null when the skin is plain version 1. */
    val firstVersion2FeaturePath: String?
        get() {
            for ((mode, variant) in listOf("light" to variants.light, "dark" to variants.dark)) {
                for (name in COLOR_NAMES) {
                    if (variant?.colors?.get(name)?.length == 9) return "$.variants.$mode.colors.$name"
                }
                val background = variant?.background ?: continue
                val base = "$.variants.$mode.background"
                if (background.type == "image" || background.image != null) return "$base.type"
                if (background.colors != null) return "$base.colors"
                val flat = listOf("color" to background.color, "from" to background.from, "to" to background.to)
                for ((key, value) in flat) {
                    if (value?.length == 9) return "$base.$key"
                }
            }
            return null
        }

    /** Whether a variant draws an image background (raises the file size limit). */
    val usesImage: Boolean
        get() =
            listOf(variants.light, variants.dark).any {
                it?.background?.image != null || it?.background?.type == "image"
            }

    /**
     * Checks the whole skin. [verifyImage] false skips decoding the image payload (readers of an
     * already verified snapshot); the data-URL shape is still checked.
     */
    fun validate(verifyImage: Boolean = true) {
        requireField(formatVersion in SUPPORTED_VERSIONS, "$.formatVersion")
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
            validateBackground(background, "$.variants." + mode + ".background", verifyImage)
        }
    }

    private fun validateBackground(
        b: Background,
        base: String,
        verifyImage: Boolean,
    ) {
        fun valid(hex: String?): Boolean = hex?.let { SkinColor.fromHex(it) != null } == true
        when (b.type) {
            "solid" -> {
                requireField(valid(b.color), "$base.color")
                requireField(
                    b.from == null &&
                        b.to == null &&
                        b.direction == null &&
                        b.colors == null &&
                        b.image == null &&
                        b.align == null,
                    base,
                )
            }
            "linearGradient" -> {
                requireField(b.color == null && b.image == null && b.align == null, base)
                val stops = b.colors
                if (stops != null) {
                    requireField(b.from == null && b.to == null, base)
                    requireField(stops.size in 2..4, "$base.colors")
                    stops.forEachIndexed { index, stop -> requireField(valid(stop), "$base.colors[$index]") }
                    requireField(b.direction in GRADIENT_DIRECTIONS, "$base.direction")
                } else {
                    requireField(valid(b.from) && valid(b.to), base)
                    requireField(b.direction == "vertical", "$base.direction")
                }
            }
            "image" -> {
                requireField(
                    b.color == null && b.from == null && b.to == null && b.direction == null && b.colors == null,
                    base,
                )
                requireField(b.align in ALIGNMENTS, "$base.align")
                requireField(
                    b.image != null && BackgroundImage.inspect(b.image, verifyImage) is BackgroundImage.Result.Ok,
                    "$base.image",
                )
            }
            else -> requireField(false, "$base.type")
        }
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
        // Translucent keys sit on the system surface, so contrast is judged against the composite.
        val surface = SkinColor.systemSurface(dark)
        colors.getValue("text").let { colors["text"] = it.contrasting(colors.getValue("key"), surface) }
        colors.getValue("secondaryText").let {
            colors["secondaryText"] = it.contrasting(colors.getValue("key"), surface)
        }
        colors.getValue("accentText").let { colors["accentText"] = it.contrasting(colors.getValue("accent"), surface) }
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
        // The version follows the features in use, so a file without any stays byte-identical version 1.
        return result.copy(formatVersion = if (result.firstVersion2FeaturePath != null) 2 else 1)
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
        if (text.encodeToByteArray().size > (if (usesImage) MAX_BYTES_WITH_IMAGE else MAX_BYTES)) {
            throw AppearanceError(AppearanceErrorCode.OVERSIZE, "$")
        }
        return text
    }

    companion object {
        /** The version a skin without any version 2 feature is written as. */
        const val FORMAT_VERSION: Int = 1
        val SUPPORTED_VERSIONS: IntRange = 1..2
        const val SYSTEM_DEFAULT_ID: String = "system-default"

        /** Byte budget of one skin document. */
        const val MAX_BYTES: Int = 128 * 1024

        /** Byte budget of a skin with an image background. */
        const val MAX_BYTES_WITH_IMAGE: Int = 2_097_152

        private val GRADIENT_DIRECTIONS = setOf("vertical", "horizontal", "diagonal")
        private val ALIGNMENTS = setOf("top", "center", "bottom")

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
                                        "key" to "#FFFFFF",
                                        "control" to "#CBD0D8",
                                        "accent" to "#44598F",
                                        "accentText" to "#FFFFFF",
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
                                        "accent" to "#4C65A6",
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
                path.endsWith(".background") ->
                    setOf("type", "color", "from", "to", "direction", "colors", "image", "align")
                else -> emptySet()
            }

        /**
         * Strict decode: unknown fields, wrong versions and invalid values are rejected; omitted
         * fields keep the format's documented defaults.
         */
        fun decode(
            text: String,
            verifyImage: Boolean = true,
        ): SkinDefinition {
            val size = text.encodeToByteArray().size
            // Above the plain limit only a file that can hold an image is parsed; the content is checked below.
            if (size > MAX_BYTES_WITH_IMAGE || (size > MAX_BYTES && !text.contains("\"image\""))) {
                throw AppearanceError(AppearanceErrorCode.OVERSIZE, "$")
            }
            val source =
                try {
                    StrictJsonShared.decoder.parseToJsonElement(text) as? JsonObject
                } catch (error: IllegalArgumentException) {
                    throw AppearanceError(AppearanceErrorCode.MALFORMED_JSON, "$")
                } ?: throw AppearanceError(AppearanceErrorCode.NOT_AN_OBJECT, "$")
            val skin = decode(source, verifyImage)
            if (size > MAX_BYTES && !skin.usesImage) throw AppearanceError(AppearanceErrorCode.OVERSIZE, "$")
            return skin
        }

        /** Decodes an already parsed object; the caller owns the file size limit. */
        fun decode(
            source: JsonObject,
            verifyImage: Boolean = true,
        ): SkinDefinition {
            StrictJsonShared.rejectUnknownFields(source, "$", ::allowedNestedFields) { path ->
                throw AppearanceError(AppearanceErrorCode.UNKNOWN_FIELD, path)
            }
            val declared =
                (source["formatVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.content?.toIntOrNull()
            if (declared == null || declared !in SUPPORTED_VERSIONS) {
                throw AppearanceError(AppearanceErrorCode.UNSUPPORTED_VERSION, "$.formatVersion")
            }

            val merged = mergeIntoDefaults(source, defaultWireForm())
            val decoded =
                try {
                    StrictJsonShared.decoder.decodeFromJsonElement(serializer(), merged)
                } catch (error: IllegalArgumentException) {
                    throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$")
                }
            // A version 1 file must stay within version 1 features.
            if (declared == 1) {
                decoded.firstVersion2FeaturePath?.let { throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, it) }
            }
            decoded.validate(verifyImage)
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
