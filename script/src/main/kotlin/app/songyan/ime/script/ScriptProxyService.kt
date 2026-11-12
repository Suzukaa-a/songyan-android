package app.songyan.ime.script

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
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

    /** The fetch surface the isolated process calls during a run; one call per request. */
    private val transportBinder: ScriptTransport.Stub =
        object : ScriptTransport.Stub() {
            override fun fetch(
                url: String,
                method: String,
                headersJson: String?,
                body: ByteArray?,
                callback: ScriptTransportCallback,
            ) {
                executor.execute {
                    val reply =
                        ScriptNetworkTransport.fetch(
                            url,
                            method,
                            headersJson,
                            body,
                            declaredHosts(),
                            ScriptCaseRun.PROVISIONAL_RESPONSE_BYTES,
                        )
                    callback.onResult(
                        reply.accepted,
                        reply.status,
                        reply.body,
                        reply.contentType,
                        reply.finalUrl,
                        reply.headersJson,
                        reply.error,
                    )
                }
            }
        }

    /** The confirmed @connect list across installed scripts: the transport authorizes against it. */
    private fun declaredHosts(): List<String> = store.list(hostCapabilities).flatMap { it.connects }

    override fun onBind(intent: Intent): IBinder =
        if (intent.action == "app.songyan.ime.script.TRANSPORT") transportBinder else binder

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
                            ScriptFetcher.download(origin.url)
                        } else {
                            source
                        }
                    val header =
                        HeaderParser.parse(this@ScriptProxyService, bytes) as? ScriptHeader.Parsed
                            ?: return@reply ScriptProxyProto.error("the header is refused")
                    ScriptProxyProto.entry(store.add(bytes, header, origin, hostCapabilities))
                }
            }

            override fun confirm(
                id: String,
                settings: Bundle,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    val entry = store.entry(id) ?: return@reply ScriptProxyProto.unavailable("no script " + id)
                    val header =
                        HeaderParser.parse(this@ScriptProxyService, store.source(id)!!) as? ScriptHeader.Parsed
                            ?: return@reply ScriptProxyProto.error("the stored header is refused")
                    val values =
                        header.settings.associate { field ->
                            field.key to (settings.getString(field.key) ?: "")
                        }
                    ScriptProxyProto.entry(store.confirm(id, header, values, header.settings, hostCapabilities))
                }
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
                    val fresh = ScriptFetcher.download(origin.updateUrl)
                    val header =
                        HeaderParser.parse(this@ScriptProxyService, fresh) as? ScriptHeader.Parsed
                            ?: return@reply ScriptProxyProto.error("the updated header is refused")
                    store.replaceSource(id, fresh, header)
                    ScriptProxyProto.entry(store.entry(id, hostCapabilities)!!)
                }
            }

            override fun runTrial(
                id: String,
                text: String,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    val outcome = ScriptRunner(this@ScriptProxyService, store).run(id, text)
                    ScriptProxyProto.outcome(outcome)
                }
            }

            override fun run(
                id: String,
                text: String,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    val outcome = ScriptRunner(this@ScriptProxyService, store).run(id, text)
                    ScriptProxyProto.outcome(outcome)
                }
            }

            override fun runAudio(
                id: String,
                audio: ParcelFileDescriptor,
                reply: ScriptProxyCallback,
            ) {
                reply(reply) {
                    try {
                        val outcome = ScriptRunner(this@ScriptProxyService, store).runAudio(id, audio)
                        ScriptProxyProto.outcome(outcome)
                    } finally {
                        // The descriptor is this process's after the Binder call; the isolated run
                        // already holds its own copy by the time the reply is written.
                        runCatching { audio.close() }
                    }
                }
            }

            override fun cancel(id: String) {
                ScriptRunner(this@ScriptProxyService, store).cancel(id)
            }
        }

    private inline fun reply(
        callback: ScriptProxyCallback,
        crossinline body: () -> String,
    ) {
        executor.execute {
            val payload =
                try {
                    body()
                } catch (error: Throwable) {
                    ScriptProxyProto.error(error.message ?: error.javaClass.simpleName)
                }
            runCatching { refreshSnapshot() }
            callback.onResult(true, payload)
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
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
