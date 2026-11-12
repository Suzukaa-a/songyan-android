package app.songyan.ime.audio

import android.Manifest
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The unauthorized path. The runbook revokes the permission over adb before this class runs:
 * `adb -s <serial> shell pm revoke app.songyan.ime.debug android.permission.RECORD_AUDIO`.
 * A take must then be refused with a typed error and write nothing.
 *
 * The permission is not revoked from inside the process — changing the app's own runtime permission
 * kills it — so this class only asserts the state the harness prepared.
 */
@RunWith(AndroidJUnit4::class)
class VoiceRecorderNoPermissionDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun requireTheRevokedPermission() {
        assertFalse(
            "revoke RECORD_AUDIO first; adb -s <serial> shell pm revoke ${context.packageName} " +
                Manifest.permission.RECORD_AUDIO,
            MicrophonePermission.isGranted(context),
        )
    }

    @Test
    fun withoutThePermissionAStartIsRejectedAndNothingIsRecorded() {
        val recorder = VoiceRecorder(context)
        assertEquals(VoiceRecordingStart.Rejected(VoiceRecordingError.NO_PERMISSION), recorder.start())
        assertFalse("a refused take must not run", recorder.isRecording)
        assertEquals(VoiceRecordingOutcome.NotRecording, recorder.stop())
    }
}
