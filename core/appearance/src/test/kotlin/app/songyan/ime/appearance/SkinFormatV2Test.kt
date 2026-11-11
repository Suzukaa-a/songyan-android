package app.songyan.ime.appearance

import org.junit.Test
import java.util.Base64

/** Format version 2 at the skin level: alpha colours, contrast over the system surface, image inspection. */
class SkinFormatV2Test {
    private fun pngHeader(
        width: Int,
        height: Int,
    ): ByteArray {
        fun be(value: Int) = ByteArray(4) { ((value shr (24 - 8 * it)) and 0xFF).toByte() }
        return byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) +
            byteArrayOf(0, 0, 0, 13) + "IHDR".toByteArray() + be(width) + be(height) + byteArrayOf(1, 0, 0, 0, 0)
    }

    private fun url(
        data: ByteArray,
        type: String = "png",
    ): String = "data:image/$type;base64," + Base64.getEncoder().encodeToString(data)

    private fun problem(dataUrl: String): BackgroundImage.Problem? =
        (BackgroundImage.inspect(dataUrl) as? BackgroundImage.Result.Failure)?.problem

    @Test
    fun alphaColourParsingAndOutput() {
        val colour = SkinColor.fromHex("#abcdef80")!!
        assertEquals("#ABCDEF80", colour.toHexString())
        assertEquals(128.0 / 255, colour.alpha, 1e-9)
        assertEquals("#ABCDEF", SkinColor.fromHex("#abcdef")!!.toHexString())
        assertEquals("#ABCDEFFF", SkinColor.fromHex("#ABCDEFFF")!!.toHexString())
        for (bad in listOf("#12345", "#1234567", "#+FFFFF", "#GG0000", "ABCDEF", "#ABCDEF8", "#ABCDEF800")) {
            assertNull(SkinColor.fromHex(bad), bad)
        }
    }

    @Test
    fun textContrastIsMeasuredAgainstTheKeyOverTheSystemSurface() {
        // A 20% black key over the light surface is light grey, so white text is flipped to black.
        val key = SkinColor.fromHex("#00000033")!!
        val white = SkinColor.fromHex("#FFFFFF")!!
        assertEquals("#000000", white.contrasting(key, SkinColor.systemSurface(dark = false)).toHexString())
        // A faint white key over the dark surface stays dark, so white text is kept.
        val faint = SkinColor.fromHex("#FFFFFF1A")!!
        assertEquals("#FFFFFF", white.contrasting(faint, SkinColor.systemSurface(dark = true)).toHexString())

        val skin =
            SkinDefinition(
                id = "alpha",
                name = "alpha",
                variants =
                    SkinDefinition.Variants(
                        light =
                            SkinDefinition.Variant(
                                colors = mapOf("key" to "#00000033", "text" to "#FFFFFF"),
                            ),
                    ),
            )
        assertEquals("#000000", skin.resolvedColors(dark = false).getValue("text").toHexString())
    }

    @Test
    fun translucentTextIsMeasuredAsComposited() {
        val key = SkinColor.fromHex("#FFFFFF")!!
        val faint = SkinColor.fromHex("#00000033")!!
        assertEquals("#000000", faint.contrasting(key).toHexString())
        val strong = SkinColor.fromHex("#000000E6")!!
        assertEquals("#000000E6", strong.contrasting(key).toHexString())
    }

    @Test
    fun imageInspection() {
        // A complete one-bit PNG, 5000 x 1 pixels.
        val wide = "iVBORw0KGgoAAAANSUhEUgAAE4gAAAABAQAAAAAaanklAAAAD0lEQVR4nGNgGAWjgJ4AAAJyAAHq5ifDAAAAAElFTkSuQmCC"
        assertEquals(BackgroundImage.Problem.TOO_MANY_PIXELS, problem("data:image/png;base64,$wide"))
        // A truncated file whose header claims a legal size is unreadable, not accepted.
        assertEquals(BackgroundImage.Problem.UNREADABLE, problem(url(pngHeader(4, 4))))
        assertEquals(
            BackgroundImage.Problem.BAD_MAGIC,
            problem(url(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(8))),
        )
        assertEquals(BackgroundImage.Problem.BAD_BASE64, problem("data:image/png;base64,@@@"))
        assertEquals(BackgroundImage.Problem.MALFORMED_DATA_URL, problem("data:image/gif;base64,AAAA"))
        assertEquals(BackgroundImage.Problem.MALFORMED_DATA_URL, problem("data:image/png;base64,"))
        // WebP needs RIFF....WEBP.
        var riff = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WAVE".toByteArray()
        assertEquals(BackgroundImage.Problem.BAD_MAGIC, problem(url(riff, "webp")))
        riff = "RIFF".toByteArray() + byteArrayOf(0, 0, 0, 0) + "WEBP".toByteArray()
        assertNotEquals(BackgroundImage.Problem.BAD_MAGIC, problem(url(riff, "webp")))
    }

    @Test
    fun decodedSizeLimitIsExactlyOneMebibyte() {
        var bytes = pngHeader(1, 1)
        bytes += ByteArray(BackgroundImage.MAX_BYTES - bytes.size + 1)
        assertEquals(1_048_577, bytes.size)
        assertEquals(BackgroundImage.Problem.TOO_LARGE, problem(url(bytes)))
        bytes = bytes.copyOf(bytes.size - 1)
        // At the limit the size check passes; the bytes are not a decodable image.
        assertEquals(BackgroundImage.Problem.UNREADABLE, problem(url(bytes)))
    }

    @Test
    fun base64MustBeCanonicalAndImagesMustBeComplete() {
        // The wide PNG is complete; stripping its padding or appending garbage changes the spelling.
        val wide = "iVBORw0KGgoAAAANSUhEUgAAE4gAAAABAQAAAAAaanklAAAAD0lEQVR4nGNgGAWjgJ4AAAJyAAHq5ifDAAAAAElFTkSuQmCC"
        assertEquals(BackgroundImage.Problem.TOO_MANY_PIXELS, problem("data:image/png;base64,$wide"))
        assertEquals(BackgroundImage.Problem.BAD_BASE64, problem("data:image/png;base64,${wide.dropLast(2)}"))
        assertEquals(BackgroundImage.Problem.BAD_BASE64, problem("data:image/png;base64,$wide\n"))
        // A JPEG without the end-of-image marker, and a WebP whose RIFF size disagrees, are truncated.
        val jpeg =
            byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xC0.toByte(), 0, 11, 8, 0, 4, 0, 4, 1, 1, 0x11, 0)
        assertEquals(BackgroundImage.Problem.UNREADABLE, problem(url(jpeg, "jpeg")))
        val complete = jpeg + byteArrayOf(0xFF.toByte(), 0xD9.toByte())
        assertTrue(BackgroundImage.inspect(url(complete, "jpeg")) is BackgroundImage.Result.Ok)
        val webp =
            "RIFF".toByteArray() + byteArrayOf(22, 0, 0, 0) + "WEBPVP8L".toByteArray() + byteArrayOf(5, 0, 0, 0, 0x2F) +
                byteArrayOf(3, 0, 0, 0) + ByteArray(5)
        assertTrue(BackgroundImage.inspect(url(webp, "webp")) is BackgroundImage.Result.Ok)
        val wrong = webp.copyOf().also { it[4] = 23 }
        assertEquals(BackgroundImage.Problem.UNREADABLE, problem(url(wrong, "webp")))
    }
}
