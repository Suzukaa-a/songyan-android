package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateBarDefinition
import org.junit.Test

/** Palette resolution, the appearance policy decision matrix and the derived Android-only roles. */
class KeyboardPaletteTest {
    @Test
    fun paletteResolvesEveryColorRoleFromTheSkin() {
        val light = KeyboardPalette.of(SkinDefinition.systemDefault, dark = false)
        val dark = KeyboardPalette.of(SkinDefinition.systemDefault, dark = true)
        assertEquals(0xFFDFE2E7.toInt(), light.background)
        assertEquals(0xFFFAFBFC.toInt(), light.keySurface)
        assertEquals(0xFF25272C.toInt(), dark.background)
        assertEquals(0xFF44474E.toInt(), dark.keySurface)
        assertEquals(0xFF252930.toInt(), light.text)
        assertEquals(0xFFF4F5F7.toInt(), dark.text)
        // Aliases match the Canvas palette field names the IME currently uses.
        assertEquals(light.keySurface, light.key)
        assertEquals(light.controlKey, light.control)
        assertEquals(light.mutedText, light.muted)
        assertEquals(light.candidateBarBackground, light.candidateBackground)
    }

    @Test
    fun androidOnlyRolesAreDerivedAndStayReadable() {
        val palette = KeyboardPalette.of(SkinDefinition.systemDefault, dark = false)
        // The candidate bar floats on the panel and its chip uses the skin's selected colour.
        assertEquals(palette.background, palette.candidateBarBackground)
        assertEquals(0xFFCCDBEE.toInt(), palette.candidateSelectedBackground)
        // The chip glyph must read on the chip.
        val chip = SkinColor.fromHex("#CCDBEE")!!
        assertTrue(
            SkinColor.fromHex(String.format("#%06X", palette.candidateSelectedText and 0xFFFFFF))!!.contrast(chip) >=
                4.5,
        )
        // A borderless skin draws no perimeter stroke.
        assertEquals(0, palette.keyBorder)
        assertEquals(0, palette.controlKeyBorder)
        // The popup sits on the keycap colour with the selected role as its selected cell.
        assertEquals(palette.keySurface, palette.popupBackground)
        assertEquals(palette.candidateSelectedBackground, palette.popupSelected)
        assertTrue(palette.popupShadow != 0)
    }

    @Test
    fun aBorderWidthMakesTheBorderStrokeVisible() {
        val bordered = SkinDefinition.systemDefault.copy(keycap = SkinDefinition.Keycap(borderWidth = 1.0))
        val palette = KeyboardPalette.of(bordered, dark = false)
        assertEquals(1.0, palette.keyBorderWidthDp, 0.001)
        assertTrue(palette.keyBorder != 0, "a declared border width draws a perimeter stroke")
        assertEquals(0xFF777777.toInt(), palette.keyBorder, "the border role is used as the stroke colour")
    }

    @Test
    fun skinMetricsReachThePalette() {
        val skin =
            SkinDefinition.systemDefault.copy(
                keycap = SkinDefinition.Keycap(cornerRadius = 9.0, shadow = "subtle"),
                spacing = SkinDefinition.Spacing(visualInsetX = 3.0, visualInsetY = 1.5),
                typography = SkinDefinition.Typography(keySize = 24.0, secondarySize = 12.0, candidateSize = 22.0),
            )
        val palette = KeyboardPalette.of(skin, dark = false)
        assertEquals(9.0, palette.keyRadiusDp, 0.001)
        assertTrue(palette.drawsKeyShadow)
        assertEquals(3.0, palette.visualInsetXDp, 0.001)
        assertEquals(1.5, palette.visualInsetYDp, 0.001)
        assertEquals(24.0, palette.keyTextSizeSp, 0.001)
        assertEquals(22.0, palette.candidateTextSizeSp, 0.001)
        assertEquals(22.0, palette.candidateTypography.candidateSize, 0.001)
        assertEquals(12.0, palette.candidateTypography.secondarySize, 0.001)
        assertFalse(SkinDefinition.systemDefault.keycap.shadow != "none" && false)
    }

    @Test
    fun theDesignDefaultPresetMapsItsTokensOntoThePersistedRoles() {
        val palette = KeyboardPalette.of(AppearancePresets.androidDefault, dark = false)
        // Design tokens: background / keySurface / text / mutedText / accent / accentText.
        assertEquals(0xFFECEFEA.toInt(), palette.background)
        assertEquals(0xFFFFFFFF.toInt(), palette.keySurface)
        assertEquals(0xFF191D1A.toInt(), palette.text)
        assertEquals(0xFF586159.toInt(), palette.mutedText)
        assertEquals(0xFF2A593F.toInt(), palette.accent)
        assertEquals(0xFFFFFFFF.toInt(), palette.accentText)
        assertEquals(0xFFD8E6DB.toInt(), palette.candidateSelectedBackground)
        assertEquals(0xFFD5DDD4.toInt(), palette.divider)

        val dark = KeyboardPalette.of(AppearancePresets.androidDefault, dark = true)
        assertEquals(0xFF121513.toInt(), dark.background)
        assertEquals(0xFF232724.toInt(), dark.keySurface)
        assertEquals(0xFF8FD7AA.toInt(), dark.accent)
        assertEquals(0xFF003820.toInt(), dark.accentText)
    }

    @Test
    fun paletteOfADocumentMatchesThePaletteOfItsSkin() {
        val document = AppearancePresets.androidDefault
        assertEquals(
            KeyboardPalette.of(document.skin, dark = true),
            KeyboardPalette.of(document, dark = true),
        )
    }

    @Test
    fun appearancePolicyFollowsTheHostFieldForTheSystemDefaultSkin() {
        fun dark(
            systemDefault: Boolean,
            skin: SkinAppearance,
            host: HostKeyboardAppearance,
            systemDark: Boolean,
        ) = KeyboardAppearancePolicy.resolvedDark(
            skinIsSystemDefault = systemDefault,
            skinAppearance = skin,
            hostAppearance = host,
            systemDark = systemDark,
        )
        for (systemDark in listOf(false, true)) {
            assertFalse(dark(true, SkinAppearance.SYSTEM, HostKeyboardAppearance.LIGHT, systemDark))
            assertTrue(dark(true, SkinAppearance.SYSTEM, HostKeyboardAppearance.DARK, systemDark))
            assertEquals(
                systemDark,
                dark(true, SkinAppearance.SYSTEM, HostKeyboardAppearance.UNSPECIFIED, systemDark),
            )
        }
        // The field still wins on the system default skin even if the document preference differs.
        assertFalse(dark(true, SkinAppearance.DARK, HostKeyboardAppearance.LIGHT, systemDark = true))
        assertTrue(dark(true, SkinAppearance.LIGHT, HostKeyboardAppearance.DARK, systemDark = false))
    }

    @Test
    fun theResolvedSurfaceWinsOverAStaleHostProxy() {
        assertTrue(
            KeyboardAppearancePolicy.resolvedDark(
                skinIsSystemDefault = true,
                skinAppearance = SkinAppearance.SYSTEM,
                hostAppearance = HostKeyboardAppearance.LIGHT,
                systemDark = true,
                systemTraitKnown = true,
            ),
        )
        assertFalse(
            KeyboardAppearancePolicy.resolvedDark(
                skinIsSystemDefault = true,
                skinAppearance = SkinAppearance.SYSTEM,
                hostAppearance = HostKeyboardAppearance.DARK,
                systemDark = false,
                systemTraitKnown = true,
            ),
        )
    }

    @Test
    fun aCustomSkinKeepsTheUsersChoice() {
        for (host in HostKeyboardAppearance.entries) {
            assertFalse(
                KeyboardAppearancePolicy.resolvedDark(
                    skinIsSystemDefault = false,
                    skinAppearance = SkinAppearance.LIGHT,
                    hostAppearance = host,
                    systemDark = true,
                ),
            )
            assertTrue(
                KeyboardAppearancePolicy.resolvedDark(
                    skinIsSystemDefault = false,
                    skinAppearance = SkinAppearance.DARK,
                    hostAppearance = host,
                    systemDark = false,
                ),
            )
        }
        assertTrue(
            KeyboardAppearancePolicy.resolvedDark(
                skinIsSystemDefault = false,
                skinAppearance = SkinAppearance.SYSTEM,
                hostAppearance = HostKeyboardAppearance.LIGHT,
                systemDark = true,
            ),
        )
        assertFalse(
            KeyboardAppearancePolicy.resolvedDark(
                skinIsSystemDefault = false,
                skinAppearance = SkinAppearance.LIGHT,
                hostAppearance = HostKeyboardAppearance.DARK,
                systemDark = true,
                systemTraitKnown = true,
            ),
        )
    }

    @Test
    fun hostAppearanceMapsFromTheNightModeFlag() {
        assertEquals(HostKeyboardAppearance.UNSPECIFIED, HostKeyboardAppearance.of(null))
        assertEquals(HostKeyboardAppearance.DARK, HostKeyboardAppearance.of(true))
        assertEquals(HostKeyboardAppearance.LIGHT, HostKeyboardAppearance.of(false))
        assertEquals(SkinAppearance.SYSTEM, SkinAppearance.fromWire("system"))
        assertEquals(SkinAppearance.LIGHT, SkinAppearance.fromWire("light"))
        assertEquals(SkinAppearance.DARK, SkinAppearance.fromWire("dark"))
        assertNull(SkinAppearance.fromWire("sepia"))
    }

    @Test
    fun paletteDefaultsFillTheDesignMetricsNoSkinFieldCovers() {
        val palette = KeyboardPalette.of(SkinDefinition.systemDefault, dark = false)
        assertEquals(KeyboardPalette.DEFAULT_KEY_GAP_HORIZONTAL_DP, palette.keyGapHorizontalDp, 0.001)
        assertEquals(KeyboardPalette.DEFAULT_KEY_GAP_VERTICAL_DP, palette.keyGapVerticalDp, 0.001)
        assertEquals(KeyboardPalette.DEFAULT_MIN_TOUCH_TARGET_DP, palette.minTouchTargetDp, 0.001)
        assertEquals(KeyboardPalette.DEFAULT_POPUP_RADIUS_DP, palette.popupRadiusDp, 0.001)
        assertEquals(KeyboardPalette.DEFAULT_PRESS_STATE_LAYER_ALPHA, palette.pressStateLayerAlpha, 0.001)
    }

    @Test
    fun theCandidateBarAcceptsThePaletteTypographyDirectly() {
        val palette = KeyboardPalette.of(AppearancePresets.androidDefault, dark = false)
        // The candidate bar tops its height up for the palette's candidate size.
        val height = CandidateBarDefinition.Collapsed.effectiveHeight(36.0, palette.candidateTextSizeSp)
        assertEquals(36.0, height, 0.001)
        assertEquals(
            CandidateBarDefinition.Collapsed.effectiveHeight(36.0, 30.0),
            CandidateBarDefinition.Collapsed.effectiveHeight(
                36.0,
                palette.candidateTypography.candidateSize.coerceAtLeast(30.0),
            ),
            0.001,
        )
    }
}
