package app.songyan.ime.appearance

import org.junit.Test

/** Skin model: strict decoding, defaults, contrast correction and colour parsing. */
class SkinDefinitionTest {
    @Test
    fun defaultsStrictPathsAndContrast() {
        val skin =
            SkinDefinition.decode(
                """{"formatVersion":1,"id":"my-blue","name":"blue","variants":{"light":{"colors":{"accent":"#145ACC","text":"#FFFFFF"}}},"typography":{"family":"rounded"}}""",
            )
        assertEquals(22.0, skin.typography.keySize, 0.001)
        // A white text colour on a white key is forced to black: the contrast rule is part of the format.
        assertEquals("#000000", skin.resolvedColors(dark = false).getValue("text").toHexString())
        // An omitted candidate size keeps the format default rather than the system default skin's value.
        assertEquals(20.0, skin.typography.candidateSize, 0.001)
        assertEquals(skin.normalized(), SkinDefinition.decode(skin.encode()))
    }

    @Test
    fun unknownFieldsWrongVersionsAndInvalidValuesAreRejected() {
        val rejected =
            listOf(
                """{"formatVersion":2,"id":"x","name":"x"}""",
                """{"formatVersion":1,"id":"../x","name":"x"}""",
                """{"formatVersion":1,"id":"x","name":"x","variants":{"light":{"code":"a"}}}""",
                """{"formatVersion":1,"id":"x","name":"x","spacing":{"visualInsetX":7}}""",
                """{"formatVersion":1,"id":"x","name":"x","typography":{"family":"comic"}}""",
                """{"formatVersion":1,"id":"x","name":"x","keycap":{"shadow":"heavy"}}""",
                """{"formatVersion":1,"id":"x","name":"x","variants":{"light":{"colors":{"accent":"#FFF"}}}}""",
                """{"formatVersion":1,"id":"x","name":"x","variants":{"light":{"colors":{"nope":"#123456"}}}}""",
            )
        for (json in rejected) {
            assertFailsWith<AppearanceError>(json) { SkinDefinition.decode(json) }
        }
    }

    @Test
    fun backgroundAcceptsExactlyOneShapePerType() {
        val solid =
            SkinDefinition.decode(
                """{"formatVersion":1,"id":"x","name":"x","variants":{"light":{"background":{"type":"solid","color":"#123456"}}}}""",
            )
        assertEquals(
            "solid",
            solid.variants.light
                ?.background
                ?.type,
        )

        val gradient =
            SkinDefinition.decode(
                """{"formatVersion":1,"id":"x","name":"x","variants":{"light":{"background":{"type":"linearGradient","from":"#000000","to":"#FFFFFF","direction":"vertical"}}}}""",
            )
        assertEquals(
            "linearGradient",
            gradient.variants.light
                ?.background
                ?.type,
        )

        // A solid background carrying gradient stops, or a gradient without a vertical direction, is invalid.
        for (
        json in
        listOf(
            """{"formatVersion":1,"id":"x","name":"x","variants":{"light":{"background":{"type":"solid","color":"#123456","from":"#000000"}}}}""",
            """{"formatVersion":1,"id":"x","name":"x","variants":{"light":{"background":{"type":"linearGradient","from":"#000000","to":"#FFFFFF","direction":"horizontal"}}}}""",
            """{"formatVersion":1,"id":"x","name":"x","variants":{"light":{"background":{"type":"linearGradient","from":"#000000"}}}}""",
        )
        ) {
            assertFailsWith<AppearanceError>(json) { SkinDefinition.decode(json) }
        }
    }

    @Test
    fun colorNamesAreExactlyTheCrossPlatformSet() {
        // Adding a role here would make Android exports unreadable by iOS.
        assertEquals(
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
            ),
            SkinDefinition.COLOR_NAMES,
        )
    }

    @Test
    fun resolvedColorsFillEveryRoleForBothVariants() {
        for (dark in listOf(false, true)) {
            val colors = SkinDefinition.systemDefault.resolvedColors(dark)
            assertEquals(SkinDefinition.COLOR_NAMES.toSet(), colors.keys)
        }
        // The system default's own colours survive resolution.
        assertEquals(
            "#DFE2E7",
            SkinDefinition.systemDefault
                .resolvedColors(dark = false)
                .getValue("panel")
                .toHexString(),
        )
        assertEquals(
            "#25272C",
            SkinDefinition.systemDefault
                .resolvedColors(dark = true)
                .getValue("panel")
                .toHexString(),
        )
    }

    @Test
    fun systemDefaultIsReadOnlyByItsIdentityOnly() {
        assertEquals("system-default", SkinDefinition.systemDefault.id)
        // The name equals the id so the localized display name can come from resources.
        assertEquals("system-default", SkinDefinition.systemDefault.name)
        assertEquals(18.0, SkinDefinition.systemDefault.typography.candidateSize, 0.001)
        assertEquals("none", SkinDefinition.systemDefault.keycap.shadow)
    }

    @Test
    fun oversizeInputIsRejected() {
        val padding = " ".repeat(SkinDefinition.MAX_BYTES)
        assertFailsWith<AppearanceError> {
            SkinDefinition.decode("""{"formatVersion":1,"id":"x","name":"x","author":"$padding"}""")
        }
    }

    @Test
    fun skinColorParsesAndFormatsRoundHexOnly() {
        val color = SkinColor.fromHex("#145ACC")
        assertNotNull(color)
        assertEquals("#145ACC", color!!.toHexString())
        // Anything but exactly #RRGGBB is rejected, matching the iOS format.
        assertNull(SkinColor.fromHex("#FFF"))
        assertNull(SkinColor.fromHex("145ACC"))
        assertNull(SkinColor.fromHex("#GGGGGG"))
        assertNull(SkinColor.fromHex(""))
        assertEquals(0xFF145ACC.toInt(), SkinColor.fromHex("#145ACC")!!.toArgb())
        assertEquals(0x80145ACC.toInt(), SkinColor.fromHex("#145ACC")!!.toArgb(alpha = 0x80))
    }

    @Test
    fun contrastPicksAReadableForeground() {
        val white = SkinColor.fromHex("#FFFFFF")!!
        val black = SkinColor.fromHex("#000000")!!
        assertEquals(black, black.contrasting(white))
        assertEquals(black, white.contrasting(white), "unreadable white on white falls back to black")
        assertEquals(white, black.contrasting(black), "unreadable black on black falls back to white")
        val readable = SkinColor.fromHex("#252930")!!
        assertEquals(readable, readable.contrasting(white), "an already readable colour is kept")
        assertTrue(black.contrast(white) > 4.5)
        assertTrue(white.contrast(white) < 1.5)
    }
}
