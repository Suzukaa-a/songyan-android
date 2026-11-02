package app.songyan.ime.storage

import app.songyan.ime.deploy.DeployFixtures
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InputSchemeSetupTest {
    private val storage = DeployFixtures.storage()

    @Test
    fun emptyDirectoriesAndAnInterruptedDownloadStillNeedAScheme() {
        File(storage.importStagingDir, "incoming-download").mkdirs()
        File(storage.sourcesDir, ".installing-package").mkdirs()
        File(storage.versionsDir, ".publishing-first").mkdirs()
        assertTrue(InputSchemeSetup.needsDownload(storage))
    }

    @Test
    fun aFirstInstallKilledAfterMovingThePackageStillNeedsAChoice() {
        val source = storage.sourceDir("scheme-0123456789abcdef").also { it.mkdirs() }
        File(source, InputSchemeSetup.INSTALLING).writeText("")
        assertTrue(InputSchemeSetup.needsDownload(storage))
    }

    @Test
    fun aDamagedActiveRecordRetainsTheRecoveryPath() {
        storage.activeFile.writeText("interrupted")
        assertFalse(InputSchemeSetup.needsDownload(storage))
    }

    @Test
    fun aRestoredPackageRetainsTheRecoveryPathEvenBeforeItIsRebuilt() {
        storage.sourceDir("scheme-0123456789abcdef").mkdirs()
        assertFalse(InputSchemeSetup.needsDownload(storage))
    }

    @Test
    fun restoredLearningRetainsTheRecoveryPath() {
        File(storage.userRoot, "pinyin").mkdirs()
        assertFalse(InputSchemeSetup.needsDownload(storage))
    }

    @Test
    fun aCompletePublishedGenerationRetainsRecoveryWhenItsPointerIsLost() =
        runTest {
            DeployFixtures.coordinator(storage, DeployFixtures.FakeRunner()).deployBuiltin().getOrThrow()
            storage.activeFile.delete()
            storage.sourceDir(SourceIds.BUILTIN).deleteRecursively()
            assertFalse(InputSchemeSetup.needsDownload(storage))
        }
}
