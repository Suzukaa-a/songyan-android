package app.songyan.ime.deploy

import app.songyan.ime.storage.AppStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files

class DeployStatusStoreTest {
    private fun storage(): AppStorage = AppStorage(Files.createTempDirectory("files").toFile())

    @Test
    fun missingStatusFileReadsAsIdle() {
        val snapshot = DeployStatusStore(storage()).readSnapshot()
        assertEquals(DeployState.IDLE, snapshot.state)
        assertEquals(0L, snapshot.updatedAtEpochMs)
        assertNull(snapshot.tag)
    }

    @Test
    fun writesStateTagAndANewerTimestamp() {
        val store = DeployStatusStore(storage())
        store.write(DeployState.PREPARING, "rev-a")
        val preparing = store.readSnapshot()
        assertEquals(DeployState.PREPARING, preparing.state)
        assertEquals("rev-a", preparing.tag)
        assertEquals(0L < preparing.updatedAtEpochMs, true)

        store.write(DeployState.FAILED, "rev-b")
        val failed = store.readSnapshot()
        assertEquals(DeployState.FAILED, failed.state)
        assertEquals("rev-b", failed.tag)
        assertEquals(true, failed.updatedAtEpochMs >= preparing.updatedAtEpochMs)
    }
}
