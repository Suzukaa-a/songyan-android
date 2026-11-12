package app.songyan.ime.script

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A callback that parks the calling thread until the isolated service answers. */
internal class CallbackLatch : ScriptIsolatedCallback.Stub() {
    private val latch = CountDownLatch(1)

    var exitCode: Int = -1
        private set

    var stdout: String = ""
        private set

    var stderr: String = ""
        private set

    override fun finished(
        exitCode: Int,
        stdout: String,
        stderr: String,
    ) {
        this.exitCode = exitCode
        this.stdout = stdout
        this.stderr = stderr
        latch.countDown()
    }

    fun await(timeoutSeconds: Long = 60): Boolean = latch.await(timeoutSeconds, TimeUnit.SECONDS)
}
