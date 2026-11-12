package app.songyan.ime.script

import android.content.Context
import android.os.ParcelFileDescriptor
import android.system.Os
import android.system.OsConstants

/** Header parsing runs in the isolated process through the shared CLI (the same code path macOS
 *  uses), so the proxy never loads the engine itself. */
internal object HeaderParser {
    fun parse(
        context: Context,
        source: ByteArray,
    ): ScriptHeader {
        val fd = Os.memfd_create("header-parse", 0)
        Os.write(fd, source, 0, source.size)
        Os.lseek(fd, 0, OsConstants.SEEK_SET)
        val pfd = ParcelFileDescriptor.dup(fd)
        Os.close(fd)
        val answer =
            IsolatedCall.call(context) { service, callback ->
                service.run(
                    listOf("header", "--script", "@fd0", "--capabilities", "text,audio"),
                    listOf(pfd),
                    callback,
                )
            }
        pfd.close()
        val (exit, stdout) =
            answer ?: return ScriptHeader.Refused(
                "unavailable",
                0,
                "the isolated service did not answer",
                false,
            )
        return ScriptProxyProto.headerFromCliReport(stdout)
    }
}
