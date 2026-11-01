package app.songyan.ime.deploy

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import app.songyan.ime.BuildConfig
import app.songyan.ime.rime.BuiltinSourceInstaller
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.storage.BuildStamp
import app.songyan.ime.storage.PublicationRecord

/**
 * The build stamp a generation must carry to be used as is (H1): an app update, a librime update
 * or new bundled data each make an older generation stale, whichever source it was built from.
 *
 * The engine part is the librime release plus a hash of the engine sources (pin, patches, plugins):
 * the runtime refuses compiled data whose `rime_version` differs, and a patched or re-pinned engine
 * may compile differently under the same release. The data part is [BuiltinSourceInstaller.DATA_VERSION]
 * plus a hash of the bundled Rime data, so new data is picked up even when nobody bumped the version.
 */
object BuildStamps {
    /** Reads the package info; call off the main thread. */
    fun current(context: Context): BuildStamp =
        BuildStamp(
            app = appVersion(context),
            engine = RimeBindings.ENGINE_VERSION + "+" + BuildConfig.ENGINE_SOURCE_HASH,
            data = BuiltinSourceInstaller.DATA_VERSION + "+" + BuildConfig.RIME_DATA_HASH,
        )

    fun isCurrent(
        record: PublicationRecord,
        current: BuildStamp,
    ): Boolean = record.stamp == current

    /**
     * Release builds use the version code only: reinstalling or restoring the same build keeps its
     * generations, and engine or data changes are caught by the other two parts. Debug builds add
     * the version name and the install time, so a developer never types on tables built by the
     * previous install of changed deployment code.
     */
    fun appVersion(context: Context): String =
        try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            appVersion(info.versionName, versionCode(info), info.lastUpdateTime, BuildConfig.DEBUG)
        } catch (_: PackageManager.NameNotFoundException) {
            "0/0/0"
        }

    fun appVersion(
        versionName: String?,
        versionCode: Long,
        lastUpdateTime: Long,
        debug: Boolean,
    ): String = if (debug) (versionName ?: "0") + "/" + versionCode + "/" + lastUpdateTime else versionCode.toString()

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
}
