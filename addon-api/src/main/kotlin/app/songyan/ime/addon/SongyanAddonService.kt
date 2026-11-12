// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.view.SurfaceControlViewHost

/**
 * The service base class an addon extends. It owns everything that must be identical in every
 * addon: Binder plumbing, caller verification, protocol version negotiation and the guarantee that
 * an incompatible or unverified caller runs nothing.
 *
 * An addon only implements [createAddon] and the [SongyanAddon] / [SongyanAddonSession]
 * interfaces. The class is fail-closed: exceptions from addon code become failure callbacks or
 * are dropped, never crashes or hangs in the keyboard process.
 */
public abstract class SongyanAddonService : Service() {
    private val addonLock: Any = Any()
    private var addonInstance: SongyanAddon? = null

    /**
     * Who may bind here. The default accepts only the exact Songyan packages with a matching
     * signing certificate, so an addon that does not override this rejects everyone until it pins
     * Songyan's published certificate fingerprint.
     */
    protected open val callerPolicy: AddonCallerPolicy = AddonCallerPolicy.DEFAULT

    /** Creates the addon implementation; called once, lazily, on first accepted call. */
    protected abstract fun createAddon(): SongyanAddon

    private val addon: SongyanAddon
        get() =
            synchronized(addonLock) {
                addonInstance ?: createAddon().also { addonInstance = it }
            }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action != AddonProtocol.SERVICE_ACTION) return null
        return serviceBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        disconnectAddon()
        return false
    }

    override fun onDestroy() {
        disconnectAddon()
        super.onDestroy()
    }

    private fun disconnectAddon() {
        synchronized(addonLock) {
            addonInstance?.onDisconnect()
        }
    }

    private fun rejectionReason(): String? =
        when (val decision = AddonCallers.verify(this, Binder.getCallingUid(), callerPolicy)) {
            is CallerDecision.Accepted -> null
            is CallerDecision.Rejected -> decision.reason
        }

    private val serviceBinder: ISongyanAddonService.Stub =
        object : ISongyanAddonService.Stub() {
            override fun negotiate(hostInfo: Bundle): Bundle {
                rejectionReason()?.let { reason ->
                    return incompatibleHandshake("", "", reason, AddonProtocol.MIN_VERSION, AddonProtocol.MAX_VERSION)
                }
                val info =
                    runCatching { addonHostInfoFromWire(hostInfo.toAddonWireMap()) }.getOrNull()
                        ?: return incompatibleHandshake(
                            "",
                            "",
                            "invalid host info",
                            AddonProtocol.MIN_VERSION,
                            AddonProtocol.MAX_VERSION,
                        )
                val implementation = addon
                val range = implementation.supportedProtocolVersions
                val version =
                    AddonProtocol.negotiatedVersion(
                        hostMinVersion = info.protocolMinVersion,
                        hostMaxVersion = info.protocolMaxVersion,
                        addonMinVersion = range.first,
                        addonMaxVersion = range.last,
                    ) ?: return incompatibleHandshake(
                        implementation.addonId,
                        implementation.addonName,
                        "no common protocol version",
                        range.first,
                        range.last,
                    )
                val handshake =
                    runCatching { implementation.handshake(HostCallback(info, version, null)) }
                        .getOrElse { failure ->
                            return incompatibleHandshake(
                                implementation.addonId,
                                implementation.addonName,
                                "handshake failed: " + failure.message,
                                range.first,
                                range.last,
                            )
                        }
                val finalized =
                    handshake.copy(
                        protocolVersion = version,
                        supportedMinVersion = range.first,
                        supportedMaxVersion = range.last,
                    )
                return finalized.toWire().toBundle()
            }

            override fun openSession(
                hostInfo: Bundle,
                host: ISongyanAddonHost?,
            ): ISongyanAddonSession? {
                if (host == null) return null
                if (rejectionReason() != null) return null
                val info = runCatching { addonHostInfoFromWire(hostInfo.toAddonWireMap()) }.getOrNull() ?: return null
                val implementation = addon
                val range = implementation.supportedProtocolVersions
                val version =
                    AddonProtocol.negotiatedVersion(
                        hostMinVersion = info.protocolMinVersion,
                        hostMaxVersion = info.protocolMaxVersion,
                        addonMinVersion = range.first,
                        addonMaxVersion = range.last,
                    ) ?: return null
                val callback = HostCallback(info, version, host)
                val session = runCatching { implementation.openSession(callback) }.getOrNull() ?: return null
                return SessionBinder(callback, session)
            }

            override fun disconnect() {
                disconnectAddon()
            }
        }

    private fun incompatibleHandshake(
        addonId: String,
        addonName: String,
        reason: String,
        supportedMinVersion: Int,
        supportedMaxVersion: Int,
    ): Bundle =
        AddonHandshake
            .incompatible(
                addonId = addonId,
                addonName = addonName,
                reason = reason,
                supportedMinVersion = supportedMinVersion,
                supportedMaxVersion = supportedMaxVersion,
            ).toWire()
            .toBundle()

    private class HostCallback(
        override val hostInfo: AddonHostInfo,
        override val protocolVersion: Int,
        private val binder: ISongyanAddonHost?,
    ) : AddonHost {
        override fun onToolResults(
            requestId: String,
            results: List<String>,
        ) {
            runCatching { binder?.onToolResults(requestId, results) }
        }

        override fun onToolFailed(
            requestId: String,
            failure: AddonFailure,
        ) {
            runCatching { binder?.onToolFailed(requestId, failure.toWire().toBundle()) }
        }

        override fun onVoiceText(
            text: String,
            isFinal: Boolean,
        ) {
            runCatching { binder?.onVoiceText(text, isFinal) }
        }

        override fun onVoiceState(state: AddonVoiceState) {
            runCatching { binder?.onVoiceState(state.wireName) }
        }

        override fun onWarning(message: String) {
            runCatching { binder?.onWarning(message) }
        }
    }

    private class SessionBinder(
        private val host: HostCallback,
        private val session: SongyanAddonSession,
    ) : ISongyanAddonSession.Stub() {
        private var panel: PanelBridge? = null

        override fun tools(): List<Bundle> =
            runCatching { session.tools().map { it.toWire().toBundle() } }.getOrElse { emptyList() }

        override fun runTextTool(
            requestId: String,
            toolId: String,
            request: Bundle,
        ) {
            val text = request.getString(AddonKeys.RUN_TEXT) ?: ""
            val tool = runCatching { session.tools().firstOrNull { it.id == toolId } }.getOrNull()
            if (tool == null) {
                host.onToolFailed(requestId, AddonFailure(AddonFailureCode.NOT_SUPPORTED, "unknown tool: " + toolId))
                return
            }
            runCatching { session.runTextTool(requestId, tool, text) }.onFailure { failure ->
                host.onToolFailed(requestId, AddonFailure(AddonFailureCode.INTERNAL_ERROR, failure.message))
            }
        }

        override fun cancelRun(requestId: String) {
            runCatching { session.cancelRun(requestId) }
        }

        override fun startVoice(
            requestId: String,
            options: Bundle,
        ) {
            runCatching { session.startVoice(requestId) }.onFailure { failure ->
                host.onToolFailed(requestId, AddonFailure(AddonFailureCode.INTERNAL_ERROR, failure.message))
            }
        }

        override fun stopVoice() {
            runCatching { session.stopVoice() }
        }

        override fun openPanel(
            requestId: String,
            context: Bundle,
            panelHost: ISongyanAddonPanelHost?,
        ) {
            val decoded = runCatching { addonPanelContextFromWire(context.toAddonWireMap()) }.getOrNull()
            if (decoded == null || panelHost == null) {
                host.onToolFailed(requestId, AddonFailure(AddonFailureCode.INVALID_REQUEST, "panel context missing"))
                return
            }
            val bridge = PanelBridge(panelHost, decoded)
            panel = bridge
            runCatching { session.openPanel(requestId, decoded, bridge) }.onFailure { failure ->
                panel = null
                host.onToolFailed(requestId, AddonFailure(AddonFailureCode.INTERNAL_ERROR, failure.message))
            }
        }

        override fun updatePanelContext(context: Bundle) {
            val decoded = runCatching { addonPanelContextFromWire(context.toAddonWireMap()) }.getOrNull() ?: return
            panel?.update(decoded)
            runCatching { session.updatePanelContext(decoded) }
        }

        override fun closePanel() {
            panel = null
            runCatching { session.closePanel() }
        }

        override fun close() {
            panel = null
            runCatching { session.close() }
        }
    }

    private class PanelBridge(
        private val host: ISongyanAddonPanelHost,
        initialContext: AddonPanelContext,
    ) : AddonPanelHost {
        private var latestContext: AddonPanelContext = initialContext

        fun update(context: AddonPanelContext) {
            latestContext = context
        }

        override fun onSurfaceReady(
            surface: SurfaceControlViewHost.SurfacePackage,
            widthPx: Int,
            heightPx: Int,
        ) {
            runCatching { host.onSurfaceReady(surface, widthPx, heightPx) }
        }

        override fun insertText(write: AddonTextWrite): AddonWriteResult =
            runCatching { addonWriteResultFromWire(host.insertText(write.toWire().toBundle()).toAddonWireMap()) }
                .getOrElse { AddonWriteResult(applied = false, blockReason = AddonWriteBlockReason.NOT_APPLIED) }

        override fun readText(query: AddonTextQuery): AddonTextResult =
            runCatching { addonTextResultFromWire(host.readText(query.toWire().toBundle()).toAddonWireMap()) }
                .getOrElse { AddonTextResult(blockReason = AddonReadBlockReason.NO_EDITOR) }

        override fun panelContext(): AddonPanelContext =
            runCatching { addonPanelContextFromWire(host.panelContext().toAddonWireMap()) }
                .getOrElse { latestContext }

        override fun requestPanelSize(
            widthPx: Int,
            heightPx: Int,
        ): AddonPanelContext =
            runCatching {
                val applied = addonPanelContextFromWire(host.requestPanelSize(widthPx, heightPx).toAddonWireMap())
                latestContext = applied
                applied
            }.getOrElse { latestContext }

        override fun closePanel() {
            runCatching { host.closePanel() }
        }
    }
}
