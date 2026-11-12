package app.songyan.ime.script

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The [ScriptRegistry] the app installs: every call is one Binder round-trip into the proxy
 * process, which starts it on demand. [entries] is served from a cache the proxy refreshed, so a
 * settings screen renders the list without starting anything.
 *
 * [hostCapabilities] is what this host process can offer a run; it travels to the proxy in the
 * `hello` handshake of every bind, and the proxy answers (and snapshots) `availableOnThisHost`
 * from it. The app installs the audio-capable set once its voice input path is wired; the default
 * keeps text-only hosts honest.
 */
class ScriptProxyRegistry(
    context: Context,
    private val hostCapabilities: Set<ScriptHostCapability> = ScriptHostCapability.DEFAULT,
) : ScriptRegistry {
    private val appContext = context.applicationContext

    override var entries: List<ScriptEntry> = emptyList()
        private set

    override var refreshing: Boolean = false
        private set

    override suspend fun reload() {
        val payload = call { proxy, callback -> proxy.list(callback) }
        entries = ScriptProxyProto.parseEntries(payload)
    }

    override suspend fun inspect(source: ByteArray): ScriptHeader {
        val payload = call { proxy, callback -> proxy.inspect(source, callback) }
        return ScriptProxyProto.parseHeader(payload)
    }

    override suspend fun add(
        source: ByteArray,
        origin: ScriptSourceOrigin,
    ): ScriptEntry {
        val payload = call { proxy, callback -> proxy.add(source, originJson(origin), callback) }
        return ScriptProxyProto.parseEntry(payload)
    }

    override suspend fun addFromLink(url: String): ScriptEntry {
        // The proxy downloads; the bytes never pass through the calling process.
        val payload =
            call { proxy, callback ->
                proxy.add(
                    ByteArray(0),
                    buildJsonObject {
                        put("kind", "link")
                        put("url", url)
                        put("update", url)
                    }.toString(),
                    callback,
                )
            }
        return ScriptProxyProto.parseEntry(payload)
    }

    override suspend fun confirm(
        id: String,
        settings: Map<String, String>,
    ): ScriptEntry {
        val bundle = Bundle().apply { settings.forEach { (key, value) -> putString(key, value) } }
        val payload = call { proxy, callback -> proxy.confirm(id, bundle, callback) }
        return ScriptProxyProto.parseEntry(payload)
    }

    override suspend fun updateSettings(
        id: String,
        settings: Map<String, String>,
    ): ScriptEntry = confirm(id, settings)

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ): ScriptEntry {
        val payload = call { proxy, callback -> proxy.setEnabled(id, enabled, callback) }
        return ScriptProxyProto.parseEntry(payload)
    }

    override suspend fun reorder(orderedIds: List<String>) {
        call { proxy, callback -> proxy.reorder(orderedIds, callback) }
    }

    override suspend fun source(id: String): ByteArray? {
        val payload = call { proxy, callback -> proxy.source(id, callback) }
        return ScriptProxyProto.parseSource(payload)
    }

    override suspend fun export(id: String): ByteArray? = source(id)

    override suspend fun checkUpdate(id: String): ScriptEntry {
        val payload = call { proxy, callback -> proxy.checkUpdate(id, callback) }
        return ScriptProxyProto.parseEntry(payload)
    }

    override suspend fun delete(id: String) {
        call { proxy, callback -> proxy.delete(id, callback) }
    }

    override suspend fun runForTest(
        id: String,
        text: String,
    ): ScriptRunOutcome {
        val payload = call { proxy, callback -> proxy.runForTest(id, text, callback) }
        return ScriptProxyProto.parseOutcome(payload)
    }

    override suspend fun run(
        id: String,
        text: String,
    ): ScriptRunOutcome {
        val payload = call { proxy, callback -> proxy.run(id, text, callback) }
        return ScriptProxyProto.parseOutcome(payload)
    }

    override suspend fun runAudio(
        id: String,
        wav: ByteArray,
    ): ScriptRunOutcome {
        // The size policy is checked before any process is involved; the proxy checks it again
        // from the descriptor because the Binder surface is callable on its own.
        ScriptAudioInput.sizeRefusal(wav.size.toLong())?.let { return it }
        val payload =
            withContext(Dispatchers.IO) {
                val audio = memfdOf(wav)
                try {
                    call { proxy, callback -> proxy.runAudio(id, audio, callback) }
                } finally {
                    audio.close()
                }
            }
        return ScriptProxyProto.parseOutcome(payload)
    }

    override suspend fun cancel(id: String) {
        withBinder { proxy -> proxy.cancel(id) }
    }

    /** The recorded WAV crosses as a descriptor: a Binder transaction carries at most 1 MB, and a
     *  minute of 16 kHz mono PCM is 1.92 MB. The proxy hands the descriptor on to the isolated
     *  process, which reads it before the engine starts. */
    private fun memfdOf(bytes: ByteArray): ParcelFileDescriptor {
        val fd = Os.memfd_create("script-audio", 0)
        try {
            Os.write(fd, bytes, 0, bytes.size)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            // dup takes ownership of its own copy; the memfd itself is closed below.
            return ParcelFileDescriptor.dup(fd)
        } finally {
            Os.close(fd)
        }
    }

    // --- binder plumbing ---

    private suspend fun call(action: (ScriptProxy, ProxyLatch) -> Unit): String =
        withContext(Dispatchers.IO) {
            withBinder { proxy ->
                val latch = ProxyLatch()
                action(proxy, latch)
                check(latch.await(90)) { "the script proxy did not answer" }
                latch.payload ?: error("the script proxy returned nothing")
            }
        }

    private suspend fun <T> withBinder(block: (ScriptProxy) -> T): T =
        suspendCancellableCoroutine { continuation ->
            var bound: IBinder? = null
            val ready = java.util.concurrent.CountDownLatch(1)
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        service: IBinder,
                    ) {
                        bound = service
                        ready.countDown()
                    }

                    override fun onServiceDisconnected(name: ComponentName) = Unit
                }
            val intent = Intent().setComponent(ComponentName(appContext, "app.songyan.ime.script.ScriptProxyService"))
            if (!appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
                continuation.resumeWith(Result.failure(IllegalStateException("cannot bind the script proxy")))
                return@suspendCancellableCoroutine
            }
            val worker =
                Thread {
                    try {
                        check(
                            ready.await(30, java.util.concurrent.TimeUnit.SECONDS),
                        ) { "the script proxy did not connect" }
                        val proxy = ScriptProxy.Stub.asInterface(bound)
                        // The proxy answers availability from this declaration, so it must be
                        // applied before the operation itself is sent.
                        val negotiation = ProxyLatch()
                        proxy.hello(
                            ScriptProxyProto.VERSION,
                            ScriptHostCapability.names(hostCapabilities),
                            negotiation,
                        )
                        check(negotiation.await(30L)) { "the script proxy did not answer hello" }
                        val result = block(proxy)
                        continuation.resumeWith(Result.success(result))
                    } catch (error: Throwable) {
                        continuation.resumeWith(Result.failure(error))
                    } finally {
                        appContext.unbindService(connection)
                    }
                }
            worker.start()
            continuation.invokeOnCancellation {
                worker.interrupt()
                runCatching { appContext.unbindService(connection) }
            }
        }

    private fun originJson(origin: ScriptSourceOrigin): String =
        when (origin) {
            is ScriptSourceOrigin.Pasted -> buildJsonObject { put("kind", "pasted") }.toString()
            is ScriptSourceOrigin.File ->
                buildJsonObject {
                    put("kind", "file")
                    put("name", origin.displayName)
                }.toString()
            is ScriptSourceOrigin.Link ->
                buildJsonObject {
                    put("kind", "link")
                    put("url", origin.url)
                    put("update", origin.updateUrl)
                }.toString()
            ScriptSourceOrigin.Edited -> buildJsonObject { put("kind", "edited") }.toString()
        }
}

/** Parks the caller until the proxy's oneway reply lands. */
private class ProxyLatch : ScriptProxyCallback.Stub() {
    private val latch = java.util.concurrent.CountDownLatch(1)

    var payload: String? = null
        private set

    override fun onResult(
        ok: Boolean,
        payloadJson: String,
    ) {
        if (ok) {
            payload = payloadJson
        } else {
            payload = payloadJson
        }
        latch.countDown()
    }

    fun await(timeoutSeconds: Long): Boolean = latch.await(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)
}
