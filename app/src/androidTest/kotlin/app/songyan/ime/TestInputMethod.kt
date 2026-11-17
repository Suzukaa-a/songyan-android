package app.songyan.ime

import android.content.Context
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.songyan.ime.deploy.DeployContract
import app.songyan.ime.storage.GenerationPublisher

/**
 * Prepares the built-in input method when the app has none. A fresh install has no input method
 * until the user downloads one, and then the keyboard shows no keys; tests that read or press
 * keys only need some input method, so they must not depend on what an earlier test left behind.
 */
internal fun ensureAnInputMethod(
    context: Context,
    timeoutMs: Long = 300_000L,
) {
    val publisher = GenerationPublisher((context.applicationContext as SongyanApplication).storage)
    if (publisher.usablePublication() != null) return
    ContextCompat.startForegroundService(context, DeployContract.builtinIntent(context, force = true))
    val deadline = SystemClock.elapsedRealtime() + timeoutMs
    while (publisher.usablePublication() == null) {
        check(SystemClock.elapsedRealtime() < deadline) { "the built-in input method was not prepared" }
        Thread.sleep(500)
    }
}
