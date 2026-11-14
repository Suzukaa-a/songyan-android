package app.songyan.ime.scripttools

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.script.ScriptHostCapability
import app.songyan.ime.script.ScriptProxyRegistry
import app.songyan.ime.script.ScriptRunOutcome
import app.songyan.ime.script.ScriptSourceOrigin
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Script runs the way the keyboard starts them: through [ScriptToolExecutor] in a coroutine the
 * panel owns, against the real proxy and isolated processes and a real socket. The settings screen
 * reaches the registry from a live coroutine; the keyboard cancels its coroutine instead, which is
 * the path these cover.
 */
@RunWith(AndroidJUnit4::class)
class ScriptKeyboardRunDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val registry = ScriptProxyRegistry(context, ScriptHostCapability.DEFAULT)
    private val created = mutableListOf<String>()
    private val server = LocalServer()

    @After
    fun tearDown() {
        server.close()
        runBlocking { created.forEach { runCatching { registry.delete(it) } } }
    }

    /** The user's own service: the script names no host, the address is typed into its settings. */
    private fun script(
        path: String,
        timeout: Int? = null,
        body: String = "text",
    ): ByteArray {
        val quote = '"'
        return listOfNotNull(
            "// @api 1",
            "// @name 自建服务",
            "// @setting endpoint url 服务地址",
            timeout?.let { "// @timeout $it" },
            "export default async ({ text }) => {",
            "  const r = await fetch(settings.endpoint + $quote$path$quote, { method: ${quote}POST$quote, body: $body });",
            "  return await r.text();",
            "};",
        ).joinToString("\n")
            .replace("async ({ text })", "async ({ text, settings })")
            .toByteArray(Charsets.UTF_8)
    }

    private suspend fun install(source: ByteArray): String {
        val entry = registry.add(source, ScriptSourceOrigin.Pasted)
        created += entry.id
        registry.confirm(entry.id, mapOf("endpoint" to "http://127.0.0.1:" + server.port))
        return entry.id
    }

    private fun tool(id: String) = ToolDescriptor("script:$id", "自建服务", setOf(ToolCapability.TEXT))

    @Test
    fun cancellingTheKeyboardRunStopsItAndTheNextRunStartsAtOnce() =
        runBlocking {
            server.answerAfter(60_000, "too late")
            val id = install(script("/hang"))
            val executor = ScriptToolExecutor { registry }
            val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
            val task = scope.launch { executor.run(tool(id), "要处理的文字") }
            assertTrue("the request reached the server", server.requestSeen.await(15, TimeUnit.SECONDS))

            // What the panel does on Cancel, on closing, on a field change and on its timeout.
            task.cancel()
            // The run is stopped, not left talking to the server: the connection drops.
            assertTrue("the request was abandoned", server.disconnected.await(10, TimeUnit.SECONDS))

            // The process survived the cancel, and the next run is not queued behind the old one.
            server.answerAfter(0, "第二次")
            val started = System.currentTimeMillis()
            val outputs = withTimeout(20_000) { executor.run(tool(id), "再来一次") }
            assertEquals(listOf("第二次"), outputs)
            assertTrue(
                "the next run waited " + (System.currentTimeMillis() - started) + " ms",
                System.currentTimeMillis() - started < 15_000,
            )
        }

    /** A declared @timeout past every fixed wait in the pipeline still gets its answer. */
    @Test
    fun aDeclaredLongTimeoutOutlastsTheFixedWaits() =
        runBlocking {
            server.answerAfter(65_000, "慢模型的回复")
            val id = install(script("/slow", timeout = 90))
            val outcome = registry.run(id, "文字")
            assertTrue("the run succeeds: $outcome", outcome is ScriptRunOutcome.Success)
            assertEquals(listOf("慢模型的回复"), (outcome as ScriptRunOutcome.Success).outputs)
        }

    /** A minute of 16 kHz mono 16-bit audio is 1.92 MB; a body that size reaches the server whole. */
    @Test
    fun aRecordingSizedBodyIsUploadedWhole() =
        runBlocking {
            server.answerWithBodySize()
            val bytes = 1_920_044
            val id = install(script("/upload", body = "new Uint8Array($bytes)"))
            val outcome = registry.run(id, "文字")
            assertTrue("the run succeeds: $outcome", outcome is ScriptRunOutcome.Success)
            assertEquals(listOf(bytes.toString()), (outcome as ScriptRunOutcome.Success).outputs)
        }

    /** Serves one request at a time on loopback: reads it whole, then answers after a delay. */
    private class LocalServer {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        val requestSeen = CountDownLatch(1)
        val disconnected = CountDownLatch(1)
        private val lastBodySize = AtomicLong(-1)
        private var thread: Thread? = null

        fun answerAfter(
            delayMillis: Long,
            reply: String,
        ) = serve { client, _ ->
            val deadline = System.currentTimeMillis() + delayMillis
            // Watch the connection while waiting: a client that goes away is a cancelled run.
            client.soTimeout = 200
            while (System.currentTimeMillis() < deadline) {
                val gone = runCatching { client.getInputStream().read() == -1 }.getOrDefault(false)
                if (gone) {
                    disconnected.countDown()
                    return@serve null
                }
            }
            reply
        }

        fun answerWithBodySize() = serve { _, size -> size.toString() }

        private fun serve(answer: (Socket, Long) -> String?) {
            thread?.interrupt()
            thread =
                Thread {
                    runCatching {
                        socket.accept().use { client ->
                            val input = DataInputStream(client.getInputStream())
                            var length = 0L
                            while (true) {
                                val line = readLine(input)
                                if (line.isEmpty()) break
                                if (line.startsWith("content-length:", ignoreCase = true)) {
                                    length = line.substringAfter(':').trim().toLong()
                                }
                            }
                            input.skipNBytesCompat(length)
                            lastBodySize.set(length)
                            requestSeen.countDown()
                            val reply = answer(client, length) ?: return@use
                            val bytes = reply.toByteArray(Charsets.UTF_8)
                            client.getOutputStream().apply {
                                write(
                                    (
                                        "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                                            "Content-Length: " +
                                            bytes.size + "\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(),
                                )
                                write(bytes)
                                flush()
                            }
                        }
                    }
                }.also { it.start() }
        }

        private fun readLine(input: DataInputStream): String {
            val line = StringBuilder()
            while (true) {
                val c = input.read()
                if (c < 0 || c == '\n'.code) break
                if (c != '\r'.code) line.append(c.toChar())
            }
            return line.toString()
        }

        private fun DataInputStream.skipNBytesCompat(count: Long) {
            var left = count
            val buffer = ByteArray(64 * 1024)
            while (left > 0) {
                val read = read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                if (read < 0) break
                left -= read
            }
        }

        fun close() {
            runCatching { socket.close() }
            thread?.interrupt()
        }
    }
}
