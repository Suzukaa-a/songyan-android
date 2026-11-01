package app.songyan.ime.ime

import android.content.Context
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.keyboard.layout.KeyboardLayoutKind
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

@RunWith(AndroidJUnit4::class)
class LeakProbeTmpTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun probe(make: (Context) -> Any): String {
        val refs = ArrayList<WeakReference<Any>>()
        instrumentation.runOnMainSync {
            repeat(8) { refs += WeakReference(make(instrumentation.targetContext)) }
        }
        instrumentation.waitForIdleSync()
        repeat(5) {
            System.gc()
            Runtime.getRuntime().runFinalization()
            Thread.sleep(50)
        }
        return refs.count { it.get() != null }.toString()
    }

    @Test
    fun probe() {
        val out = StringBuilder()

        fun add(
            name: String,
            make: (Context) -> Any,
        ) = out
            .append(name)
            .append('=')
            .append(probe(make))
            .append(' ')
        add("plain") { LinearLayout(it) }
        add("keyboardView") { KeyboardView(it) }
        add("popupLayer") { KeyPopupLayer(it) }
        add("candidates") { CandidateBarView(it) }
        add("toolbar") { ToolbarView(it) }
        add("inline") { InlineSuggestionStrip(it) }
        add("reading") { ReadingRowView(it) }
        add("feedback") { c -> KeyboardFeedback(c).also { it.close() } }
        add("menu") {
            KeyboardSwitcherPanel(
                it,
                app.songyan.ime.keyboard.layout.BuiltinTemplateSchemas.QUANPIN,
                KeyboardLayoutKind.QWERTY26,
            )
        }
        add("shell") { OneHandShell(it, FrameLayout(it)) }
        fail("PROBE $out")
    }
}
