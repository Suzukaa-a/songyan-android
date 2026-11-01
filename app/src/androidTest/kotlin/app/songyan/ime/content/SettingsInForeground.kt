package app.songyan.ime.content

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import app.songyan.ime.SongyanSettingsActivity

/**
 * Keeps the settings screen in the foreground while a test asks :deploy for work: the deploy runs
 * as a foreground service, which Android 12+ only lets a visible app start.
 */
internal class SettingsInForeground {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var activity: Activity? = null

    /**
     * Waits only for "resumed", not for the main thread to go idle the way startActivitySync does
     * (an animating progress indicator never lets it).
     */
    fun open() {
        val context = instrumentation.targetContext
        // A fresh install asks for the notification permission on first launch; the system dialog
        // would keep the activity paused for the whole test.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        }
        context.startActivity(
            Intent(context, SongyanSettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        val deadline = System.currentTimeMillis() + LAUNCH_TIMEOUT_MILLIS
        while (activity == null && System.currentTimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                activity =
                    ActivityLifecycleMonitorRegistry
                        .getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .firstOrNull { it is SongyanSettingsActivity }
            }
            if (activity == null) Thread.sleep(POLL_MILLIS)
        }
        requireNotNull(activity) { "the settings activity did not resume" }
    }

    fun close() {
        activity?.let { instrumentation.runOnMainSync { it.finish() } }
        activity = null
    }

    private companion object {
        const val LAUNCH_TIMEOUT_MILLIS = 45_000L
        const val POLL_MILLIS = 100L
    }
}
