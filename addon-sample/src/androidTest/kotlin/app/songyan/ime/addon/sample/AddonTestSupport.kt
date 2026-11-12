// SPDX-License-Identifier: Apache-2.0
package app.songyan.ime.addon.sample

import android.content.ComponentName
import android.content.Context
import android.view.SurfaceControlViewHost
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.addon.AddonFailure
import app.songyan.ime.addon.AddonHostClient
import app.songyan.ime.addon.AddonHostInfo
import app.songyan.ime.addon.AddonPanelContext
import app.songyan.ime.addon.AddonPanelHost
import app.songyan.ime.addon.AddonTextQuery
import app.songyan.ime.addon.AddonTextResult
import app.songyan.ime.addon.AddonTextScope
import app.songyan.ime.addon.AddonTextWrite
import app.songyan.ime.addon.AddonVoiceState
import app.songyan.ime.addon.AddonWriteResult
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

internal const val SAMPLE_PACKAGE: String = "app.songyan.ime.addon.sample"
internal const val SAMPLE_SERVICE: String = "app.songyan.ime.addon.sample.SampleAddonService"

internal fun sampleComponent(className: String): ComponentName = ComponentName(SAMPLE_PACKAGE, className)

internal fun testContext(): Context = InstrumentationRegistry.getInstrumentation().context

internal fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

internal fun testHostInfo(): AddonHostInfo = AddonHostInfo.forHost(testContext())

/** One addon service string that is not installed, for the missing-service case. */
internal fun missingComponent(): ComponentName =
    ComponentName(SAMPLE_PACKAGE, "app.songyan.ime.addon.sample.NoSuchService")

/** Records every addon callback and gives the tests latches to wait on. */
internal class CallbackRecorder : AddonHostClient.Listener {
    private val toolResults: ConcurrentHashMap<String, List<String>> = ConcurrentHashMap()
    private val toolFailures: ConcurrentHashMap<String, AddonFailure> = ConcurrentHashMap()
    private val toolLatches: ConcurrentHashMap<String, CountDownLatch> = ConcurrentHashMap()
    val voiceTexts: CopyOnWriteArrayList<Pair<String, Boolean>> = CopyOnWriteArrayList()
    val voiceStates: CopyOnWriteArrayList<AddonVoiceState> = CopyOnWriteArrayList()
    val warnings: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
    private val disconnectedLatch: CountDownLatch = CountDownLatch(1)

    @Volatile
    var disconnected: Boolean = false
        private set

    fun awaitResults(
        requestId: String,
        timeoutMillis: Long = 5_000,
    ): List<String>? {
        latchFor(requestId).await(timeoutMillis, TimeUnit.MILLISECONDS)
        return toolResults[requestId]
    }

    fun awaitFailure(
        requestId: String,
        timeoutMillis: Long = 5_000,
    ): AddonFailure? {
        latchFor(requestId).await(timeoutMillis, TimeUnit.MILLISECONDS)
        return toolFailures[requestId]
    }

    fun awaitDisconnected(timeoutMillis: Long = 5_000): Boolean =
        disconnectedLatch.await(timeoutMillis, TimeUnit.MILLISECONDS)

    fun awaitVoiceFinal(timeoutMillis: Long = 5_000): String? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            val final = voiceTexts.lastOrNull { it.second }
            if (final != null) return final.first
            Thread.sleep(20)
        }
        return null
    }

    private fun latchFor(requestId: String): CountDownLatch = toolLatches.getOrPut(requestId) { CountDownLatch(1) }

    override fun onToolResults(
        requestId: String,
        results: List<String>,
    ) {
        toolResults[requestId] = results
        latchFor(requestId).countDown()
    }

    override fun onToolFailed(
        requestId: String,
        failure: AddonFailure,
    ) {
        toolFailures[requestId] = failure
        latchFor(requestId).countDown()
    }

    override fun onVoiceText(
        text: String,
        isFinal: Boolean,
    ) {
        voiceTexts.add(text to isFinal)
    }

    override fun onVoiceState(state: AddonVoiceState) {
        voiceStates.add(state)
    }

    override fun onWarning(message: String) {
        warnings.add(message)
    }

    override fun onDisconnected() {
        disconnected = true
        disconnectedLatch.countDown()
    }
}

/** The host side of a panel: records what the addon asks the editor to do. */
internal class RecordingPanelHost(
    private val attachSurface: (SurfaceControlViewHost.SurfacePackage) -> Unit,
    initial: AddonPanelContext,
) : AddonPanelHost {
    @Volatile
    private var latest: AddonPanelContext = initial

    val surfaceReady: CountDownLatch = CountDownLatch(1)
    val writes: CopyOnWriteArrayList<AddonTextWrite> = CopyOnWriteArrayList()
    val reads: CopyOnWriteArrayList<AddonTextQuery> = CopyOnWriteArrayList()
    val sizeRequests: CopyOnWriteArrayList<Pair<Int, Int>> = CopyOnWriteArrayList()
    val closeRequests: CountDownLatch = CountDownLatch(1)

    @Volatile
    var surface: SurfaceControlViewHost.SurfacePackage? = null
        private set

    @Volatile
    var surfaceWidth: Int = 0
        private set

    @Volatile
    var surfaceHeight: Int = 0
        private set

    fun updateContext(context: AddonPanelContext) {
        latest = context
    }

    override fun onSurfaceReady(
        surface: SurfaceControlViewHost.SurfacePackage,
        widthPx: Int,
        heightPx: Int,
    ) {
        this.surface = surface
        surfaceWidth = widthPx
        surfaceHeight = heightPx
        attachSurface(surface)
        surfaceReady.countDown()
    }

    override fun insertText(write: AddonTextWrite): AddonWriteResult {
        writes.add(write)
        return AddonWriteResult.APPLIED
    }

    override fun readText(query: AddonTextQuery): AddonTextResult {
        reads.add(query)
        return AddonTextResult(
            text = if (query.mode == AddonTextScope.SELECTION) "选区内容" else "光标前的文字",
            scope = query.mode,
        )
    }

    override fun panelContext(): AddonPanelContext = latest

    override fun requestPanelSize(
        widthPx: Int,
        heightPx: Int,
    ): AddonPanelContext {
        sizeRequests.add(widthPx to heightPx)
        latest = latest.copy(widthPx = widthPx, heightPx = heightPx)
        return latest
    }

    override fun closePanel() {
        closeRequests.countDown()
    }
}

/** Waits for a condition without sleeping the main thread; used around Binder callbacks. */
internal fun waitFor(
    timeoutMillis: Long = 5_000,
    condition: () -> Boolean,
): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(20)
    }
    return condition()
}

/** Reads one string resource of the sample app (the test APK does not own those resources). */
internal fun sampleString(name: String): String {
    val resources = targetContext().resources
    val id = resources.getIdentifier(name, "string", SAMPLE_PACKAGE)
    return resources.getString(id)
}

internal fun sampleDp(value: Int): Int = (value * targetContext().resources.displayMetrics.density).toInt()
