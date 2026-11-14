package app.songyan.ime.script

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.songyan.ime.ime.EditorConnection
import app.songyan.ime.ime.EditorToolGateway
import app.songyan.ime.ime.tools.ToolExecutor
import app.songyan.ime.ime.tools.ToolUndoResult
import app.songyan.ime.ime.tools.ToolWriteResult
import app.songyan.ime.tools.ToolCapability
import app.songyan.ime.tools.ToolDescriptor
import app.songyan.ime.tools.ToolLimits
import app.songyan.ime.tools.ToolPhase
import app.songyan.ime.tools.ToolSession
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The minimal script example end to end: the real bundled QuickJS runs examples/minimal.js in the
 * isolated process, its output becomes a real [ToolExecutor] result, a [ToolSession] previews it,
 * and the keyboard's [EditorToolGateway] writes it into a real [EditText] and undoes it again. No
 * fake executor and no stub engine; only the editor is a plain platform editor, as in production.
 */
@RunWith(AndroidJUnit4::class)
class ScriptMinimalExampleWriteBackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = instrumentation.context
    private val limits =
        ToolLimits(
            maxInputUnits = 20000,
            timeoutMillis = 30_000,
            undoWindowMillis = null,
            maxResults = 3,
        )
    private val tool = ToolDescriptor("minimal", "加书名号", setOf(ToolCapability.TEXT))

    private class Field(
        val edit: EditText,
        val info: EditorInfo,
        val connection: InputConnection,
    ) {
        val text: String get() = edit.text.toString()
    }

    private lateinit var activeConnection: InputConnection
    private val editor = EditorConnection { activeConnection }

    private fun field(text: String): Field {
        lateinit var result: Field
        instrumentation.runOnMainSync {
            val edit = EditText(context)
            edit.setInputType(InputType.TYPE_CLASS_TEXT)
            edit.setText(text)
            edit.setSelection(text.length)
            val info = EditorInfo()
            info.inputType = InputType.TYPE_CLASS_TEXT
            info.packageName = context.packageName
            info.fieldId = 7
            val connection = requireNotNull(edit.onCreateInputConnection(info))
            info.initialSelStart = edit.selectionStart
            info.initialSelEnd = edit.selectionEnd
            result = Field(edit, info, connection)
        }
        return result
    }

    private fun activate(target: Field) {
        activeConnection = target.connection
        editor.startInput(target.info, false)
    }

    private fun typeUser(
        target: Field,
        text: String,
    ) {
        instrumentation.runOnMainSync {
            val before = target.edit.text.length
            editor.update("", listOf(text))
            editor.onUpdateSelection(before, before, before + text.length, before + text.length, -1, -1)
        }
    }

    /** The executor under test: the real script engine in its isolated process, not a lambda stand-in. */
    private class ScriptExecutor(
        private val context: Context,
        private val instrumentationAssets: android.content.res.AssetManager,
    ) : ToolExecutor {
        override suspend fun run(
            tool: ToolDescriptor,
            input: String,
        ): List<String> {
            val script = instrumentationAssets.open("script/examples/minimal.js").use { it.readBytes() }
            val files = mutableListOf<ParcelFileDescriptor>()
            try {
                val scriptFd = memfdOf(script, "minimal.js")
                files.add(scriptFd)
                val textFd = memfdOf(input.toByteArray(StandardCharsets.UTF_8), "input.txt")
                files.add(textFd)
                val arguments =
                    listOf(
                        "run",
                        "--script",
                        "@fd0",
                        "--text-file",
                        "@fd1",
                        "--capabilities",
                        "text",
                        "--source-bytes",
                        ScriptCaseRun.DEFAULT_SOURCE_BYTES.toString(),
                        "--heap-bytes",
                        ScriptCaseRun.DEFAULT_HEAP_BYTES.toString(),
                        "--duration-ms",
                        ScriptCaseRun.DEFAULT_DURATION_MS.toString(),
                        "--compute-ms",
                        ScriptCaseRun.DEFAULT_COMPUTE_MS.toString(),
                        "--request-count",
                        ScriptCaseRun.DEFAULT_REQUEST_COUNT.toString(),
                        "--response-bytes",
                        ScriptCaseRun.DEFAULT_RESPONSE_BYTES.toString(),
                        "--input-text-bytes",
                        ScriptCaseRun.DEFAULT_INPUT_TEXT_BYTES.toString(),
                    )
                val outcome = runInIsolatedProcess(context, arguments, files)
                check(outcome.exitCode == 0) { "script exit " + outcome.exitCode + ": " + outcome.stderr }
                val json = JSONObject(outcome.stdout)
                check(json.optBoolean("ok", false)) { "script refused: " + outcome.stdout }
                val outputs = json.getJSONArray("outputs")
                return buildList { for (index in 0 until outputs.length()) add(outputs.getString(index)) }
            } finally {
                files.forEach { it.close() }
            }
        }
    }

    private fun newGateway(): Pair<EditorConnection, EditorToolGateway> {
        val gateway = EditorToolGateway(editor, limits, ToolSession("script-minimal-" + System.nanoTime()))
        return editor to gateway
    }

    @Test
    fun theMinimalExampleRunsOnTheRealEngineAndWritesBackAndUndoes() {
        val target = field("示例文字")
        activate(target)
        val original = target.text
        val (_, gateway) = newGateway()
        val run =
            gateway.beginRun(
                tool,
                app.songyan.ime.tools
                    .CapabilityTable(setOf(ToolCapability.TEXT)),
                target.info,
            )
        assertEquals(ToolPhase.RUNNING, run.phase)
        val executor = ScriptExecutor(context, context.assets)
        val outputs = runBlocking { executor.run(tool, run.source!!.text) }
        assertEquals(listOf("「示例文字」"), outputs)
        assertTrue(run.complete(run.runId, outputs))
        assertEquals(ToolPhase.PREVIEWING, run.phase)
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        assertEquals("「示例文字」", target.text)
        assertEquals(ToolUndoResult.Applied, gateway.undo(run, target.info))
        assertEquals(original, target.text)
    }

    @Test
    fun aChangedSourceIsInsertedInsteadOfOverwritten() {
        val target = field("hello")
        activate(target)
        val (_, gateway) = newGateway()
        val run =
            gateway.beginRun(
                tool,
                app.songyan.ime.tools
                    .CapabilityTable(setOf(ToolCapability.TEXT)),
                target.info,
            )
        val executor = ScriptExecutor(context, context.assets)
        val outputs = runBlocking { executor.run(tool, run.source!!.text) }
        assertTrue(run.complete(run.runId, outputs))
        typeUser(target, "!")
        assertEquals(ToolWriteResult.Applied, gateway.replace(run, target.info))
        assertTrue("the user's text survives: " + target.text, target.text.startsWith("hello!"))
        assertTrue("the script result landed: " + target.text, target.text.contains("「hello」"))
        assertEquals(ToolUndoResult.Applied, gateway.undo(run, target.info))
        assertEquals("hello!", target.text)
    }

    private class Outcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    private companion object {
        fun memfdOf(
            bytes: ByteArray,
            name: String,
        ): ParcelFileDescriptor {
            val fd = Os.memfd_create(name, 0)
            Os.write(fd, bytes, 0, bytes.size)
            Os.lseek(fd, 0, OsConstants.SEEK_SET)
            val pfd = ParcelFileDescriptor.dup(fd)
            Os.close(fd)
            return pfd
        }

        /** Same bind/memfd hand-off as ScriptSharedCasesTest: the isolated process has no storage. */
        fun runInIsolatedProcess(
            context: Context,
            arguments: List<String>,
            files: List<ParcelFileDescriptor>,
        ): Outcome {
            val latch = CountDownLatch(1)
            var outcome = Outcome(-1, "", "")
            val connection =
                object : ServiceConnection {
                    override fun onServiceConnected(
                        name: ComponentName,
                        service: IBinder,
                    ) {
                        val remote = ScriptIsolatedProcess.Stub.asInterface(service)
                        remote.run(
                            arguments,
                            files,
                            object : ScriptIsolatedCallback.Stub() {
                                override fun finished(
                                    exitCode: Int,
                                    stdout: String,
                                    stderr: String,
                                ) {
                                    outcome = Outcome(exitCode, stdout, stderr)
                                    latch.countDown()
                                }
                            },
                        )
                    }

                    override fun onServiceDisconnected(name: ComponentName) = Unit
                }
            val intent = Intent().setComponent(ComponentName(context, "app.songyan.ime.script.ScriptIsolatedService"))
            check(
                context.bindService(intent, connection, Context.BIND_AUTO_CREATE),
            ) { "cannot bind the isolated service" }
            try {
                check(latch.await(120, TimeUnit.SECONDS)) { "the isolated service did not answer" }
            } finally {
                context.unbindService(connection)
            }
            return outcome
        }
    }
}
