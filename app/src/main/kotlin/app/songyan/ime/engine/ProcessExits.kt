package app.songyan.ime.engine

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import app.songyan.ime.engine.EngineStartGuard.PreviousExit

/**
 * Why this app's main process last died, from the system's own record (Android 11+,
 * https://developer.android.com/reference/android/app/ActivityManager#getHistoricalProcessExitReasons(java.lang.String,%20int,%20int)).
 * The record is kept by the platform, not by the vendor's task killer, so it tells a crash from a
 * kill on every device that has it; older systems answer [PreviousExit.UNKNOWN].
 */
object ProcessExits {
    private const val TAG = "ProcessExits"
    private const val MAX_RECORDS = 16

    /** Disk and binder IO; call off the main thread. */
    fun previousExit(
        context: Context,
        sinceEpochMs: Long,
    ): PreviousExit {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return PreviousExit.UNKNOWN
        val manager = context.getSystemService(ActivityManager::class.java) ?: return PreviousExit.UNKNOWN
        val last =
            try {
                manager
                    .getHistoricalProcessExitReasons(null, 0, MAX_RECORDS)
                    .firstOrNull { it.processName == context.packageName && it.timestamp >= sinceEpochMs }
            } catch (e: RuntimeException) {
                Log.w(TAG, "exit reasons unavailable", e)
                null
            } ?: return PreviousExit.UNKNOWN
        return classify(last.reason)
    }

    fun classify(reason: Int): PreviousExit =
        when (reason) {
            ApplicationExitInfo.REASON_CRASH,
            ApplicationExitInfo.REASON_CRASH_NATIVE,
            ApplicationExitInfo.REASON_ANR,
            ApplicationExitInfo.REASON_INITIALIZATION_FAILURE,
            -> PreviousExit.CRASH

            ApplicationExitInfo.REASON_UNKNOWN -> PreviousExit.UNKNOWN

            else -> PreviousExit.NOT_A_CRASH
        }
}
