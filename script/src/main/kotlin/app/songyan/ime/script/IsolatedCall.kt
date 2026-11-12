package app.songyan.ime.script

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** One Binder round-trip into the isolated process: binds on demand, runs [block] with the stub
 *  and the parking callback, then unbinds so the isolated process can exit when idle. */
internal object IsolatedCall {
    fun call(
        context: Context,
        action: (ScriptIsolatedProcess, ScriptIsolatedCallback) -> Unit,
    ): Triple<Int, String, String>? {
        var bound: IBinder? = null
        val ready = CountDownLatch(1)
        val connection =
            object : ServiceConnection {
                override fun onServiceConnected(
                    name: ComponentName,
                    service: IBinder,
                ) {
                    bound = service
                    ready.countDown()
                }

                override fun onServiceDisconnected(name: ComponentName) = Unit
            }
        val intent =
            Intent().setComponent(
                ComponentName(context, "app.songyan.ime.script.ScriptIsolatedService"),
            )
        check(context.bindService(intent, connection, Context.BIND_AUTO_CREATE)) {
            "cannot bind the isolated service"
        }
        return try {
            check(ready.await(30, TimeUnit.SECONDS)) { "the isolated service did not connect" }
            val callback = CallbackLatch()
            action(ScriptIsolatedProcess.Stub.asInterface(bound), callback)
            if (callback.await()) Triple(callback.exitCode, callback.stdout, callback.stderr) else null
        } finally {
            context.unbindService(connection)
        }
    }
}
