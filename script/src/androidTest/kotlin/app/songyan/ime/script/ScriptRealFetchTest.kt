package app.songyan.ime.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket

/** The production fetch path: a run in the isolated process, a real socket in this process,
 *  Binder to the proxy for the transport. The loopback address is the one plain-HTTP host the
 *  address policy allows when the script declares it. */
@RunWith(AndroidJUnit4::class)
class ScriptRealFetchTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry = ScriptProxyRegistry(context)

    /** Serves one request per accept; the lambda decides the status line and body. */
    private class Loopback(
        val server: ServerSocket,
    ) {
        val port: Int = server.localPort

        fun answer(respond: (path: String) -> Pair<String, String>) {
            val socket = server.accept()
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: ""
                var header = reader.readLine()
                while (!header.isNullOrEmpty()) header = reader.readLine()
                val (status, body) = respond(requestLine.split(" ").getOrNull(1) ?: "/")
                val bytes = body.toByteArray(Charsets.UTF_8)
                socket.getOutputStream().apply {
                    write(
                        ("HTTP/1.1 " + status + "\r\nContent-Length: " + bytes.size + "\r\nConnection: close\r\n\r\n")
                            .toByteArray(),
                    )
                    write(bytes)
                    flush()
                }
            } finally {
                socket.close()
            }
        }

        fun close() = server.close()
    }

    private fun loopback(): Loopback = Loopback(ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")))

    private fun fetchScript(
        host: String,
        path: String,
        urlHost: String = host,
    ): ByteArray {
        val quote = '\u0022'
        return listOf(
            "// @api 1",
            "// @name fetch 测试",
            "// @connect " + host,
            "export default async () => {",
            "  const r = await fetch(" + quote + "http://" + urlHost + path + quote + ");",
            "  return await r.text();",
            "};",
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    @Test
    fun declaredLoopbackHostFetchesAndReturns() =
        runBlocking {
            val server = loopback()
            val worker =
                Thread {
                    runCatching { server.answer { "200 OK" to "你好，来自回环" } }
                }
            worker.start()
            try {
                val entry =
                    registry.add(
                        fetchScript("127.0.0.1", "/hi", urlHost = "127.0.0.1:" + server.port),
                        ScriptSourceOrigin.Pasted,
                    )
                registry.confirm(entry.id, emptyMap())
                val outcome = registry.runForTest(entry.id, "")
                android.util.Log.d("RealFetch", "outcome=" + outcome)
                assertTrue("the run succeeds: " + outcome, outcome is ScriptRunOutcome.Success)
                assertEquals(listOf("你好，来自回环"), (outcome as ScriptRunOutcome.Success).outputs)
                registry.delete(entry.id)
            } finally {
                server.close()
                worker.join(2000)
            }
        }

    @Test
    fun undeclaredHostIsRefused() =
        runBlocking {
            // The declaration names localhost but the request goes to 127.0.0.1: the policy compares
            // the declared host string, so the request is denied before the socket is read.
            val server = loopback()
            val worker =
                Thread {
                    runCatching { server.answer { "200 OK" to "never read" } }
                }
            worker.start()
            try {
                val entry =
                    registry.add(
                        fetchScript("localhost", "/hi", urlHost = "127.0.0.1:" + server.port),
                        ScriptSourceOrigin.Pasted,
                    )
                registry.confirm(entry.id, emptyMap())
                val outcome = registry.runForTest(entry.id, "")
                assertTrue(outcome is ScriptRunOutcome.Failed)
                assertEquals("addressNotAllowed", (outcome as ScriptRunOutcome.Failed).code)
                registry.delete(entry.id)
            } finally {
                server.close()
                worker.join(2000)
            }
        }
}
