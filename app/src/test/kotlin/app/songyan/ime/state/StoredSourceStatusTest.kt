package app.songyan.ime.state

import app.songyan.ime.settings.state.StoredSourceStatus
import app.songyan.ime.storage.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class StoredSourceStatusTest {
    private fun status(
        kind: SourceKind = SourceKind.SCHEME,
        intact: Boolean = true,
        stored: String = "abc",
        published: String? = null,
    ) = storedSourceStatus(kind, intact, stored, published)

    @Test
    fun aSourceTheKeyboardDoesNotUseIsNotInUse() = assertEquals(StoredSourceStatus.NOT_IN_USE, status())

    @Test
    fun aGenerationBuiltFromTheStoredFilesIsInUse() {
        assertEquals(StoredSourceStatus.IN_USE, status(published = "abc+overlay1"))
        assertEquals(StoredSourceStatus.IN_USE, status(published = "abc"))
    }

    @Test
    fun filesNewerThanTheRunningGenerationOfferTheNewVersion() {
        assertEquals(StoredSourceStatus.UPDATE_AVAILABLE, status(stored = "def", published = "abc+overlay1"))
        // A longer revision that merely begins with the stored one is another revision.
        assertEquals(StoredSourceStatus.UPDATE_AVAILABLE, status(stored = "abc", published = "abcd+overlay1"))
    }

    @Test
    fun missingFilesMakeAPackageDamagedWhetherOrNotItIsInUse() {
        assertEquals(StoredSourceStatus.DAMAGED, status(intact = false))
        assertEquals(StoredSourceStatus.DAMAGED, status(intact = false, published = "abc+x"))
    }

    @Test
    fun theBuiltInSourceIsNeverDamagedOrOutdated() {
        assertEquals(StoredSourceStatus.IN_USE, status(kind = SourceKind.BUILTIN, intact = false, published = "zzz"))
    }
}
