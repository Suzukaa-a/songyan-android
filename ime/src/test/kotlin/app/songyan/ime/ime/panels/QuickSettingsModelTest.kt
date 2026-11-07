package app.songyan.ime.ime.panels

import app.songyan.ime.candidate.KeyboardOneHandMode
import app.songyan.ime.ime.ColorMode
import app.songyan.ime.ime.KeyHeight
import app.songyan.ime.keyboard.engine.BuiltinScriptState
import app.songyan.ime.keyboard.engine.EngineOptionKind
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickSettingsModelTest {
    @Test
    fun characterFormsShareOneChoiceTileAndSelectingOneReplacesThePreviousChoice() {
        val names = listOf("s2s", "s2t", "s2hk", "s2tw")
        val labels = listOf("简体", "繁体", "香港", "臺灣")
        val state =
            QuickSettingsState(
                engineOptions =
                    names.mapIndexed { index, name ->
                        EngineOptionTile(name, EngineOptionKind.TRADITIONAL, index == 0, names, labels[index])
                    } + EngineOptionTile("emoji", EngineOptionKind.EMOJI, true),
            )
        val tiles = QuickSettingsModel.tiles(state).filter { it.setting == QuickSetting.ENGINE_OPTION }
        assertEquals(2, tiles.size)
        val forms = tiles.first()
        assertEquals(QuickTile.Kind.CHOICE, forms.kind)
        assertEquals(labels, forms.options.map { it.label })
        assertEquals("s2s", forms.valueKey)
        assertNull(QuickSettingsModel.toggled(forms))
        val selected = state.apply(forms.options.last().change)
        assertEquals(listOf("s2tw", "emoji"), selected.engineOptions.filter { it.on }.map { it.name })
        assertEquals(
            "s2tw",
            QuickSettingsModel.tiles(selected).first { it.setting == QuickSetting.ENGINE_OPTION }.valueKey,
        )
    }

    @Test
    fun characterFormChoicesCarryTheKnownBundledScriptState() {
        val names = listOf("simplification", "zht", "zh_tw", "zh_hk")
        val states =
            listOf(
                BuiltinScriptState.SIMPLIFIED,
                BuiltinScriptState.TRADITIONAL,
                BuiltinScriptState.TAIWAN,
                BuiltinScriptState.HONG_KONG,
            )
        val group =
            QuickSettingsModel
                .tiles(
                    QuickSettingsState(
                        engineOptions =
                            names.mapIndexed { index, name ->
                                EngineOptionTile(
                                    name,
                                    EngineOptionKind.TRADITIONAL,
                                    index == 0,
                                    names,
                                    null,
                                    states[index],
                                )
                            },
                    ),
                ).single { it.setting == QuickSetting.ENGINE_OPTION }
        assertEquals(states, group.options.map { it.knownScriptState })

        // An author's group with the same option names keeps its own labels and carries no known state.
        val custom =
            QuickSettingsModel
                .tiles(
                    QuickSettingsState(
                        engineOptions =
                            names.mapIndexed { index, name ->
                                EngineOptionTile(name, EngineOptionKind.TRADITIONAL, index == 0, names, "原字")
                            },
                    ),
                ).single { it.setting == QuickSetting.ENGINE_OPTION }
        assertTrue(custom.options.all { it.knownScriptState == null })
        assertEquals(listOf("原字", "原字", "原字", "原字"), custom.options.map { it.label })
    }

    private fun tile(
        state: QuickSettingsState,
        setting: QuickSetting,
    ) = QuickSettingsModel.tiles(state).single { it.setting == setting }

    @Test
    fun theGridOffersTheHighFrequencySettingsInOrder() {
        assertEquals(
            listOf(
                QuickSetting.ONE_HAND,
                QuickSetting.KEY_HEIGHT,
                QuickSetting.LAYOUT,
                QuickSetting.APPEARANCE,
                QuickSetting.KEY_SOUND,
                QuickSetting.KEY_HAPTICS,
                QuickSetting.KEY_PREVIEW,
                QuickSetting.PHRASES,
                QuickSetting.MANAGE_TOOLS,
            ),
            QuickSettingsModel.tiles(QuickSettingsState()).map { it.setting },
        )
    }

    @Test
    fun oneHandOffersThreeChoicesWithTheCurrentOneMarked() {
        val one = tile(QuickSettingsState(oneHand = KeyboardOneHandMode.LEFT), QuickSetting.ONE_HAND)
        assertEquals(QuickTile.Kind.CHOICE, one.kind)
        assertEquals(listOf("off", "left", "right"), one.options.map { it.key })
        assertEquals(listOf("left"), one.options.filter { it.selected }.map { it.key })
        assertEquals("left", one.valueKey)
    }

    @Test
    fun keyHeightUsesTheSameValuesAsTheSettingsApp() {
        val height = tile(QuickSettingsState(keyHeight = KeyHeight.TALL), QuickSetting.KEY_HEIGHT)
        assertEquals(listOf("compact", "standard", "tall"), height.options.map { it.key })
        assertEquals("tall", height.valueKey)
        assertEquals(
            QuickSettingChange.Height(KeyHeight.COMPACT),
            height.options.first { it.key == "compact" }.change,
        )
    }

    @Test
    fun appearanceOffersSystemLightAndDark() {
        val appearance = tile(QuickSettingsState(colorMode = ColorMode.DARK), QuickSetting.APPEARANCE)
        assertEquals(listOf("system", "light", "dark"), appearance.options.map { it.key })
        assertEquals("dark", appearance.valueKey)
    }

    @Test
    fun oneHandIsUnavailableWhereTheKeyboardNeverNarrows() {
        assertFalse(tile(QuickSettingsState(oneHandAvailable = false), QuickSetting.ONE_HAND).enabled)
        assertTrue(tile(QuickSettingsState(), QuickSetting.ONE_HAND).enabled)
    }

    @Test
    fun layoutIsAnActionThatNamesTheCurrentLayout() {
        val layout = tile(QuickSettingsState(layout = KeyboardLayoutKind.MERGED14), QuickSetting.LAYOUT)
        assertEquals(QuickTile.Kind.ACTION, layout.kind)
        assertEquals("merged14", layout.valueKey)
    }

    @Test
    fun aTapOnAToggleAsksForTheOppositeValue() {
        val state = QuickSettingsState(soundOn = true, hapticsOn = false, previewOn = true)
        assertEquals(QuickSettingChange.Sound(false), QuickSettingsModel.toggled(tile(state, QuickSetting.KEY_SOUND)))
        assertEquals(
            QuickSettingChange.Haptics(true),
            QuickSettingsModel.toggled(tile(state, QuickSetting.KEY_HAPTICS)),
        )
        assertEquals(
            QuickSettingChange.Preview(false),
            QuickSettingsModel.toggled(tile(state, QuickSetting.KEY_PREVIEW)),
        )
    }

    @Test
    fun theSoundToggleDoesNothingWhileTheSystemSwitchIsOff() {
        val sound = tile(QuickSettingsState(soundOn = false, soundBlockedBySystem = true), QuickSetting.KEY_SOUND)
        assertFalse(sound.enabled)
        assertNull(QuickSettingsModel.toggled(sound))
    }

    @Test
    fun theFlickOnlyChoiceAppearsOnlyForJapaneseKana() {
        assertTrue(
            "an ordinary keyboard offers no kana-only choice",
            QuickSettingsModel.tiles(QuickSettingsState()).none { it.setting == QuickSetting.KANA12_FLICK_ONLY },
        )
        val flick = tile(QuickSettingsState(kana12 = true), QuickSetting.KANA12_FLICK_ONLY)
        assertEquals(QuickTile.Kind.TOGGLE, flick.kind)
        assertFalse(flick.on)
        assertEquals(QuickSettingChange.Kana12FlickOnly(true), QuickSettingsModel.toggled(flick))
        val on =
            QuickSettingsModel.toggled(
                tile(QuickSettingsState(kana12 = true, kana12FlickOnly = true), QuickSetting.KANA12_FLICK_ONLY),
            )
        assertEquals(QuickSettingChange.Kana12FlickOnly(false), on)
        val changed = QuickSettingsState(kana12 = true).apply(QuickSettingChange.Kana12FlickOnly(true))
        assertTrue(tile(changed, QuickSetting.KANA12_FLICK_ONLY).on)
    }

    @Test
    fun onlyTogglesToggle() {
        val state = QuickSettingsState()
        assertNull(QuickSettingsModel.toggled(tile(state, QuickSetting.ONE_HAND)))
        assertNull(QuickSettingsModel.toggled(tile(state, QuickSetting.LAYOUT)))
        assertNull(QuickSettingsModel.toggled(tile(state, QuickSetting.PHRASES)))
    }

    @Test
    fun changesAdvanceTheStateTheTilesAreBuiltFrom() {
        var state = QuickSettingsState()
        state = state.apply(QuickSettingChange.OneHand(KeyboardOneHandMode.RIGHT))
        state = state.apply(QuickSettingChange.Height(KeyHeight.COMPACT))
        state = state.apply(QuickSettingChange.Appearance(ColorMode.LIGHT))
        state = state.apply(QuickSettingChange.Sound(false))
        state = state.apply(QuickSettingChange.Haptics(false))
        state = state.apply(QuickSettingChange.Preview(false))
        assertEquals("right", tile(state, QuickSetting.ONE_HAND).valueKey)
        assertEquals("compact", tile(state, QuickSetting.KEY_HEIGHT).valueKey)
        assertEquals("light", tile(state, QuickSetting.APPEARANCE).valueKey)
        assertFalse(tile(state, QuickSetting.KEY_SOUND).on)
        assertFalse(tile(state, QuickSetting.KEY_HAPTICS).on)
        assertFalse(tile(state, QuickSetting.KEY_PREVIEW).on)
    }

    @Test
    fun aStoredColorModeResolvesAndAnUnknownOneFollowsTheSystem() {
        assertEquals(ColorMode.SYSTEM, ColorMode.resolve(null))
        assertEquals(ColorMode.SYSTEM, ColorMode.resolve("nonsense"))
        assertEquals(ColorMode.DARK, ColorMode.resolve("dark"))
    }

    @Test
    fun theSwitchesOfTheInputMethodFollowThePlainTilesAsToggles() {
        val state =
            QuickSettingsState(
                engineOptions =
                    listOf(
                        EngineOptionTile("traditionalize", EngineOptionKind.TRADITIONAL, on = true),
                        EngineOptionTile("emoji", EngineOptionKind.EMOJI, on = false),
                    ),
            )
        val tiles = QuickSettingsModel.tiles(state)
        assertEquals(QuickSetting.MANAGE_TOOLS, tiles[tiles.size - 3].setting)
        val (first, second) = tiles.takeLast(2)
        assertTrue(first.kind == QuickTile.Kind.TOGGLE && first.on)
        assertFalse(second.on)
        assertEquals(QuickSettingChange.EngineOption("traditionalize", false), QuickSettingsModel.toggled(first))
        assertEquals(QuickSettingChange.EngineOption("emoji", true), QuickSettingsModel.toggled(second))
    }

    @Test
    fun aSwitchChangeAdvancesOnlyThatSwitch() {
        val state =
            QuickSettingsState(
                engineOptions =
                    listOf(
                        EngineOptionTile("a", EngineOptionKind.TRADITIONAL, on = false),
                        EngineOptionTile("b", EngineOptionKind.EMOJI, on = false),
                    ),
            )
        val next = state.apply(QuickSettingChange.EngineOption("b", true))
        assertEquals(listOf(false, true), next.engineOptions.map { it.on })
    }

    @Test
    fun anInputMethodWithoutSwitchesAddsNoTiles() {
        assertEquals(9, QuickSettingsModel.tiles(QuickSettingsState()).size)
    }
}
