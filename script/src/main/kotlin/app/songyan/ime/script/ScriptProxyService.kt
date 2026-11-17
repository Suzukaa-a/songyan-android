package app.songyan.ime.script

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.KeyStore
import java.util.concurrent.Executors
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * The script proxy process: storage, import confirmation state, settings and secrets live here;
 * JS itself runs only in the isolated service. The process starts on demand (binding from the app
 * or settings) and exits when idle - Android stops an unbound service process, which is the whole
 * point of keeping the engine out of the IME process.
 */
class ScriptProxyService : Service() {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "songyan-script-proxy")
        }

    /** What the connected host says it can offer a run; text only until a `hello` declares
     *  more. It decides `availableOnThisHost` for every entry and for the catalog snapshot, so
     *  the IME's cache-less reader gets the same answer without binding here. */
    private var hostCapabilities: Set<ScriptHostCapability> = ScriptHostCapability.DEFAULT

    private val storeRoot: File
        get() = File(filesDir, "script")

    private val store: ScriptStore by lazy {
        ScriptStore(storeRoot, keystoreWrap())
    }

    /** The keyboard process reads this file without ever binding here. */
    private fun refreshSnapshot() {
        runCatching { ScriptCatalogSnapshotWriter.write(storeRoot, store.list(hostCapabilities)) }
    }

    /** One runner for the service, so a cancel reaches the run it names. */
    private val runner by lazy { ScriptRunner(this, store) }

    /** Runs wait on the network for up to their duration; they get their own thread so the
     *  settings screens never wait behind one. Runs stay sequential. */
    private val runExecutor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "songyan-script-proxy-run")
        }

    override fun onBind(intent: Intent): IBinder = binder

    private val binder: ScriptProxy.Stub =
        object : ScriptProxy.Stub() {
            override fun hello(
                clientVersion: Int,
                capabilities: MutableList<String>,
                negotiation: ScriptProxyCallback,
            ) {
                reply(negotiation) {
                    // The declaration is applied before the reply is written, so the snapshot the
                    // host-visible catalog comes from is refreshed with it.
                    hostCapabilities = ScriptHostCapability.parse(capabilities)
                    val common = minOf(clientVersion, ScriptProxyProto.VERSION)
                    ScriptProxyProto.negotiation(ScriptProxyProto.VERSION, common)
                }
            }

            override fun list(reply: ScriptProxyCallback) =
                reply(reply) { ScriptProxyProto.entries(store.list(hostCapabilities)) }

            override fun inspect(
                source: ByteArray,
                reply: ScriptProxyCallback,
            ) = reply(reply) {
                ScriptProxyProto.header(HeaderParser.parse(this@ScriptProxyService, source))
            }

            override fun add(
                source: ByteArray,
                originJson: String,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    val originNode = ScriptProxyProto.json.parseToJsonElement(originJson).jsonObject
                    val origin =
                        when (originNode["kind"]?.jsonPrimitive?.contentOrNull) {
                            "file" -> ScriptSourceOrigin.File(originNode["name"]!!.jsonPrimitive.content)
                            "link" ->
                                ScriptSourceOrigin.Link(
                                    originNode["url"]!!.jsonPrimitive.content,
                                    originNode["update"]!!.jsonPrimitive.content,
                                )
                            "edited" -> ScriptSourceOrigin.Edited
                            else -> ScriptSourceOrigin.Pasted
                        }
                    // An empty source with a link origin means "download it in the proxy": the bytes
                    // never travel through the calling process.
                    val bytes =
                        if (source.isEmpty() && origin is ScriptSourceOrigin.Link) {
                            download(origin.url) ?: return@reply downloadFailed()
                        } else {
                            source
                        }
                    store.idWithSource(bytes)?.let { existing ->
                        return@reply ScriptProxyProto.entry(store.entry(existing, hostCapabilities)!!)
                    }
                    val header =
                        when (val parsed = HeaderParser.parse(this@ScriptProxyService, bytes)) {
                            is ScriptHeader.Parsed -> parsed
                            is ScriptHeader.Refused -> return@reply ScriptProxyProto.refused(parsed)
                        }
                    ScriptProxyProto.entry(store.add(bytes, header, origin, hostCapabilities))
                }
            }

            override fun confirm(
                id: String,
                settings: Bundle,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) { confirmStored(id, settings, emptySet()) }
            }

            override fun settings(
                id: String,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) { ScriptProxyProto.settings(store.plainSettings(id)) }
            }

            override fun confirmClear(
                id: String,
                settings: Bundle,
                clearSecrets: MutableList<String>,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) { confirmStored(id, settings, clearSecrets.toSet()) }
            }

            override fun setEnabled(
                id: String,
                enabled: Boolean,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    store.setEnabled(id, enabled)
                    ScriptProxyProto.entry(store.entry(id, hostCapabilities)!!)
                }
            }

            override fun reorder(
                orderedIds: MutableList<String>,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    store.reorder(orderedIds)
                    ScriptProxyProto.ack()
                }
            }

            override fun source(
                id: String,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) { ScriptProxyProto.source(store.source(id)) }
            }

            override fun delete(
                id: String,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    store.delete(id)
                    ScriptProxyProto.ack()
                }
            }

            override fun checkUpdate(
                id: String,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    val origin =
                        store.originOf(id) as? ScriptSourceOrigin.Link
                            ?: return@reply ScriptProxyProto.error("the script did not come from a link")
                    val fresh = download(origin.updateUrl) ?: return@reply downloadFailed()
                    val header =
                        when (val parsed = HeaderParser.parse(this@ScriptProxyService, fresh)) {
                            is ScriptHeader.Parsed -> parsed
                            is ScriptHeader.Refused -> return@reply ScriptProxyProto.refused(parsed)
                        }
                    store.replaceSource(id, fresh, header)
                    ScriptProxyProto.entry(store.entry(id, hostCapabilities)!!)
                }
            }

            override fun replaceSource(
                id: String,
                source: ByteArray,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    val header =
                        when (val parsed = HeaderParser.parse(this@ScriptProxyService, source)) {
                            is ScriptHeader.Parsed -> parsed
                            is ScriptHeader.Refused -> return@reply ScriptProxyProto.refused(parsed)
                        }
                    // An edited script no longer matches its link, so a later update must not undo the edit.
                    store.replaceSource(id, source, header, ScriptSourceOrigin.Edited)
                    ScriptProxyProto.entry(store.entry(id, hostCapabilities)!!)
                }
            }

            override fun runTrial(
                id: String,
                text: String,
                reply: ScriptProxyCallback,
            ) {
                reply(reply, runExecutor) {
                    ScriptProxyProto.outcome(runner.run(id, text))
                }
            }

            override fun run(
                id: String,
                text: String,
                reply: ScriptProxyCallback,
            ) {
                reply(reply, runExecutor) {
                    ScriptProxyProto.outcome(runner.run(id, text))
                }
            }

            override fun runAudio(
                id: String,
                audio: ParcelFileDescriptor,
                reply: ScriptProxyCallback,
            ) {
                reply(reply, runExecutor) {
                    try {
                        val outcome = runner.runAudio(id, audio)
                        ScriptProxyProto.outcome(outcome)
                    } finally {
                        // The descriptor is this process's after the Binder call; the isolated run
                        // already holds its own copy by the time the reply is written.
                        runCatching { audio.close() }
                    }
                }
            }

            override fun cancel(id: String) {
                runner.cancel(id)
            }
        }

    /** Parses the stored header and writes the form's values; shared by confirm and confirmClear. */
    private fun confirmStored(
        id: String,
        settings: Bundle,
        clearSecrets: Set<String>,
    ): String {
        val entry = store.entry(id) ?: return ScriptProxyProto.unavailable("no script " + id)
        val source = store.source(id) ?: return ScriptProxyProto.unavailable("no source for " + id)
        val header =
            when (val parsed = HeaderParser.parse(this, source)) {
                is ScriptHeader.Parsed -> parsed
                is ScriptHeader.Refused -> return ScriptProxyProto.refused(parsed)
            }
        // Only the keys the form actually sent travel: an omitted key keeps what is stored, and
        // a blank secret is not mistaken for an explicit clear.
        val values = mutableMapOf<String, String>()
        header.settings.forEach { field ->
            settings.getString(field.key)?.let { value -> values[field.key] = value }
        }
        return ScriptProxyProto.entry(
            store.confirm(id, header, values, header.settings, hostCapabilities, clearSecrets),
        )
    }

    private inline fun reply(
        callback: ScriptProxyCallback,
        on: java.util.concurrent.Executor = executor,
        crossinline body: () -> String,
    ) {
        on.execute {
            val payload =
                try {
                    body()
                } catch (error: Throwable) {
                    Log.w(TAG, "script proxy call failed", error)
                    ScriptProxyProto.error(error.message ?: error.javaClass.simpleName)
                }
            // A run changes nothing the catalog shows; only store work refreshes the snapshot.
            if (on === executor) runCatching { refreshSnapshot() }
            callback.onResult(true, payload)
        }
    }

    /** The link's bytes, or null with the reason in logcat when the download failed. */
    private fun download(url: String): ByteArray? =
        try {
            ScriptFetcher.download(url)
        } catch (error: Exception) {
            Log.w(TAG, "script download failed", error)
            null
        }

    private fun downloadFailed(): String =
        ScriptProxyProto.refused("downloadFailed", "the link could not be downloaded")

    private companion object {
        const val TAG = "SongyanScript"
    }

    override fun onDestroy() {
        // Interrupting the run thread does not stop a blocking socket read; disconnect it too.
        runner.cancelAll()
        executor.shutdownNow()
        runExecutor.shutdownNow()
        super.onDestroy()
    }
}

/** The keystore-backed wrap: the data key is encrypted by an AES/GCM key that never leaves the
 *  secure hardware-backed keystore, so secret values are unreadable outside this process. */
internal fun keystoreWrap(): ScriptStore.KeyWrap =
    object : ScriptStore.KeyWrap {
        private val alias = "songyan-script-store"

        private fun keystoreKey(): SecretKey {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (keyStore.getKey(alias, null) as? SecretKey)?.let { return it }
            val generator =
                javax.crypto.KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore",
                )
            generator.init(
                KeyGenParameterSpec
                    .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            return generator.generateKey()
        }

        override fun wrap(key: SecretKey): ByteArray {
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, keystoreKey())
            val encrypted = cipher.doFinal(key.encoded)
            return byteArrayOf(cipher.iv.size.toByte()) + cipher.iv + encrypted
        }

        override fun unwrap(bytes: ByteArray): SecretKey {
            val ivSize = bytes[0].toInt()
            val iv = bytes.copyOfRange(1, 1 + ivSize)
            val encrypted = bytes.copyOfRange(1 + ivSize, bytes.size)
            val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE, keystoreKey(), javax.crypto.spec.GCMParameterSpec(128, iv))
            return SecretKeySpec(cipher.doFinal(encrypted), "AES")
        }
    }
