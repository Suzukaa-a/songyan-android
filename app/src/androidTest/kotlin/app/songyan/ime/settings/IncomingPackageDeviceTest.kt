package app.songyan.ime.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.IncomingPackageIntents
import app.songyan.ime.SongyanSettingsActivity
import app.songyan.ime.settings.state.IncomingPackageHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** P4-2: a ZIP opened or shared from another app reaches the settings screen, which asks before importing. */
@RunWith(AndroidJUnit4::class)
class IncomingPackageDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val zip = Uri.parse("content://example.documents/document/package.zip")

    @Before
    fun setUp() = IncomingPackageHolder.clear()

    @After
    fun tearDown() = IncomingPackageHolder.clear()

    private fun view(
        uri: Uri,
        type: String?,
    ) = Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)

    private fun send(
        uri: Uri,
        type: String,
    ) = Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri)

    private fun handlers(intent: Intent): Set<String> =
        context.packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .filter { it.activityInfo.packageName == context.packageName }
            .map { it.activityInfo.name }
            .toSet()

    @Test
    fun theSettingsActivityHandlesOpeningAndSharingAZip() {
        val activity = SongyanSettingsActivity::class.java.name
        assertEquals(setOf(activity), handlers(view(zip, "application/zip")))
        assertEquals(setOf(activity), handlers(view(zip, "application/x-zip-compressed")))
        assertEquals(setOf(activity), handlers(send(zip, "application/zip")))
        assertEquals("other files are not ours", emptySet<String>(), handlers(view(zip, "text/plain")))
        assertEquals(emptySet<String>(), handlers(send(zip, "image/png")))
    }

    @Test
    fun onlyContentUrisOfZipsAreAccepted() {
        assertEquals(zip, IncomingPackageIntents.packageUri(view(zip, "application/zip")))
        assertEquals(zip, IncomingPackageIntents.packageUri(send(zip, "application/zip")))
        assertNull(IncomingPackageIntents.packageUri(view(Uri.parse("file:///sdcard/p.zip"), "application/zip")))
        assertNull(IncomingPackageIntents.packageUri(view(Uri.parse("https://example.com/p.zip"), "application/zip")))
        assertNull(IncomingPackageIntents.packageUri(view(zip, "text/plain")))
        assertNull(IncomingPackageIntents.packageUri(Intent(Intent.ACTION_SEND).setType("application/zip")))
        assertNull(IncomingPackageIntents.packageUri(Intent(Intent.ACTION_MAIN)))
        assertNull(IncomingPackageIntents.packageUri(null))
    }

    @Test
    fun anOpenedZipWaitsForTheUsersAnswerInsteadOfImporting() {
        val activity =
            instrumentation.startActivitySync(
                Intent(view(zip, "application/zip"))
                    .setClass(context, SongyanSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (IncomingPackageHolder.uri.value == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertEquals(zip, IncomingPackageHolder.uri.value)
            assertTrue("nothing was imported without a yes", activity != null)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
