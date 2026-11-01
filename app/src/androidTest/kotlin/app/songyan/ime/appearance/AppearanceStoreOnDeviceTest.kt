package app.songyan.ime.appearance

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.settings.ui.theme.ThemeMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Runs the appearance store on a real device.
 *
 * core:appearance persists through java.nio.file, and a JVM-only test suite cannot catch platform
 * API gaps such as Files.readString/writeString (Java 11) not existing on Android. These tests fail
 * a connected run instead of only manual acceptance when that happens.
 */
@RunWith(AndroidJUnit4::class)
class AppearanceStoreOnDeviceTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        directory = File(context.cacheDir, "appearance-test-" + System.nanoTime())
        directory.mkdirs()
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun applyingAnAppearanceWritesAndReadsTheSnapshot() {
        val store = AppearanceStore(directory.absolutePath)
        store.apply(AppearancePresets.androidDefault)

        assertTrue(File(directory, AppearanceStore.ACTIVE_FILE_NAME).isFile)
        val result = store.read()
        assertEquals(AppearanceSnapshotSource.APPLIED, result.source)
        assertEquals(AppearancePresets.SONGYAN_ID, result.snapshot.document.id)
        assertNull(result.error)
    }

    @Test
    fun theStoreListsBuiltInsAndTheAppliedSnapshot() {
        val store = AppearanceStore(directory.absolutePath)
        store.apply(AppearanceDocument.systemDefault)

        val ids = store.list().map { it.id }
        assertTrue(ids.containsAll(AppearancePresets.BUILT_IN_IDS))
        assertTrue(ids.contains(AppearanceDocument.SYSTEM_DEFAULT_ID))
    }

    @Test
    fun aUserAppearanceRoundTripsThroughTheStore() {
        val store = AppearanceStore(directory.absolutePath)
        val copy = AppearancePresets.androidDefault.duplicate(" copy")
        store.save(copy)

        val ids = store.list().map { it.id }
        assertTrue(ids.contains(copy.id))
        store.apply(copy)
        val stored = store.read()
        assertEquals(copy.id, stored.snapshot.document.id)
    }

    @Test
    fun theControllerSelectsTheSystemDefaultOnDevice() {
        val controller = AppearanceStoreController(AppearanceStore(directory.absolutePath))
        val state =
            controller.select(
                AppearanceDocument.SYSTEM_DEFAULT_ID,
                dynamicColor = false,
                themeMode = ThemeMode.SYSTEM,
            )

        assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, state.document.id)
        val reloaded = AppearanceStoreController(AppearanceStore(directory.absolutePath)).read(dynamicColor = false)
        assertEquals(AppearanceDocument.SYSTEM_DEFAULT_ID, reloaded.document.id)
    }
}
