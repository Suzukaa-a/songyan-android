package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateBarDefinition
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which variant of the active skin to render. */
@Serializable
enum class SkinAppearance(
    val wire: String,
) {
    @kotlinx.serialization.SerialName("system")
    SYSTEM("system"),

    @kotlinx.serialization.SerialName("light")
    LIGHT("light"),

    @kotlinx.serialization.SerialName("dark")
    DARK("dark"), ;

    companion object {
        fun fromWire(value: String?): SkinAppearance? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * The unified appearance project (`*.songyanappearance.json`): one user-facing unit is a keyboard
 * skin plus the candidate area's layout behaviour.
 *
 * Identity rule: `document.id == skin.id == candidateBar.id`. A mismatch is a mixed-generation
 * document and is rejected on decode, so "new skin + old candidate bar" cannot exist at runtime.
 *
 * Content boundary: [skin] owns every colour and font (including `typography.candidateSize`);
 * [candidateBar] only describes layout behaviour and carries no colour.
 */
@Serializable
data class AppearanceDocument(
    val formatVersion: Int = FORMAT_VERSION,
    val id: String,
    val name: String,
    val author: String? = null,
    /** Light/dark/follow-system is committed together with the skin and is part of the identity. */
    val appearance: SkinAppearance = SkinAppearance.SYSTEM,
    val skin: SkinDefinition,
    val candidateBar: CandidateBarDefinition,
) {
    val isSystemDefault: Boolean get() = id == SYSTEM_DEFAULT_ID

    /** Whether the three identities agree (skin, candidate bar, project). */
    val identityIsConsistent: Boolean get() = id == skin.id && id == candidateBar.id

    /** Stable identity string for diagnostics; contains no user-visible text. */
    val identityDescription: String get() = id + "|" + skin.id + "|" + candidateBar.id

    /** The candidate size in dp, the one typography value the candidate area needs. */
    val candidateSize: Double get() = skin.typography.candidateSize

    /** Uniform name and materialized colours; the candidate area and skin are re-attached to this identity. */
    fun normalized(): AppearanceDocument {
        val trimmed = name.trim()
        return copy(
            formatVersion = FORMAT_VERSION,
            name = trimmed,
            skin = reidentified(skin, id, trimmed).normalized(),
            candidateBar = reidentified(candidateBar, id, trimmed),
        )
    }

    /** A duplicate with one fresh identity applied to the project, the skin and the candidate area. */
    fun duplicate(nameSuffix: String): AppearanceDocument {
        val newId =
            java.util.UUID
                .randomUUID()
                .toString()
        val base = normalized()
        val newName = base.name + nameSuffix
        return base.copy(
            id = newId,
            name = newName,
            skin = reidentified(base.skin, newId, newName).normalized(),
            candidateBar = reidentified(base.candidateBar, newId, newName),
        )
    }

    /** Replaces the candidate layout; the skin and all three identities stay in lockstep. */
    fun replacing(candidateBar: CandidateBarDefinition): AppearanceDocument =
        copy(candidateBar = reidentified(candidateBar, id, name))

    /** Replaces the skin; the candidate layout and all three identities stay in lockstep. */
    fun replacing(skin: SkinDefinition): AppearanceDocument = copy(skin = reidentified(skin, id, name))

    fun validate() {
        requireField(formatVersion == FORMAT_VERSION, "$.formatVersion")
        requireField(id.isNotEmpty() && id.all { it in ID_CHARS }, "$.id")
        requireField(name.isNotBlank(), "$.name")
        skin.validate()
        // The candidate area validates in its own format; this API only exposes one error type, and
        // the offending path is preserved so the UI can still point at the field.
        try {
            candidateBar.validate()
        } catch (error: app.songyan.ime.candidate.CandidateBarError) {
            throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.candidateBar" + error.path.removePrefix("$"))
        }
        // Composite identity: mixing a skin and a candidate area from different generations is not allowed.
        requireField(id == skin.id, "$.skin.id")
        requireField(id == candidateBar.id, "$.candidateBar.id")
    }

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
        const val FILE_EXTENSION: String = ".songyanappearance.json"

        /** Byte budget of one appearance project. */
        const val MAX_BYTES: Int = 256 * 1024

        private const val ID_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz-_"

        private val TOP_LEVEL_FIELDS =
            setOf("formatVersion", "id", "name", "author", "appearance", "skin", "candidateBar")

        /** Re-attaches a skin to a new identity; only id and name change. */
        fun reidentified(
            skin: SkinDefinition,
            id: String,
            name: String,
        ): SkinDefinition = skin.copy(id = id, name = name)

        /** Re-attaches a candidate area config to a new identity; only id and name change. */
        fun reidentified(
            bar: CandidateBarDefinition,
            id: String,
            name: String,
        ): CandidateBarDefinition = bar.copy(id = id, name = name)

        /** Builds a document, re-attaching both children to the project's identity. */
        fun create(
            id: String,
            name: String,
            appearance: SkinAppearance = SkinAppearance.SYSTEM,
            skin: SkinDefinition = SkinDefinition(id = id, name = name),
            candidateBar: CandidateBarDefinition = CandidateBarDefinition(id = id, name = name),
        ): AppearanceDocument =
            AppearanceDocument(
                id = id,
                name = name,
                appearance = appearance,
                skin = reidentified(skin, id, name),
                candidateBar = reidentified(candidateBar, id, name),
            )

        /** The built-in default: system default skin + system default candidate layout (read-only). */
        val systemDefault: AppearanceDocument =
            create(
                id = SYSTEM_DEFAULT_ID,
                name = SYSTEM_DEFAULT_ID,
                skin = SkinDefinition.systemDefault,
                candidateBar = CandidateBarDefinition.systemDefault,
            )

        /** Strict decode of a stored project; mixed identities and unknown fields are rejected. */
        fun decode(text: String): AppearanceDocument {
            if (text.encodeToByteArray().size > MAX_BYTES) {
                throw AppearanceError(AppearanceErrorCode.OVERSIZE, "$")
            }
            val source = parseObject(text)
            for ((key, _) in source) {
                if (key !in TOP_LEVEL_FIELDS) {
                    throw AppearanceError(AppearanceErrorCode.UNKNOWN_FIELD, "$." + key)
                }
            }
            val version = (source["formatVersion"] as? JsonPrimitive)?.takeIf { !it.isString }?.content
            if (version != FORMAT_VERSION.toString()) {
                throw AppearanceError(AppearanceErrorCode.UNSUPPORTED_VERSION, "$.formatVersion")
            }
            val id = (source["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (id.isNullOrEmpty()) throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.id")
            val name = (source["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (name.isNullOrBlank()) throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.name")

            val skinNode =
                source["skin"] as? JsonObject
                    ?: throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.skin")
            val barNode =
                source["candidateBar"] as? JsonObject
                    ?: throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.candidateBar")

            // Each child keeps its own package's strict decoding (unknown fields, invalid values, versions).
            val skin = SkinDefinition.decode(StrictJsonShared.encoder.encodeToString(JsonObject.serializer(), skinNode))
            val bar =
                try {
                    CandidateBarDefinition.decode(
                        StrictJsonShared.encoder.encodeToString(JsonObject.serializer(), barNode),
                    )
                } catch (error: app.songyan.ime.candidate.CandidateBarError) {
                    throw AppearanceError(
                        AppearanceErrorCode.INVALID_VALUE,
                        "$.candidateBar" + error.path.removePrefix("$"),
                    )
                }

            // A persisted file must not hold a skin or candidate area from another generation.
            if (skin.id != id) throw AppearanceError(AppearanceErrorCode.IDENTITY_MISMATCH, "$.skin.id")
            if (bar.id != id) throw AppearanceError(AppearanceErrorCode.IDENTITY_MISMATCH, "$.candidateBar.id")

            val appearance =
                when (val node = source["appearance"]) {
                    null -> SkinAppearance.SYSTEM
                    is JsonPrimitive ->
                        SkinAppearance.fromWire(node.takeIf { it.isString }?.content)
                            ?: throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.appearance")
                    else -> throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.appearance")
                }
            val author =
                when (val node = source["author"]) {
                    null -> null
                    is JsonPrimitive ->
                        node.takeIf { it.isString }?.content
                            ?: throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.author")
                    else -> throw AppearanceError(AppearanceErrorCode.INVALID_VALUE, "$.author")
                }

            val document =
                AppearanceDocument(
                    id = id,
                    name = name,
                    author = author,
                    appearance = appearance,
                    skin = skin,
                    candidateBar = bar,
                )
            document.validate()
            return document.normalized()
        }

        /**
         * Imports one user-supplied file as an appearance project, accepting three input kinds:
         *  1. an appearance project (this package's format);
         *  2. a standalone skin, wrapped with the system default candidate area;
         *  3. a standalone candidate area, wrapped with the system default skin.
         *
         * A legacy `system-default` file is copied to a fresh identity so it does not collide with
         * the read-only built-in.
         */
        fun decodeImported(text: String): AppearanceDocument {
            if (text.encodeToByteArray().size > MAX_BYTES) {
                throw AppearanceError(AppearanceErrorCode.OVERSIZE, "$")
            }
            val source = parseObject(text)
            // A snapshot (selection plus content) is not an importable configuration.
            val snapshotKeys = setOf("selectedSkinID", "selectedBarID", "selectedID")
            if (snapshotKeys.any { it in source }) {
                throw AppearanceError(AppearanceErrorCode.SNAPSHOT_NOT_IMPORTABLE, "$")
            }
            if ("skin" in source || "candidateBar" in source) return decode(text)

            val skinKeys = setOf("variants", "typography", "keycap", "spacing")
            val barKeys = setOf("compositionRow", "collapsed", "expanded", "controls")
            if (skinKeys.any { it in source }) {
                var skin = SkinDefinition.decode(text)
                if (skin.id == SkinDefinition.SYSTEM_DEFAULT_ID) {
                    skin = skin.duplicate(DEFAULT_COPY_SUFFIX_FOR_IMPORT)
                }
                return decode(
                    create(
                        id = skin.id,
                        name = skin.name,
                        skin = skin,
                        candidateBar = CandidateBarDefinition.systemDefault,
                    ).encode(),
                )
            }
            if (barKeys.any { it in source }) {
                var bar = CandidateBarDefinition.decode(text)
                if (bar.id == CandidateBarDefinition.SYSTEM_DEFAULT_ID) {
                    bar = bar.duplicate(DEFAULT_COPY_SUFFIX_FOR_IMPORT)
                }
                return decode(
                    create(
                        id = bar.id,
                        name = bar.name,
                        skin = SkinDefinition.systemDefault,
                        candidateBar = bar,
                    ).encode(),
                )
            }
            throw AppearanceError(AppearanceErrorCode.UNRECOGNIZED_DOCUMENT, "$")
        }

        /** The suffix appended when an imported file carries the read-only built-in's identity. */
        const val DEFAULT_COPY_SUFFIX_FOR_IMPORT: String = " "

        private fun parseObject(text: String): JsonObject =
            try {
                StrictJsonShared.decoder.parseToJsonElement(text) as? JsonObject
            } catch (error: IllegalArgumentException) {
                throw AppearanceError(AppearanceErrorCode.MALFORMED_JSON, "$")
            } ?: throw AppearanceError(AppearanceErrorCode.NOT_AN_OBJECT, "$")
    }
}
