package app.songyan.ime.tools

import android.content.pm.ActivityInfo
import android.content.pm.ResolveInfo
import app.songyan.ime.keyboard.engine.InputLanguage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The speech apps the user turned on: off by default, stored beside (not inside) the other apps'
 * list, and only apps this app can actually start are ever listed.
 */
class SpeechToolAppsTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val recognizer = "app.voice/app.voice.Recognize"
    private val other = "app.other/app.other.Recognize"

    private fun directory(): File = temporary.newFolder("tools")

    private fun speech(directory: File) = ProcessTextToolApps(directory, ToolAppKind.SPEECH)

    private fun processText(directory: File) = ProcessTextToolApps(directory, ToolAppKind.PROCESS_TEXT)

    private fun candidate(
        exported: Boolean = true,
        permission: String? = null,
    ): ResolveInfo =
        ResolveInfo().apply {
            activityInfo =
                ActivityInfo().apply {
                    this.exported = exported
                    this.permission = permission
                }
        }

    @Test
    fun noSpeechAppIsOnByDefault() =
        runBlocking {
            assertTrue(speech(directory()).enabled().isEmpty())
        }

    @Test
    fun theEnabledSetPersistsAcrossInstances() =
        runBlocking {
            val directory = directory()
            speech(directory).setEnabled(recognizer, true)
            speech(directory).setEnabled(other, true)

            assertEquals(setOf(recognizer, other), speech(directory).enabled())
            assertEquals(setOf(other), speech(directory).setEnabled(recognizer, false))
            assertEquals(setOf(other), speech(directory).enabled())
        }

    @Test
    fun theSpeechListAndTheOtherAppsListDoNotShareAFile() =
        runBlocking {
            val directory = directory()
            speech(directory).setEnabled(recognizer, true)

            assertTrue("a speech app was enabled as a text handler", processText(directory).enabled().isEmpty())
            assertEquals(
                listOf(ToolAppKind.SPEECH.fileName),
                directory.listFiles().orEmpty().map { it.name },
            )
            assertEquals("speech-apps.json", ToolAppKind.SPEECH.fileName)
            assertEquals("process-text.json", ToolAppKind.PROCESS_TEXT.fileName)
        }

    @Test
    fun onlyExportedAppsWhosePermissionIsHeldAreListed() {
        val open = candidate()
        val granted = candidate(permission = "app.voice.PERMISSION")
        val denied = candidate(permission = "app.voice.DENIED")
        val hidden = candidate(exported = false)
        val noActivity = ResolveInfo()

        val listed =
            filterLaunchable(listOf(open, granted, denied, hidden, noActivity)) { it == "app.voice.PERMISSION" }

        assertEquals(listOf(open, granted), listed)
        assertEquals(
            "an empty permission needs nothing",
            2,
            filterLaunchable(
                listOf(open, candidate(permission = "")),
            ) {
                false
            }.size,
        )
    }

    @Test
    fun theInputLanguageIsAskedForAsTheTagRecognizersKnow() {
        assertEquals("zh-CN", speechLanguageTag(InputLanguage.ZH_HANS))
        assertEquals("zh-TW", speechLanguageTag(InputLanguage.ZH_HANT))
        assertEquals("ja-JP", speechLanguageTag(InputLanguage.JA))
    }
}
