package app.songyan.ime.ime

import android.os.Debug
import android.widget.LinearLayout
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.ref.WeakReference

@RunWith(AndroidJUnit4::class)
class LeakProbeTmpTest {
    @Volatile private var sink: ByteArray? = null

    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun gcCount() =
        Debug.getRuntimeStat("art.gc.gc-count") + "/" + Debug.getRuntimeStat("art.gc.blocking-gc-count")

    private fun settle() {
        repeat(3) {
            System.gc()
            Runtime.getRuntime().runFinalization()
            Thread.sleep(50)
        }
    }

    private fun aliveOf(make: () -> Any): Int {
        val refs = ArrayList<WeakReference<Any>>()
        repeat(8) { refs += WeakReference(make()) }
        settle()
        return refs.count { it.get() != null }
    }

    private fun aliveOnMain(make: () -> Any): Int {
        val refs = ArrayList<WeakReference<Any>>()
        instrumentation.runOnMainSync { repeat(8) { refs += WeakReference(make()) } }
        instrumentation.waitForIdleSync()
        settle()
        return refs.count { it.get() != null }
    }

    @Test
    fun probe() {
        val out = StringBuilder()
        val context = instrumentation.targetContext
        val before = gcCount()
        out.append("object=").append(aliveOf { Any() }).append(' ')
        out
            .append("gcs=")
            .append(before)
            .append("->")
            .append(gcCount())
            .append(' ')
        out.append("sb=").append(aliveOf { StringBuilder("x") }).append(' ')
        out.append("viewTestThread=").append(aliveOf { LinearLayout(context) }).append(' ')
        out.append("viewMain=").append(aliveOnMain { LinearLayout(context) }).append(' ')
        out.append("viewAppCtx=").append(aliveOnMain { LinearLayout(context.applicationContext) }).append(' ')
        out.append("viewTestCtx=").append(aliveOnMain { LinearLayout(instrumentation.context) }).append(' ')
        out.append("objectMain=").append(aliveOnMain { Any() }).append(' ')
        out
            .append(
                "handlerThread=",
            ).append(aliveOf { android.os.Handler(android.os.Looper.getMainLooper()) })
            .append(' ')
        run {
            val refs = ArrayList<WeakReference<Any>>()
            repeat(8) { refs += WeakReference(Any()) }
            val start = gcCount()
            var i = 0
            while (i < 3000 && refs.any { it.get() != null }) {
                sink = ByteArray(1024 * 1024)
                i++
            }
            out
                .append("churn=")
                .append(refs.count { it.get() != null })
                .append("@")
                .append(i)
                .append(" ")
            out
                .append("gcs=")
                .append(start)
                .append("->")
                .append(gcCount())
                .append(' ')
        }
        val props =
            runCatching {
                Runtime
                    .getRuntime()
                    .exec(arrayOf("getprop"))
                    .inputStream
                    .bufferedReader()
                    .readLines()
                    .filter { Regex("dalvik|heap|gc|debuggable|qemu.hw").containsMatchIn(it) }
            }.getOrNull()
        out.append(" props=").append(props).append(' ')
        out.append("sdk=").append(android.os.Build.VERSION.SDK_INT)
        fail("PROBE $out")
    }
}
