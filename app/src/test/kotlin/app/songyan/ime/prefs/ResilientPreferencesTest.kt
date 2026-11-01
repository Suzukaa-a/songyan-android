package app.songyan.ime.prefs

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class ResilientPreferencesTest {
    @get:Rule val folder = TemporaryFolder()

    private val key = stringPreferencesKey("layout")

    private fun store(
        file: File,
        scope: CoroutineScope,
    ) = PreferenceDataStoreFactory.create(
        corruptionHandler = preferencesCorruptionHandler("test"),
        scope = scope,
        produceFile = { file },
    )

    @Test fun corruptFileReadsAsDefaultsAndStaysWritable() =
        runBlocking {
            val file = folder.newFile("keyboard.preferences_pb")
            file.writeBytes(byteArrayOf(0x7f, 0x01, 0x02, 0x03, 0x55, 0x66.toByte(), 0x77))
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            try {
                val dataStore = store(file, scope)
                assertNull(dataStore.data.orDefaultsOnIoError().first()[key])
                dataStore.edit { it[key] = "merged14" }
                assertEquals("merged14", dataStore.data.first()[key])
            } finally {
                scope.cancel()
            }
        }

    @Test fun ioErrorFallsBackToDefaultsButOtherErrorsPropagate() =
        runBlocking {
            val broken = flow<Preferences> { throw IOException("disk") }
            assertTrue(
                broken
                    .orDefaultsOnIoError()
                    .first()
                    .asMap()
                    .isEmpty(),
            )
            val bug = flow<Preferences> { throw IllegalStateException("bug") }
            assertThrows(IllegalStateException::class.java) { runBlocking { bug.orDefaultsOnIoError().first() } }
            Unit
        }

    @Test fun handlerKeepsAScopeAliveAfterAFailedFireAndForgetWrite() =
        runBlocking {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + LogAndContinue)
            try {
                scope.launch { throw IOException("ENOSPC") }.join()
                var ran = false
                scope.launch { ran = true }.join()
                assertTrue(ran)
            } finally {
                scope.cancel()
            }
        }
}
