// SPDX-License-Identifier: GPL-3.0-only
package app.songyan.ime.addons

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The registry is built in Application.onCreate, on the main thread. Resolving the app's files
 * directory (getFilesDir) is itself a disk access and StrictMode logs it (observed device stack:
 * ContextImpl.ensurePrivateDirExists <- createAddonRegistry <- SongyanApplication.onCreate), so the
 * store directory has to stay lazy and be resolved on the IO dispatcher on first use.
 */
@RunWith(AndroidJUnit4::class)
class AddonRegistryInitDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Counts getFilesDir calls; [getApplicationContext] answers itself so the factory cannot slip past. */
    private class CountingContext(
        base: Context,
    ) : ContextWrapper(base) {
        var filesDirCalls: Int = 0
            private set

        override fun getApplicationContext(): Context = this

        override fun getFilesDir(): File {
            filesDirCalls += 1
            return super.getFilesDir()
        }
    }

    @Test
    fun creatingTheRegistryOnTheMainThreadResolvesNoPrivateDirectory() {
        val counting = CountingContext(instrumentation.targetContext)
        lateinit var registry: AddonRegistry
        instrumentation.runOnMainSync {
            registry = createAddonRegistry(counting)
            // The in-memory snapshot is what the keyboard reads: still no directory access.
            assertTrue(registry.entries().isEmpty())
        }
        assertEquals(
            "creating the registry on the main thread must not resolve the private directory",
            0,
            counting.filesDirCalls,
        )
    }

    @Test
    fun theFirstIoUseResolvesTheDirectoryOnTheIoDispatcher() =
        runBlocking {
            val counting = CountingContext(instrumentation.targetContext)
            lateinit var registry: AddonRegistry
            instrumentation.runOnMainSync { registry = createAddonRegistry(counting) }
            assertEquals(0, counting.filesDirCalls)
            // The scan reads the state file with withContext(ioDispatcher); only there may the
            // directory be resolved (and StrictMode's main-thread policy does not apply).
            registry.scan(negotiate = false)
            assertEquals(1, counting.filesDirCalls)
        }
}
