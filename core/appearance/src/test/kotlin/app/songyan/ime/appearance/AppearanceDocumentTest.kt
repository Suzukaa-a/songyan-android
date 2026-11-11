package app.songyan.ime.appearance

import app.songyan.ime.candidate.CandidateBarDefinition
import org.junit.Test

/**
 * Appearance project: strict decoding, identity lockstep, duplication, import and the read-only
 * built-in presets.
 */
class AppearanceDocumentTest {
    private fun makeDocument(
        id: String,
        name: String,
        accent: String,
        candidateSize: Double,
        collapsedHeight: Double,
    ): AppearanceDocument {
        val base = SkinDefinition.systemDefault
        val light = (base.variants.light ?: SkinDefinition.Variant()).copy(colors = mapOf("accent" to accent))
        val skin =
            base.copy(
                variants = base.variants.copy(light = light),
                typography = base.typography.copy(candidateSize = candidateSize),
            )
        val bar = CandidateBarDefinitionAlias.systemDefaultWithHeight(collapsedHeight)
        return AppearanceDocument.create(id = id, name = name, skin = skin, candidateBar = bar).normalized()
    }

    @Test
    fun strictDecodeRejectsUnknownFieldsBadVersionAndMissingParts() {
        val document = makeDocument("my-look", "my look", "#145ACC", 22.0, 48.0)
        assertEquals(document, AppearanceDocument.decode(document.encode()))
        val rejected =
            listOf(
                """{"formatVersion":3,"id":"x","name":"x","skin":{},"candidateBar":{}}""",
                """{"formatVersion":1,"id":"x","name":"x","renderer":"evil","skin":{},"candidateBar":{}}""",
                """{"formatVersion":1,"id":"x","name":"x","skin":{}}""",
                """{"formatVersion":1,"id":"x","name":"x","appearance":"sepia","skin":{},"candidateBar":{}}""",
            )
        for (json in rejected) {
            assertFailsWith<AppearanceError>(json) { AppearanceDocument.decode(json) }
        }
    }

    @Test
    fun decodeRejectsCrossGenerationIdentity() {
        val document = makeDocument("look-a", "A", "#145ACC", 22.0, 48.0)

        // A skin whose identity differs from the project is a mixed generation and must be rejected.
        val foreignSkin = document.skin.copy(id = "someone-else")
        assertFailsWith<AppearanceError> {
            AppearanceDocument.decode(
                """{"formatVersion":1,"id":"look-a","name":"A","skin":""" +
                    foreignSkin.encode() +
                    ""","candidateBar":""" + document.candidateBar.encode() + """}""",
            )
        }

        // A candidate-area identity that differs from the project is rejected the same way.
        val foreignBar = document.candidateBar.copy(id = "other-bar")
        assertFailsWith<AppearanceError> {
            AppearanceDocument.decode(
                """{"formatVersion":1,"id":"look-a","name":"A","skin":""" +
                    document.skin.encode() +
                    ""","candidateBar":""" + foreignBar.encode() + """}""",
            )
        }
    }

    @Test
    fun copyGivesOneNewIdentityForAllParts() {
        val document = makeDocument("look-a", "A", "#145ACC", 22.0, 48.0)
        val copy = document.duplicate(nameSuffix = " copy")
        assertTrue(copy.id != document.id)
        assertTrue(copy.identityIsConsistent, "a duplicate must move all three identities to the same new identity")
        assertEquals(copy.id, copy.skin.id)
        assertEquals(copy.id, copy.candidateBar.id)
        assertEquals(document.name + " copy", copy.name)
        assertEquals(document.skin.typography.candidateSize, copy.skin.typography.candidateSize, 0.001)
        assertEquals(document.candidateBar.collapsed, copy.candidateBar.collapsed)
        assertFalse(copy.isSystemDefault)
        assertTrue(AppearanceDocument.systemDefault.isSystemDefault)
    }

    @Test
    fun candidateAreaCarriesLayoutOnlyAndSkinOwnsColorsAndFonts() {
        val document = makeDocument("look-a", "A", "#145ACC", 27.0, 52.0)
        val barJson = document.candidateBar.encode()
        for (forbidden in listOf("color", "accent", "font", "typography", "candidateSize")) {
            assertFalse(barJson.contains(forbidden), "the candidate area must not carry " + forbidden)
        }
        // Changing only the skin leaves every candidate-area field unchanged.
        val reskinned =
            document.replacing(
                skin = document.skin.copy(typography = document.skin.typography.copy(candidateSize = 18.0)),
            )
        assertEquals(document.candidateBar, reskinned.candidateBar)
        assertEquals(18.0, reskinned.skin.typography.candidateSize, 0.001)
        assertTrue(reskinned.identityIsConsistent)

        // Changing only the candidate layout leaves every skin field unchanged.
        val relaid =
            document.replacing(
                candidateBar =
                    document.candidateBar.copy(
                        expanded =
                            document.candidateBar.expanded.copy(
                                mode = CandidateBarDefinitionAlias.GRID,
                                columns = 3,
                            ),
                    ),
            )
        assertEquals(document.skin, relaid.skin)
        assertEquals(3, relaid.candidateBar.expanded.columns)
        assertTrue(relaid.identityIsConsistent)
    }

    @Test
    fun replacingKeepsAllThreeIdentitiesInLockstep() {
        val document = makeDocument("look-a", "A", "#145ACC", 22.0, 48.0)
        val freshSkin = SkinDefinition.systemDefault.copy(id = "foreign", name = "foreign")
        val replaced = document.replacing(skin = freshSkin)
        assertEquals(document.id, replaced.skin.id, "a replaced skin is re-attached to the project identity")
        assertEquals(document.id, replaced.name.let { replaced.skin.id }.let { replaced.id })
        assertTrue(replaced.identityIsConsistent)
    }

    @Test
    fun normalizeTrimsTheNameAndKeepsOneNamePerAppearance() {
        val document =
            AppearanceDocument
                .create(
                    id = "look-a",
                    name = "  padded  ",
                    skin = SkinDefinition.systemDefault,
                    candidateBar = CandidateBarDefinitionAlias.default(),
                ).normalized()
        assertEquals("padded", document.name)
        assertEquals("padded", document.skin.name)
        assertEquals("padded", document.candidateBar.name)
    }

    @Test
    fun importAcceptsAppearanceSkinAndBarFiles() {
        // A new composite file.
        val document = makeDocument("look-a", "A", "#145ACC", 22.0, 48.0)
        assertEquals(document, AppearanceDocument.decodeImported(document.encode()))

        // A standalone skin file with a custom identity is wrapped in the system default candidate area.
        val skin = SkinDefinition.systemDefault.duplicate(nameSuffix = " old")
        val importedSkin = AppearanceDocument.decodeImported(skin.encode())
        assertEquals(skin.id, importedSkin.id)
        assertEquals(skin.typography, importedSkin.skin.typography)
        assertEquals(
            CandidateBarDefinitionAlias.default().collapsed,
            importedSkin.candidateBar.collapsed,
        )
        assertTrue(importedSkin.identityIsConsistent)

        // A built-in default skin is copied to a fresh identity so it does not collide with the read-only default.
        val importedDefault = AppearanceDocument.decodeImported(SkinDefinition.systemDefault.encode())
        assertTrue(importedDefault.id != AppearanceDocument.SYSTEM_DEFAULT_ID)
        assertTrue(importedDefault.identityIsConsistent)

        // A standalone candidate-area file is wrapped in the system default skin.
        val bar = CandidateBarDefinitionAlias.default().duplicate(nameSuffix = " old")
        val importedBar = AppearanceDocument.decodeImported(bar.encode())
        assertEquals(bar.id, importedBar.id)
        assertEquals(SkinDefinition.systemDefault.typography, importedBar.skin.typography)
        assertTrue(importedBar.identityIsConsistent)

        // Unrecognized JSON and snapshot files are rejected explicitly.
        assertFailsWith<AppearanceError> { AppearanceDocument.decodeImported("""{"hello":1}""") }
        assertFailsWith<AppearanceError> { AppearanceDocument.decodeImported("broken") }
        for (
        snapshot in
        listOf(
            """{"selectedSkinID":"x","skin":{"formatVersion":1,"id":"x","name":"x"},"appearance":"system"}""",
            """{"selectedBarID":"x","bar":{"formatVersion":1,"id":"x","name":"x"}}""",
            """{"selectedID":"x","document":{"formatVersion":1,"id":"x","name":"x"}}""",
        )
        ) {
            assertFailsWith<AppearanceError>(snapshot) { AppearanceDocument.decodeImported(snapshot) }
        }
    }

    @Test
    fun systemDefaultIsTheSkinAndCandidateDefaultTogether() {
        val document = AppearanceDocument.systemDefault
        assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, document.id)
        assertEquals(SkinDefinition.systemDefault, document.skin)
        assertEquals(CandidateBarDefinition.androidDefault, document.candidateBar)
        assertTrue(document.identityIsConsistent)
        assertTrue(document.isSystemDefault)
        assertEquals(18.0, document.candidateSize, 0.001)
        document.validate()
    }

    @Test
    fun builtinPresetsAreConsistentAndDistinct() {
        val presets = AppearancePresets.BUILT_IN
        assertEquals(presets.size, presets.map { it.id }.toSet().size)
        for (preset in presets) {
            assertTrue(preset.identityIsConsistent, preset.id)
            assertTrue(AppearancePresets.isBuiltIn(preset), preset.id)
            assertFalse(preset.isSystemDefault)
            // A round trip loses nothing, because export and apply share one format.
            assertEquals(preset.skin, AppearanceDocument.decode(preset.encode()).skin)
        }
        assertTrue(AppearancePresets.isBuiltIn(AppearanceDocument.systemDefault))
    }

    @Test
    fun theSongyanDesignPresetIsTheAndroidDefault() {
        val songyan = AppearancePresets.androidDefault
        assertEquals(AppearancePresets.SONGYAN_ID, songyan.id)
        assertTrue(songyan.identityIsConsistent)
        assertTrue(AppearancePresets.isBuiltIn(songyan))
        // The preset stores only the cross-platform roles derived from the design tokens.
        assertEquals(
            SkinDefinition.COLOR_NAMES.toSet(),
            songyan.skin.resolvedColors(dark = false).keys,
        )
        assertEquals(
            "#2A593F",
            songyan.skin.variants.light
                ?.colors
                ?.get("accent"),
        )
        assertEquals(
            "#8FD7AA",
            songyan.skin.variants.dark
                ?.colors
                ?.get("accent"),
        )
        assertEquals(1.0, songyan.skin.keycap.borderWidth, 0.001)
        // Every built-in appearance carries the numbers the Android keyboard draws.
        for (preset in AppearancePresets.ALL_BUILT_IN) {
            assertEquals(CandidateBarDefinition.androidDefault.collapsed, preset.candidateBar.collapsed, preset.id)
        }
    }
}
