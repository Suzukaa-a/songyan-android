package app.songyan.ime.appearance

import org.junit.Assume
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** Persistence: atomic apply/read, size limits, deletion boundaries and the degraded read path. */
class AppearanceStoreTest {
    private fun temporaryDirectory(tag: String): Path = Files.createTempDirectory("appearance-" + tag + "-")

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
        return AppearanceDocument
            .create(
                id = id,
                name = name,
                skin = skin,
                candidateBar = CandidateBarDefinitionAlias.systemDefaultWithHeight(collapsedHeight),
            ).normalized()
    }

    @Test
    fun atomicApplyKeepsSkinAndBarInOneGeneration() {
        val directory = temporaryDirectory("atomic")
        val host = AppearanceStore(directory.toString())
        val reader = AppearanceStore(directory.toString())
        val first = makeDocument("look-a", "A", "#145ACC", 22.0, 44.0)
        val second = makeDocument("look-b", "B", "#CC3311", 26.0, 52.0)

        // Saving is not applying.
        host.save(first)
        assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, reader.readSnapshot().selectedID)
        host.apply(first)
        assertEquals(first.id, reader.readSnapshot().selectedID)
        assertFailsWith<AppearanceError> { host.save(first) }
        host.save(second, replacing = true)
        host.apply(second)

        val read = reader.read()
        assertEquals(AppearanceSnapshotSource.APPLIED, read.source)
        assertNull(read.error)
        assertTrue(read.identityIsConsistent)
        // One read yields B's skin together with B's candidate area.
        assertEquals(second.skin, read.snapshot.document.skin)
        assertEquals(second.candidateBar, read.snapshot.document.candidateBar)
        assertEquals(52.0, read.snapshot.document.candidateBar.collapsed.height, 0.001)

        // A corrupt snapshot reads as the built-in default with a clear error.
        Files.writeString(directory.resolve(AppearanceStore.ACTIVE_FILE_NAME), "broken")
        val degraded = reader.read()
        assertEquals(AppearanceSnapshotSource.SYSTEM_DEFAULT, degraded.source)
        assertNotNull(degraded.error)
        assertTrue(degraded.identityIsConsistent)
        assertFailsWith<AppearanceError> { reader.readSnapshot() }
    }

    @Test
    fun snapshotSizeLimitIsEnforced() {
        val directory = temporaryDirectory("bounded")
        val appearance = AppearanceStore(directory.toString())
        val second = makeDocument("look-b", "B", "#CC3311", 24.0, 52.0)
        appearance.apply(second)

        val active = directory.resolve(AppearanceStore.ACTIVE_FILE_NAME)
        val encoded = Files.readString(active)
        val limit = AppearanceStore.SNAPSHOT_MAX_BYTES
        assertTrue(encoded.length < limit)
        Files.writeString(active, encoded + " ".repeat(limit - encoded.length))
        assertEquals(second.id, appearance.readSnapshot().selectedID)

        Files.writeString(active, encoded + " ".repeat(limit - encoded.length + 1))
        assertFailsWith<AppearanceError> { appearance.readSnapshot() }
        val degraded = appearance.read()
        assertEquals(AppearanceSnapshotSource.SYSTEM_DEFAULT, degraded.source)
        assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, degraded.snapshot.selectedID)
        assertNotNull(degraded.error)
    }

    @Test
    fun appliedSnapshotRejectsSelectionContentMismatch() {
        val directory = temporaryDirectory("mismatch")
        val host = AppearanceStore(directory.toString())
        val reader = AppearanceStore(directory.toString())
        val document = makeDocument("look-a", "A", "#145ACC", 22.0, 44.0)
        host.apply(document)
        val active = directory.resolve(AppearanceStore.ACTIVE_FILE_NAME)
        Files.writeString(
            active,
            Files.readString(active).replaceFirst("\"selectedID\": \"" + document.id, "\"selectedID\": \"look-b"),
        )
        assertFailsWith<AppearanceError> { reader.readSnapshot() }
        val read = reader.read()
        assertTrue(read.identityIsConsistent)
        assertNotNull(read.error)
        assertTrue(read.source != AppearanceSnapshotSource.APPLIED)
    }

    @Test
    fun saveIsNotApplyAndTheSystemDefaultIsReadOnly() {
        val directory = temporaryDirectory("save-apply")
        val host = AppearanceStore(directory.toString())
        assertFailsWith<AppearanceError> { host.save(AppearanceDocument.systemDefault) }
        assertFailsWith<AppearanceError> { host.remove(AppearanceDocument.SYSTEM_DEFAULT_ID) }

        val document = makeDocument("look-a", "A", "#145ACC", 22.0, 44.0)
        host.save(document)
        assertEquals(
            listOf(AppearanceDocument.SYSTEM_DEFAULT_ID) + AppearancePresets.BUILT_IN.map { it.id } + listOf("look-a"),
            host.list().map { it.id },
        )

        // An invalid value cannot be saved or applied.
        val invalid =
            document.copy(
                candidateBar =
                    document.candidateBar.copy(
                        collapsed = document.candidateBar.collapsed.copy(height = Double.NaN),
                    ),
            )
        assertFailsWith<AppearanceError> { host.apply(invalid) }
        assertFailsWith<AppearanceError> { host.save(invalid) }
        assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, host.readSnapshot().selectedID)

        // Applying the built-in default is legal: the user explicitly switches back.
        host.apply(AppearanceDocument.systemDefault)
        assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, host.readSnapshot().selectedID)
    }

    @Test
    fun deletionRejectsTraversalWithoutTouchingSiblingFiles() {
        val directory = temporaryDirectory("delete")
        val appearance = AppearanceStore(directory.resolve("appearance").toString())
        appearance.save(AppearanceDocument.create(id = "safe", name = "Safe"))
        val victim = directory.resolve("victim.songyanappearance.json")
        Files.writeString(victim, "keep")
        for (id in listOf("../victim", "", "a/b", "a\\b")) {
            assertFailsWith<AppearanceError> { appearance.remove(id) }
        }
        assertEquals("keep", Files.readString(victim))
    }

    @Test
    fun oneBadFileIsIsolatedAndReportedWithoutHidingTheOthers() {
        val directory = temporaryDirectory("isolation")
        val store = AppearanceStore(directory.toString())
        val document = makeDocument("look-a", "A", "#145ACC", 22.0, 44.0)
        store.save(document)
        Files.writeString(directory.resolve("someone-else.songyanappearance.json"), document.encode())

        val errors = mutableListOf<AppearanceError>()
        val listed = store.list { errors += it }
        // The valid entries stay visible; the mismatched file is skipped and reported.
        assertEquals(
            listOf(AppearanceDocument.SYSTEM_DEFAULT_ID) + AppearancePresets.BUILT_IN.map { it.id } + listOf("look-a"),
            listed.map { it.id },
        )
        assertEquals(listOf(AppearanceErrorCode.IDENTITY_MISMATCH), errors.map { it.code })
        // Selecting still works: the list no longer aborts on one damaged file.
        assertEquals(listOf("look-a"), store.list().filter { !AppearancePresets.isBuiltIn(it) }.map { it.id })
    }

    @Test
    fun anUnreadableSnapshotDegradesInsteadOfThrowing() {
        val directory = temporaryDirectory("unreadable")
        val store = AppearanceStore(directory.toString())
        store.apply(AppearancePresets.androidDefault)
        val active = directory.resolve(AppearanceStore.ACTIVE_FILE_NAME)
        Files.setPosixFilePermissions(active, PosixFilePermissions.fromString("---------"))
        try {
            // Root (containers) can still read it; then the premise of the test does not hold.
            Assume.assumeTrue(
                "the test user can still read the file",
                runCatching { Files.readAllBytes(active) }.isFailure,
            )
            val read = store.read()
            assertEquals(AppearanceSnapshotSource.SYSTEM_DEFAULT, read.source)
            assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, read.snapshot.selectedID)
            assertNotNull(read.error)
        } finally {
            Files.setPosixFilePermissions(active, PosixFilePermissions.fromString("rw-------"))
        }
    }

    @Test
    fun removeKeepsTheAppliedSnapshotUntilTheNextApply() {
        val directory = temporaryDirectory("remove")
        val host = AppearanceStore(directory.toString())
        val document = makeDocument("look-a", "A", "#145ACC", 22.0, 44.0)
        host.save(document)
        host.apply(document)
        host.remove(document.id)
        assertEquals(document.candidateBar, host.readSnapshot().document.candidateBar)
        assertEquals(
            listOf(AppearanceDocument.SYSTEM_DEFAULT_ID) + AppearancePresets.BUILT_IN.map { it.id },
            host.list().map { it.id },
        )
    }

    @Test
    fun aMissingDirectoryListsOnlyTheBuiltInsAndReadsTheDefault() {
        val directory = temporaryDirectory("missing").resolve("nested")
        val store = AppearanceStore(directory.toString())
        assertEquals(
            listOf(AppearanceDocument.SYSTEM_DEFAULT_ID) + AppearancePresets.BUILT_IN.map { it.id },
            store.list().map { it.id },
        )
        assertEquals(AppearanceSnapshotSource.SYSTEM_DEFAULT, store.read().source)
        assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, store.readSnapshot().selectedID)
    }

    @Test
    fun readPaletteResolvesTheAppliedAppearance() {
        val directory = temporaryDirectory("palette")
        val store = AppearanceStore(directory.toString())
        store.apply(AppearancePresets.androidDefault)
        val light = store.readPalette(dark = false)
        val dark = store.readPalette(dark = true)
        assertTrue(light.background != dark.background)
        assertEquals(SkinDefinition.systemDefault.typography.candidateSize, light.candidateTextSizeSp, 0.001)
    }
}
