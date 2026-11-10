package app.songyan.ime.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchTargetsTest {
    private val builtin = manifest(SourceIds.BUILTIN, SourceKind.BUILTIN)
    private val ice = manifest("rime-ice", SourceKind.SCHEME)
    private val kagiroi = manifest("rime-kagiroi", SourceKind.SCHEME)

    @Test
    fun theBuiltInSourceIsNeverOfferedFromAnotherSource() {
        assertEquals(
            listOf("rime-kagiroi"),
            SwitchTargets.of(listOf(builtin, ice, kagiroi), "rime-ice").map { it.sourceId },
        )
    }

    @Test
    fun whileTheBuiltInSourceRunsTheImportedOnesAreOffered() {
        assertEquals(
            listOf("rime-ice", "rime-kagiroi"),
            SwitchTargets.of(listOf(builtin, ice, kagiroi), SourceIds.BUILTIN).map { it.sourceId },
        )
    }

    private fun manifest(
        id: String,
        kind: SourceKind,
    ) = SourceManifest(id, kind, id, listOf(SourceScheme(id + "_schema", id)), "rev")
}
