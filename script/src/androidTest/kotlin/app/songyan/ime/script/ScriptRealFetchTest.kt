package app.songyan.ime.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
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
 *  Binder to the proxy for the transport. Plain http is allowed only to a local address the user
 *  typed into a url setting, which is how a script reaches the user's own service. */
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

    /** The user's own service: the script names no host, the user types the address. */
    private fun ownServiceScript(path: String): ByteArray {
        val quote = '\u0022'
        return listOf(
            "// @api 1",
            "// @name 自建服务",
            "// @setting endpoint url 服务地址",
            "export default async ({ settings }) => {",
            "  const r = await fetch(settings.endpoint + " + quote + path + quote + ");",
            "  return await r.text();",
            "};",
        ).joinToString("\n").toByteArray(Charsets.UTF_8)
    }

    @Test
    fun aLocalAddressTheUserTypedIsReachedOverHttp() =
        runBlocking {
            val server = loopback()
            val worker =
                Thread {
                    runCatching { server.answer { "200 OK" to "你好，来自回环" } }
                }
            worker.start()
            try {
                val entry = registry.add(ownServiceScript("/hi"), ScriptSourceOrigin.Pasted)
                registry.confirm(entry.id, mapOf("endpoint" to "http://127.0.0.1:" + server.port))
                val outcome = registry.runTrial(entry.id, "")
                assertTrue("the run succeeds: " + outcome, outcome is ScriptRunOutcome.Success)
                assertEquals(listOf("你好，来自回环"), (outcome as ScriptRunOutcome.Success).outputs)
                registry.delete(entry.id)
            } finally {
                server.close()
                worker.join(2000)
            }
        }

    @Test
    fun plainHttpToADeclaredHostIsRefused() =
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
                // An author cannot hard-code cleartext: only an address the user typed may be http.
                val outcome = registry.runTrial(entry.id, "")
                assertTrue(outcome is ScriptRunOutcome.Failed)
                assertEquals("addressNotAllowed", (outcome as ScriptRunOutcome.Failed).code)
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
                val outcome = registry.runTrial(entry.id, "")
                assertTrue(outcome is ScriptRunOutcome.Failed)
                assertEquals("addressNotAllowed", (outcome as ScriptRunOutcome.Failed).code)
                registry.delete(entry.id)
            } finally {
                server.close()
                worker.join(2000)
            }
        }

    /** A slow model: waiting on the server is not the script's computation, so a reply after the
     *  4 s compute limit still arrives within the run's duration. */
    @Test
    fun aReplySlowerThanTheComputeLimitStillArrives() =
        runBlocking {
            val server = loopback()
            val worker =
                Thread {
                    runCatching {
                        server.answer {
                            Thread.sleep(ScriptLimits.COMPUTE_MS + 1_500)
                            "200 OK" to "慢模型的回复"
                        }
                    }
                }
            worker.start()
            try {
                val entry = registry.add(ownServiceScript("/slow"), ScriptSourceOrigin.Pasted)
                registry.confirm(entry.id, mapOf("endpoint" to "http://127.0.0.1:" + server.port))
                val outcome = registry.runTrial(entry.id, "")
                assertTrue("the run succeeds: " + outcome, outcome is ScriptRunOutcome.Success)
                assertEquals(listOf("慢模型的回复"), (outcome as ScriptRunOutcome.Success).outputs)
                registry.delete(entry.id)
            } finally {
                server.close()
                worker.join(8000)
            }
        }

    /** Cancel reaches the run that is waiting on the network: it ends at once, not when the
     *  server finally answers, and the next run is not stuck behind it. */
    @Test
    fun cancelStopsARunWaitingOnTheNetwork() =
        runBlocking {
            val server = loopback()
            val worker =
                Thread {
                    runCatching {
                        server.answer {
                            Thread.sleep(20_000)
                            "200 OK" to "too late"
                        }
                    }
                }
            worker.start()
            try {
                val entry = registry.add(ownServiceScript("/hang"), ScriptSourceOrigin.Pasted)
                registry.confirm(entry.id, mapOf("endpoint" to "http://127.0.0.1:" + server.port))
                val started = System.currentTimeMillis()
                val run = async { registry.runTrial(entry.id, "") }
                delay(1_000)
                registry.cancel(entry.id)
                val outcome = run.await()
                val elapsed = System.currentTimeMillis() - started
                assertEquals(ScriptRunOutcome.Cancelled, outcome)
                assertTrue("cancel took " + elapsed + " ms", elapsed < 6_000)
                registry.delete(entry.id)
            } finally {
                server.close()
                worker.interrupt()
                worker.join(2000)
            }
        }
}
