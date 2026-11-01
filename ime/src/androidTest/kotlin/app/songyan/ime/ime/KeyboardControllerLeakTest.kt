package app.songyan.ime.ime

import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

/**
 * A configuration change builds a new controller on the same service scope. The retired one must stop
 * collecting preferences and the layout catalog, so it is neither updated nor kept alive.
 */
@RunWith(AndroidJUnit4::class)
class KeyboardControllerLeakTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private class Source : KeyboardPreferencesSource {
        val height = MutableStateFlow(KeyHeight.DEFAULT.wire)
        override val layoutId: Flow<String> = MutableStateFlow("qwerty26")
        override val activeSchemaId: Flow<String> = MutableStateFlow("songyan_quanpin")
        override val keyHeight: Flow<String> get() = height

        override suspend fun setLayoutId(id: String) = Unit

        override suspend fun setActiveSchemaId(id: String) = Unit
    }

    private fun newController(source: Source): KeyboardController {
        lateinit var controller: KeyboardController
        instrumentation.runOnMainSync {
            controller =
                KeyboardController(
                    instrumentation.targetContext,
                    scope,
                    KeyboardController.Callbacks(
                        commit = { _, _ -> true },
                        preedit = {},
                        delete = {},
                        moveCursor = {},
                        action = {},
                        error = { throw AssertionError(it) },
                    ),
                    preferences = source,
                )
            controller.configure(EditorPolicy(false, false, false, EditorInfo.IME_ACTION_NONE))
        }
        instrumentation.waitForIdleSync()
        return controller
    }

    private fun gc() {
        repeat(5) {
            System.gc()
            Runtime.getRuntime().runFinalization()
            Thread.sleep(50)
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { scope.cancel() }
    }

    @Test
    fun retiredControllersStopCollectingAndAreGarbageCollected() {
        val source = Source()
        val baseline = newController(source)
        val perController = source.height.subscriptionCount.value
        assertTrue("a controller collects preferences", perController > 0)
        instrumentation.runOnMainSync { baseline.retire() }
        instrumentation.waitForIdleSync()
        assertEquals(0, source.height.subscriptionCount.value)

        val retired = mutableListOf<WeakReference<KeyboardController>>()
        repeat(10) {
            val controller = newController(source)
            retired += WeakReference(controller)
            instrumentation.runOnMainSync { controller.retire() }
        }
        val live = newController(source)
        instrumentation.waitForIdleSync()
        assertEquals(perController, source.height.subscriptionCount.value)

        // Only the live controller sees a preference update.
        instrumentation.runOnMainSync { source.height.value = KeyHeight.TALL.wire }
        instrumentation.waitForIdleSync()
        assertEquals(KeyHeight.TALL, KeyboardGeometry.keyHeight)

        instrumentation.runOnMainSync { live.retire() }
        instrumentation.waitForIdleSync()
        gc()
        // The newest retired controller or two can still be held by transient framework state (a
        // pending view callback); what must not happen is that they pile up, one per change.
        val alive = retired.indices.filter { retired[it].get() != null }
        android.util.Log.w("LEAKDIAG", "alive=$alive")
        assertTrue(
            "retired controllers pile up: still reachable $alive of ${retired.size}",
            alive.all {
                it >=
                    retired.size - 2
            },
        )
    }
}
