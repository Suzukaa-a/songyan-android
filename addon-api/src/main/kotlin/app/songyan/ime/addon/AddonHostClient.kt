// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import android.view.SurfaceControlViewHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Result of [AddonHostClient.connect]. */
public sealed interface AddonConnectResult {
    public data class Connected(
        public val handshake: AddonHandshake,
    ) : AddonConnectResult

    /** The service is installed but speaks no common protocol version; nothing was run. */
    public data class Incompatible(
        public val handshake: AddonHandshake,
    ) : AddonConnectResult

    /** No service to bind, no answer, or the addon refused the session. */
    public data class Unavailable(
        public val reason: String,
    ) : AddonConnectResult
}

/** Binding flags the host uses. */
public object AddonBinding {
    /**
     * What the input method uses: its foreground capabilities (including the microphone path the
     * IME enjoys as a visible input method) travel to the addon process with the binding.
     */
    public const val IME_FLAGS: Int = Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES
}

/**
 * The host side of the protocol: discovers addon services, binds, negotiates and forwards calls.
 *
 * This is the client helper the keyboard (and the repository's integration tests) use; the addon
 * side of the contract is [SongyanAddonService]. Every call tolerates a dead or crashing addon:
 * a broken binder marks the connection lost and reports through [Listener.onDisconnected], and no
 * method throws into the keyboard.
 */
public class AddonHostClient(
    public val context: Context,
) {
    /** Callbacks from one connection. All methods have empty defaults except the ones you need. */
    public interface Listener {
        public fun onConnected(handshake: AddonHandshake) {}

        public fun onIncompatible(handshake: AddonHandshake) {}

        public fun onUnavailable(reason: String) {}

        /** The addon process died or the binding broke; the client is disconnected. */
        public fun onDisconnected() {}

        public fun onToolResults(
            requestId: String,
            results: List<String>,
        ) {}

        public fun onToolFailed(
            requestId: String,
            failure: AddonFailure,
        ) {}

        public fun onVoiceText(
            text: String,
            isFinal: Boolean,
        ) {}

        public fun onVoiceState(state: AddonVoiceState) {}

        public fun onWarning(message: String) {}
    }

    private class Live(
        val rawBinder: IBinder,
        val service: ISongyanAddonService,
        val session: ISongyanAddonSession,
        val handshake: AddonHandshake,
        val deathRecipient: IBinder.DeathRecipient,
        val serviceConnection: ServiceConnection,
    )

    private val lock: Any = Any()
    private var live: Live? = null
    private var activeListener: Listener? = null
    private var panelStub: ISongyanAddonPanelHost? = null

    public val isConnected: Boolean
        get() = synchronized(lock) { live != null }

    public val connectedHandshake: AddonHandshake?
        get() = synchronized(lock) { live?.handshake }

    /** Installed packages that publish an addon service, in PackageManager order. */
    public fun discover(): List<ComponentName> {
        val intent = Intent(AddonProtocol.SERVICE_ACTION)

        @Suppress("DEPRECATION")
        val services = context.packageManager.queryIntentServices(intent, PackageManager.GET_SERVICES)
        return services.mapNotNull { info -> info.serviceInfo?.let { ComponentName(it.packageName, it.name) } }
    }

    /**
     * Binds, negotiates and opens one session. [bindFlags] accepts [AddonBinding.IME_FLAGS] for the
     * keyboard; the default is an ordinary foreground binding.
     */
    public fun connect(
        component: ComponentName,
        hostInfo: AddonHostInfo,
        listener: Listener,
        bindFlags: Int = Context.BIND_AUTO_CREATE,
        timeoutMillis: Long = 5_000,
    ): AddonConnectResult {
        disconnect()
        activeListener = listener
        val latch = CountDownLatch(1)
        val binderRef = AtomicReference<IBinder?>(null)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName?,
                    service: IBinder?,
                ) {
                    binderRef.set(service)
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    connectionLost()
                }

                override fun onBindingDied(name: ComponentName?) {
                    connectionLost()
                }

                override fun onNullBinding(name: ComponentName?) {
                    latch.countDown()
                }
            }
        val intent = Intent(AddonProtocol.SERVICE_ACTION).setComponent(component)
        val bound =
            try {
                context.bindService(intent, connection, bindFlags)
            } catch (failure: RuntimeException) {
                false
            }
        if (!bound) {
            activeListener = null
            return AddonConnectResult.Unavailable("addon service is not installed or cannot be bound")
        }
        val answered = latch.await(timeoutMillis, TimeUnit.MILLISECONDS)
        val rawBinder = binderRef.get()
        if (!answered || rawBinder == null) {
            runCatching { context.unbindService(connection) }
            activeListener = null
            return AddonConnectResult.Unavailable(
                if (answered) "addon returned no binder" else "addon did not bind within " + timeoutMillis + " ms",
            )
        }
        val service = ISongyanAddonService.Stub.asInterface(rawBinder)
        val handshake =
            try {
                addonHandshakeFromWire(service.negotiate(hostInfo.toWire().toBundle()).toAddonWireMap())
            } catch (failure: RemoteException) {
                runCatching { context.unbindService(connection) }
                activeListener = null
                return AddonConnectResult.Unavailable("addon died during negotiation")
            } catch (failure: RuntimeException) {
                runCatching { context.unbindService(connection) }
                activeListener = null
                return AddonConnectResult.Unavailable("bad handshake: " + failure.message)
            }
        if (!handshake.isCompatible) {
            runCatching { context.unbindService(connection) }
            activeListener = null
            listener.onIncompatible(handshake)
            return AddonConnectResult.Incompatible(handshake)
        }
        if (handshake.protocolVersion !in hostInfo.protocolMinVersion..hostInfo.protocolMaxVersion) {
            runCatching { context.unbindService(connection) }
            activeListener = null
            listener.onIncompatible(handshake)
            return AddonConnectResult.Incompatible(handshake)
        }
        val deathRecipient = IBinder.DeathRecipient { connectionLost() }
        runCatching { rawBinder.linkToDeath(deathRecipient, 0) }
        val session =
            try {
                service.openSession(hostInfo.toWire().toBundle(), hostCallback(listener))
            } catch (failure: RemoteException) {
                runCatching { context.unbindService(connection) }
                activeListener = null
                return AddonConnectResult.Unavailable("addon died while opening the session")
            }
        if (session == null) {
            runCatching { context.unbindService(connection) }
            activeListener = null
            return AddonConnectResult.Unavailable("addon refused the session")
        }
        synchronized(lock) {
            live = Live(rawBinder, service, session, handshake, deathRecipient, connection)
        }
        listener.onConnected(handshake)
        return AddonConnectResult.Connected(handshake)
    }

    /** Closes the session and unbinds without reporting [Listener.onDisconnected]. */
    public fun disconnect() {
        val old =
            synchronized(lock) {
                val current = live
                live = null
                panelStub = null
                current
            }
        activeListener = null
        if (old == null) return
        runCatching { old.session.close() }
        teardown(old)
    }

    /** The tools the addon offered in the handshake; refreshed from the live session when possible. */
    public fun tools(): List<AddonToolDescriptor> {
        val session = currentSession() ?: return emptyList()
        return try {
            session.tools().mapNotNull { bundle ->
                runCatching { addonToolDescriptorFromWire(bundle.toAddonWireMap()) }.getOrNull()
            }
        } catch (failure: RemoteException) {
            connectionLost()
            emptyList()
        }
    }

    /** Starts one text tool run; results arrive through [Listener.onToolResults] / onToolFailed. */
    public fun runTextTool(
        requestId: String,
        toolId: String,
        text: String,
    ): Boolean {
        val session = currentSession() ?: return false
        val request = mapOf(AddonKeys.RUN_TEXT to text).toBundle()
        return try {
            session.runTextTool(requestId, toolId, request)
            true
        } catch (failure: RemoteException) {
            connectionLost()
            false
        }
    }

    public fun cancelRun(requestId: String) {
        val session = currentSession() ?: return
        try {
            session.cancelRun(requestId)
        } catch (failure: RemoteException) {
            connectionLost()
        }
    }

    public fun startVoice(requestId: String): Boolean {
        val session = currentSession() ?: return false
        return try {
            session.startVoice(requestId, Bundle())
            true
        } catch (failure: RemoteException) {
            connectionLost()
            false
        }
    }

    public fun stopVoice() {
        val session = currentSession() ?: return
        try {
            session.stopVoice()
        } catch (failure: RemoteException) {
            connectionLost()
        }
    }

    /** Hands the addon a panel host and the current context; the addon reports its surface back. */
    public fun openPanel(
        requestId: String,
        panelContext: AddonPanelContext,
        panelHost: AddonPanelHost,
    ): Boolean {
        val session = currentSession() ?: return false
        val stub = panelHostStub(panelHost)
        synchronized(lock) { panelStub = stub }
        return try {
            session.openPanel(requestId, panelContext.toWire().toBundle(), stub)
            true
        } catch (failure: RemoteException) {
            synchronized(lock) { panelStub = null }
            connectionLost()
            false
        }
    }

    public fun updatePanelContext(panelContext: AddonPanelContext) {
        val session = currentSession() ?: return
        try {
            session.updatePanelContext(panelContext.toWire().toBundle())
        } catch (failure: RemoteException) {
            connectionLost()
        }
    }

    public fun closePanel() {
        val session = currentSession() ?: return
        synchronized(lock) { panelStub = null }
        try {
            session.closePanel()
        } catch (failure: RemoteException) {
            connectionLost()
        }
    }

    private fun currentSession(): ISongyanAddonSession? = synchronized(lock) { live?.session }

    private fun teardown(old: Live) {
        runCatching { old.rawBinder.unlinkToDeath(old.deathRecipient, 0) }
        runCatching { context.unbindService(old.serviceConnection) }
    }

    private fun connectionLost() {
        val old =
            synchronized(lock) {
                val current = live ?: return
                live = null
                panelStub = null
                current
            }
        teardown(old)
        activeListener?.onDisconnected()
    }

    private fun hostCallback(listener: Listener): ISongyanAddonHost =
        object : ISongyanAddonHost.Stub() {
            override fun onToolResults(
                requestId: String,
                results: MutableList<String>,
            ) {
                listener.onToolResults(requestId, results)
            }

            override fun onToolFailed(
                requestId: String,
                failure: Bundle,
            ) {
                listener.onToolFailed(requestId, addonFailureFromWire(failure.toAddonWireMap()))
            }

            override fun onVoiceText(
                text: String,
                isFinal: Boolean,
            ) {
                listener.onVoiceText(text, isFinal)
            }

            override fun onVoiceState(state: String) {
                listener.onVoiceState(AddonVoiceState.fromWireName(state) ?: AddonVoiceState.IDLE)
            }

            override fun onWarning(message: String) {
                listener.onWarning(message)
            }
        }

    private fun panelHostStub(panelHost: AddonPanelHost): ISongyanAddonPanelHost =
        object : ISongyanAddonPanelHost.Stub() {
            override fun onSurfaceReady(
                surface: SurfaceControlViewHost.SurfacePackage?,
                widthPx: Int,
                heightPx: Int,
            ) {
                if (surface != null) panelHost.onSurfaceReady(surface, widthPx, heightPx)
            }

            override fun insertText(request: Bundle): Bundle {
                val write = addonTextWriteFromWire(request.toAddonWireMap()) ?: AddonTextWrite("")
                return panelHost.insertText(write).toWire().toBundle()
            }

            override fun readText(query: Bundle): Bundle {
                val decoded =
                    addonTextQueryFromWire(query.toAddonWireMap()) ?: AddonTextQuery(AddonTextScope.BEFORE_CURSOR)
                return panelHost.readText(decoded).toWire().toBundle()
            }

            override fun panelContext(): Bundle = panelHost.panelContext().toWire().toBundle()

            override fun requestPanelSize(
                widthPx: Int,
                heightPx: Int,
            ): Bundle = panelHost.requestPanelSize(widthPx, heightPx).toWire().toBundle()

            override fun closePanel() {
                panelHost.closePanel()
            }
        }
}
