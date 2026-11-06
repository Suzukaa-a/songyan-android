package app.songyan.ime.settings

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.SongyanSettingsActivity
import app.songyan.ime.settings.state.IncomingToolHolder
import app.songyan.ime.ui.ToolImportIntents
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A tool file another app opens or shares reaches the settings screen, which asks before importing. */
@RunWith(AndroidJUnit4::class)
class ToolImportDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.targetContext
    private val toolFile = Uri.parse("content://example.documents/document/sample.songyantool.json")

    @Before
    fun setUp() = IncomingToolHolder.clear()

    @After
    fun tearDown() = IncomingToolHolder.clear()

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
    fun theSettingsActivityHandlesOpeningAndSharingAToolFile() {
        val activity = SongyanSettingsActivity::class.java.name
        assertEquals(setOf(activity), handlers(view(toolFile, "application/json")))
        assertEquals(setOf(activity), handlers(view(toolFile, "application/octet-stream")))
        assertEquals(setOf(activity), handlers(view(toolFile, "application/vnd.songyan.tool+json")))
        assertEquals(setOf(activity), handlers(send(toolFile, "application/json")))
        assertEquals(setOf(activity), handlers(send(toolFile, "application/vnd.songyan.tool+json")))
        // Our own type does not depend on the file name, so a provider that hides the path works.
        val opaque = Uri.parse("content://example.documents/document/1234")
        assertEquals(setOf(activity), handlers(view(opaque, "application/vnd.songyan.tool+json")))
        // Another JSON document is not a tool and must not open this app.
        val notes = Uri.parse("content://example.documents/document/notes.json")
        assertEquals("other JSON documents are not ours", emptySet<String>(), handlers(view(notes, "application/json")))
    }

    @Test
    fun onlyContentToolFilesAreAccepted() {
        assertEquals(toolFile, ToolImportIntents.toolUri(view(toolFile, "application/json")))
        assertEquals(toolFile, ToolImportIntents.toolUri(send(toolFile, "application/json")))
        assertNull(ToolImportIntents.toolUri(view(Uri.parse("file:///sdcard/t.songyantool.json"), "application/json")))
        assertNull(
            ToolImportIntents.toolUri(view(Uri.parse("https://example.com/t.songyantool.json"), "application/json")),
        )
        assertNull(ToolImportIntents.toolUri(view(toolFile, "application/zip")))
        assertNull(ToolImportIntents.toolUri(Intent(Intent.ACTION_SEND).setType("application/json")))
        assertNull(ToolImportIntents.toolUri(Intent(Intent.ACTION_MAIN)))
        assertNull(ToolImportIntents.toolUri(null))
    }

    @Test
    fun anOpenedToolFileWaitsForTheUsersAnswerInsteadOfImporting() {
        val activity =
            instrumentation.startActivitySync(
                view(toolFile, "application/json")
                    .setClass(context, SongyanSettingsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        try {
            val deadline = SystemClock.elapsedRealtime() + 10_000
            while (IncomingToolHolder.uri.value == null && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
            assertEquals(toolFile, IncomingToolHolder.uri.value)
            assertTrue("the settings screen is showing", activity != null)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }
}
