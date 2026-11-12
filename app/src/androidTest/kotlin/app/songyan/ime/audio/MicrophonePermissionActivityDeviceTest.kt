package app.songyan.ime.audio

import android.Manifest
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The transparent permission activity in the granted case: it starts from a service-style intent,
 * sees the permission already there, publishes its one answer and finishes by itself, so no
 * activity of the keyboard's host is disturbed.
 *
 * The runbook grants the permission first
 * (`adb -s <serial> shell pm grant app.songyan.ime.debug android.permission.RECORD_AUDIO`); a grant
 * from inside the process would kill it. The refusal branch needs the system dialog, which is not
 * driven here; [VoiceRecorderNoPermissionDeviceTest] covers what a refusal leaves the caller with.
 */
@RunWith(AndroidJUnit4::class)
class MicrophonePermissionActivityDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    @Before
    fun requireTheMicrophonePermission() {
        assertTrue(
            "grant RECORD_AUDIO first; adb -s <serial> shell pm grant ${context.packageName} " +
                Manifest.permission.RECORD_AUDIO,
            MicrophonePermission.isGranted(context),
        )
    }

    @Test
    fun anAlreadyGrantedRequestPublishesGrantedAndFinishesByItself() {
        val activity = instrumentation.startActivitySync(MicrophonePermission.requestIntent(context))
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (!activity.isFinishing && !activity.isDestroyed && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(20)
        }

        assertTrue("the transparent activity must finish by itself", activity.isFinishing || activity.isDestroyed)
        assertEquals(MicrophonePermission.Outcome.GRANTED, MicrophonePermission.latestOutcome.value)
    }
}
