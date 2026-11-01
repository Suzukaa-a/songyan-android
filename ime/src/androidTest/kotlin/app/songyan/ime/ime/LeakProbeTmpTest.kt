package app.songyan.ime.ime

import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

@RunWith(AndroidJUnit4::class)
class LeakProbeTmpTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun make(): List<WeakReference<Any>> {
        val refs = ArrayList<WeakReference<Any>>()
        instrumentation.runOnMainSync {
            repeat(8) { refs += WeakReference(LinearLayout(instrumentation.targetContext)) }
        }
        instrumentation.waitForIdleSync()
        return refs
    }

    private fun alive(refs: List<WeakReference<Any>>) = refs.count { it.get() != null }

    @Test
    fun probe() {
        val out = StringBuilder()
        var refs = make()
        repeat(5) {
            System.gc()
            Runtime.getRuntime().runFinalization()
            Thread.sleep(50)
        }
        out.append("gc5=").append(alive(refs)).append(' ')
        refs = make()
        repeat(20) {
            System.gc()
            Thread.sleep(100)
        }
        out.append("gc20sleep=").append(alive(refs)).append(' ')
        refs = make()
        val junk = ArrayList<ByteArray>()
        repeat(10) {
            junk += ByteArray(4 * 1024 * 1024)
            System.gc()
        }
        junk.clear()
        out.append("pressure=").append(alive(refs)).append(' ')
        refs = make()
        // Allocation churn on the main thread, in case a reference is parked in its allocation buffer.
        instrumentation.runOnMainSync { repeat(200_000) { Object() } }
        System.gc()
        out.append("mainchurn=").append(alive(refs)).append(' ')
        refs = make()
        Thread.sleep(5_000)
        System.gc()
        out.append("sleep5=").append(alive(refs)).append(' ')
        fail("PROBE $out sdk=${android.os.Build.VERSION.SDK_INT}")
    }
}
