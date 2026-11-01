package app.songyan.ime.learning

import app.songyan.ime.storage.SourceIds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LearningBackupIdentityTest {
    @Test
    fun androidSourcesUseTheIosStructuredKeys() {
        assertEquals("userScheme:songyan-builtin", LearningBackupIdentity.archiveSourceId(SourceIds.BUILTIN))
        assertEquals("userDictionary", LearningBackupIdentity.archiveSourceId(SourceIds.DICTIONARY))
        assertEquals("userScheme:scheme-0123abcd", LearningBackupIdentity.archiveSourceId("scheme-0123abcd"))
    }

    @Test
    fun builtinAndImportedDictionaryShareOneLearningNamespace() {
        for (current in listOf(SourceIds.BUILTIN, SourceIds.DICTIONARY)) {
            assertTrue(LearningBackupIdentity.accepts("userDictionary", current))
            assertTrue(LearningBackupIdentity.accepts("userScheme:songyan-builtin", current))
            assertFalse(LearningBackupIdentity.accepts("userScheme:scheme-0123abcd", current))
            assertFalse(LearningBackupIdentity.accepts("userScheme:rime-ice", current))
        }
    }

    @Test
    fun aSchemePackageTakesItsOwnBackupsAndIosPackagesButNoOtherPackage() {
        val current = "scheme-0123abcd"
        assertTrue(LearningBackupIdentity.accepts("userScheme:scheme-0123abcd", current))
        assertFalse(LearningBackupIdentity.accepts("userScheme:scheme-ffff0000", current))
        assertFalse(LearningBackupIdentity.accepts("userDictionary", current))
        assertFalse(LearningBackupIdentity.accepts("userScheme:songyan-builtin", current))
        // An iOS package id cannot be mapped to Android's content hash; the engine checks the dictionaries.
        assertTrue(LearningBackupIdentity.accepts("userScheme:rime-ice", current))
    }

    @Test
    fun unknownKeysAreRefused() {
        for (key in listOf("", "rime-ice", "userScheme:", "userdictionary")) {
            assertFalse(key, LearningBackupIdentity.accepts(key, SourceIds.BUILTIN))
            assertFalse(key, LearningBackupIdentity.accepts(key, "scheme-0123abcd"))
        }
    }
}
