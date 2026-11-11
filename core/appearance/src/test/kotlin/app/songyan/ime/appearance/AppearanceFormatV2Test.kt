package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateBarDefinition
import app.songyan.ime.candidate.CandidateBarError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Format version 2: alpha colours, multi-stop gradients and image backgrounds. */
class AppearanceFormatV2Test {
    @get:Rule
    val temporary = TemporaryFolder()

    private fun fixtureText(name: String): String =
        AppearanceFormatV2Test::class.java
            .getResource("/ios-interop/$name")!!
            .readText()

    private fun fixture(name: String): String = fixtureText("$name${AppearanceDocument.FILE_EXTENSION}")

    private fun obj(name: String): JsonObject = Json.parseToJsonElement(fixture(name)).jsonObject

    /** The JSON tree with every number as a double (iOS writes `14`, Android `14.0`). */
    private fun canonical(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.mapValues { canonical(it.value) })
            is JsonArray -> JsonArray(element.map(::canonical))
            is JsonPrimitive ->
                if (!element.isString && element.content.toDoubleOrNull() != null) {
                    JsonPrimitive(element.content.toDouble())
                } else {
                    element
                }
        }

    private fun tree(text: String): JsonElement = canonical(Json.parseToJsonElement(text))

    /** Replaces (or with [JsonNull] removes) the value at [path]. */
    private fun JsonObject.with(
        path: List<String>,
        value: JsonElement,
    ): JsonObject =
        buildJsonObject {
            for ((key, existing) in this@with) {
                when {
                    key != path.first() -> put(key, existing)
                    path.size == 1 -> if (value !is JsonNull) put(key, value)
                    else -> put(key, existing.jsonObject.with(path.drop(1), value))
                }
            }
            if (path.size == 1 && path.first() !in this@with && value !is JsonNull) put(path.first(), value)
        }

    private val lightBackground = listOf("skin", "variants", "light", "background")

    private fun imageDocument(edit: (JsonObject) -> JsonObject): String {
        val document = obj("v2-image")
        val background = lightBackgroundOf(document)
        return document.with(lightBackground, edit(background)).toString()
    }

    private fun lightBackgroundOf(document: JsonObject): JsonObject =
        document["skin"]!!
            .jsonObject["variants"]!!
            .jsonObject["light"]!!
            .jsonObject["background"]!!
            .jsonObject

    private fun JsonObject.set(
        key: String,
        value: JsonElement,
    ): JsonObject = with(listOf(key), value)

    private fun JsonObject.set(
        key: String,
        value: String,
    ): JsonObject = set(key, JsonPrimitive(value))

    private fun JsonObject.remove(key: String): JsonObject = with(listOf(key), JsonNull)

    /** The offending path of a rejected decode, or null when the text decodes. */
    private fun rejection(text: String): String? =
        try {
            AppearanceDocument.decode(text)
            null
        } catch (error: AppearanceError) {
            error.path
        }

    // Round trips

    @Test
    fun version1FixturesStayVersion1AndRoundTrip() {
        for (name in listOf("v1-builtin-songyan", "v1-builtin-paper", "v1-gradient")) {
            val text = fixture(name)
            val document = AppearanceDocument.decode(text)
            assertEquals(1, document.formatVersion, name)
            assertEquals(tree(text), tree(document.encode()), name)
        }
    }

    @Test
    fun version2FixturesRoundTrip() {
        for (name in listOf("v2-alpha", "v2-gradient", "v2-image")) {
            val text = fixture(name)
            val document = AppearanceDocument.decode(text)
            assertEquals(2, document.formatVersion, name)
            assertEquals(2, document.skin.formatVersion, name)
            assertEquals(2, document.candidateBar.formatVersion, name)
            assertEquals(document, AppearanceDocument.decode(document.encode()), name)
            assertEquals(tree(text), tree(document.encode()), name)
        }
    }

    @Test
    fun alphaColoursKeepUpperCaseAndSyntax() {
        val alpha = AppearanceDocument.decode(fixture("v2-alpha"))
        assertEquals(
            "#FFFFFF99",
            alpha.skin.variants.light!!
                .colors!!["key"],
        )
        assertEquals(
            "#0000004D",
            alpha.skin.variants.dark!!
                .colors!!["control"],
        )
        val edited =
            obj("v2-alpha").with(
                listOf("skin", "variants"),
                Json.parseToJsonElement("""{"light":{"colors":{"key":"#abcdef80"}}}"""),
            )
        val decoded = AppearanceDocument.decode(edited.toString())
        assertEquals(
            "#ABCDEF80",
            decoded.skin.variants.light!!
                .colors!!["key"],
        )
    }

    @Test
    fun versionFollowsFeaturesInUse() {
        var skin = SkinDefinition.systemDefault
        assertEquals(1, AppearanceDocument.create(id = "plain", name = "Plain", skin = skin).normalized().formatVersion)

        fun withKey(hex: String): SkinDefinition {
            val light = skin.variants.light!!
            return skin.copy(
                variants = skin.variants.copy(light = light.copy(colors = light.colors!! + ("key" to hex))),
            )
        }
        val upgraded =
            AppearanceDocument.create(id = "plain", name = "Plain", skin = withKey("#FFFFFF80")).normalized()
        assertEquals(2, upgraded.formatVersion)
        assertEquals(2, upgraded.skin.formatVersion)
        assertEquals(2, upgraded.candidateBar.formatVersion)
        skin = withKey("#FFFFFF")
        assertEquals(1, AppearanceDocument.create(id = "plain", name = "Plain", skin = skin).normalized().formatVersion)
    }

    @Test
    fun version1GradientShapeStaysValidInVersion2() {
        val document =
            obj("v2-image").with(
                listOf("skin", "variants"),
                Json.parseToJsonElement(
                    """{"light":{"background":{"type":"linearGradient","from":"#102030","to":"#A0B0C0","direction":"vertical"},"colors":{"key":"#FFFFFF80"}}}""",
                ),
            )
        assertNull(rejection(document.toString()))
    }

    // Rejections with precise paths

    @Test
    fun version2FeaturesAreRejectedInVersion1Files() {
        val document =
            obj("v2-alpha")
                .set("formatVersion", JsonPrimitive(1))
                .with(listOf("skin", "formatVersion"), JsonPrimitive(1))
                .with(listOf("candidateBar", "formatVersion"), JsonPrimitive(1))
        assertEquals("$.variants.light.colors.key", rejection(document.toString()))

        // A version 1 document cannot hold a version 2 part.
        val mixed = document.with(listOf("skin", "formatVersion"), JsonPrimitive(2))
        assertEquals("$.skin.formatVersion", rejection(mixed.toString()))

        // Image and multi-stop gradients, in a version 1 skin.
        for (name in listOf("v2-image", "v2-gradient")) {
            val file =
                obj(name)
                    .set("formatVersion", JsonPrimitive(1))
                    .with(listOf("skin", "formatVersion"), JsonPrimitive(1))
                    .with(listOf("candidateBar", "formatVersion"), JsonPrimitive(1))
            assertNotNull(rejection(file.toString()), name)
        }
    }

    @Test
    fun imageBackgroundRejections() {
        assertNull(rejection(fixture("v2-image")))
        val png = lightBackgroundOf(obj("v2-image"))["image"]!!.let { (it as JsonPrimitive).content }
        val payload = png.removePrefix("data:image/png;base64,")
        val wide = fixtureText("wide-5000px.png.b64").trim()
        val jpegMagic =
            java.util.Base64
                .getEncoder()
                .encodeToString(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(8))
        val cases: List<Triple<String, (JsonObject) -> JsonObject, String>> =
            listOf(
                Triple("bad magic", { it.set("image", "data:image/png;base64,$jpegMagic") }, "background.image"),
                Triple("type mismatch", { it.set("image", "data:image/jpeg;base64,$payload") }, "background.image"),
                Triple("not base64", { it.set("image", "data:image/png;base64,!!!") }, "background.image"),
                Triple("5000 px wide", { it.set("image", "data:image/png;base64,$wide") }, "background.image"),
                Triple("not a data url", { it.set("image", "https://example.com/a.png") }, "background.image"),
                Triple("gif", { it.set("image", "data:image/gif;base64,$payload") }, "background.image"),
                Triple("missing image", { it.remove("image") }, "background.image"),
                Triple("bad align", { it.set("align", "left") }, "background.align"),
                Triple("missing align", { it.remove("align") }, "background.align"),
                Triple("stray colour", { it.set("color", "#FFFFFF") }, "background"),
                Triple("unknown field", { it.set("fit", "cover") }, "background.fit"),
            )
        for ((name, edit, path) in cases) {
            val rejected = rejection(imageDocument(edit))
            assertNotNull(rejected, name)
            assertTrue(rejected!!.endsWith(path), "$name: $rejected")
        }
    }

    @Test
    fun gradientRejections() {
        assertNull(rejection(fixture("v2-gradient")))

        fun gradient(background: String): String =
            obj("v2-gradient")
                .with(
                    listOf("skin", "variants"),
                    Json.parseToJsonElement("""{"light":{"background":$background}}"""),
                ).toString()
        val five = """["#000000","#111111","#222222","#333333","#444444"]"""
        val cases =
            listOf(
                Triple(
                    "5 colours",
                    """{"type":"linearGradient","colors":$five,"direction":"vertical"}""",
                    "background.colors",
                ),
                Triple(
                    "1 colour",
                    """{"type":"linearGradient","colors":["#000000"],"direction":"vertical"}""",
                    "background.colors",
                ),
                Triple(
                    "bad stop",
                    """{"type":"linearGradient","colors":["#000000","red"],"direction":"vertical"}""",
                    "background.colors[1]",
                ),
                Triple(
                    "bad direction",
                    """{"type":"linearGradient","colors":["#000000","#FFFFFF"],"direction":"radial"}""",
                    "background.direction",
                ),
                Triple(
                    "mixed forms",
                    """{"type":"linearGradient","colors":["#000000","#FFFFFF"],"from":"#000000","to":"#FFFFFF","direction":"vertical"}""",
                    "background",
                ),
                Triple(
                    "v1 form is vertical only",
                    """{"type":"linearGradient","from":"#000000","to":"#FFFFFF","direction":"horizontal"}""",
                    "background.direction",
                ),
                Triple("unknown type", """{"type":"radial"}""", "background.type"),
            )
        for ((name, background, path) in cases) {
            val rejected = rejection(gradient(background))
            assertNotNull(rejected, name)
            assertTrue(rejected!!.endsWith(path), "$name: $rejected")
        }
    }

    @Test
    fun unsupportedVersionsAreRejected() {
        for (version in listOf(0, 3)) {
            val document = obj("v2-alpha").set("formatVersion", JsonPrimitive(version))
            assertNotNull(rejection(document.toString()), "$version")
        }
    }

    // Size limits

    /** Trailing whitespace is valid JSON: it pads a file to a given size without changing content. */
    private fun padded(
        text: String,
        size: Int,
    ): String = text + " ".repeat(size - text.encodeToByteArray().size)

    @Test
    fun documentSizeLimits() {
        val alpha = fixture("v2-alpha")
        assertNull(rejection(padded(alpha, 256 * 1024)))
        assertNotNull(rejection(padded(alpha, 256 * 1024 + 1)))
        val image = fixture("v2-image")
        assertNull(rejection(padded(image, 256 * 1024 + 1)))
        assertNull(rejection(padded(image, 2_097_152)))
        assertNotNull(rejection(padded(image, 2_097_152 + 1)))
        // The candidate bar part keeps its own limit.
        assertFailsWith<CandidateBarError> {
            CandidateBarDefinition.decode(padded("""{"formatVersion":2,"id":"x","name":"x"}""", 129 * 1024))
        }
    }

    @Test
    fun importedImageFileAboveVersion1LimitIsAccepted() {
        val padded = padded(fixture("v2-image"), 300 * 1024 + fixture("v2-image").length)
        assertEquals(2, AppearanceDocument.decodeImported(padded).formatVersion)
    }

    // Store

    @Test
    fun storeSavesListsAppliesAndReadsAnImageDocument() {
        val directory = temporary.newFolder("appearance").toPath()
        val store = AppearanceStore(directory.toString())
        val original = fixture("v2-image")
        val document = AppearanceDocument.decode(original)
        store.save(document)
        assertEquals(document, store.list().last())
        // The stored file is the export: the same document.
        val stored = File(directory.toFile(), "v2-image" + AppearanceDocument.FILE_EXTENSION).readText()
        assertEquals(tree(original), tree(stored))
        store.apply(document)
        assertEquals(document, store.readSnapshot().document)
        assertFalse(File(directory.toFile(), AppearanceStore.ACTIVE_FILE_NAME).readText().contains("\\/"))
    }

    @Test
    fun snapshotReadSkipsImagePayloadButKeepsShapeChecks() {
        val document = AppearanceDocument.decode(fixture("v2-image"))
        val light = document.skin.variants.light!!
        val corrupt =
            document.copy(
                skin =
                    document.skin.copy(
                        variants =
                            document.skin.variants.copy(
                                light =
                                    light.copy(
                                        background = light.background!!.copy(image = "data:image/png;base64,AAAA"),
                                    ),
                            ),
                    ),
            )
        assertFailsWith<AppearanceError> { AppearanceDocument.decode(corrupt.encode()) }
        assertNotNull(AppearanceDocument.decode(corrupt.encode(verifyImage = false), verifyImage = false))
        val shapeless =
            corrupt.copy(
                skin =
                    corrupt.skin.copy(
                        variants =
                            corrupt.skin.variants.copy(
                                light = light.copy(background = light.background!!.copy(image = "nothing")),
                            ),
                    ),
            )
        assertFailsWith<AppearanceError> {
            AppearanceDocument.decode(shapeless.encode(verifyImage = false), verifyImage = false)
        }
    }
}
