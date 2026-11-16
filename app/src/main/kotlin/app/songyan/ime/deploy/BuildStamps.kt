package app.songyan.ime.deploy

import app.songyan.ime.BuildConfig
import app.songyan.ime.rime.BuiltinSourceInstaller
import app.songyan.ime.rime.RimeBindings
import app.songyan.ime.storage.BuildStamp
import app.songyan.ime.storage.PublicationRecord

/**
 * The build stamp a generation must carry to be used as is (H1): new deployment code, a librime
 * update or new bundled data each make an older generation stale, whichever source it was built from.
 *
 * Every part is a content hash, never the app version: an update that changes none of them keeps
 * every compiled generation, so switching input methods after an update needs no rebuild.
 * - app: the deployment code (pipeline, installer, user patches, scheme presets), which decides
 *   what files librime is given.
 * - engine: the librime release plus a hash of the engine sources (pin, patches, plugins); the
 *   runtime refuses compiled data whose `rime_version` differs, and a patched or re-pinned engine
 *   may compile differently under the same release.
 * - data: [BuiltinSourceInstaller.DATA_VERSION] plus a hash of the bundled Rime data, so new data
 *   is picked up even when nobody bumped the version.
 */
object BuildStamps {
    fun current(): BuildStamp =
        BuildStamp(
            app = BuildConfig.DEPLOY_SOURCE_HASH,
            engine = RimeBindings.ENGINE_VERSION + "+" + BuildConfig.ENGINE_SOURCE_HASH,
            data = BuiltinSourceInstaller.DATA_VERSION + "+" + BuildConfig.RIME_DATA_HASH,
        )

    fun isCurrent(
        record: PublicationRecord,
        current: BuildStamp,
    ): Boolean = record.stamp == current
}
