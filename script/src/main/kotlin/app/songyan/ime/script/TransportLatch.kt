package app.songyan.ime.script

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Parks the engine thread until the proxy answers one fetch. */
internal class TransportLatch : ScriptTransportCallback.Stub() {
    private val latch = CountDownLatch(1)

    var reply: ProxyTransport.Reply = ProxyTransport.Reply(false, 0, null, null, null, null, "no answer")
        private set

    override fun onResult(
        accepted: Boolean,
        status: Int,
        body: ByteArray?,
        contentType: String?,
        finalUrl: String?,
        headersJson: String?,
        error: String?,
    ) {
        reply = ProxyTransport.Reply(accepted, status, body, contentType, finalUrl, headersJson, error)
        latch.countDown()
    }

    fun await(timeoutSeconds: Long): Boolean = latch.await(timeoutSeconds, TimeUnit.SECONDS)
}
