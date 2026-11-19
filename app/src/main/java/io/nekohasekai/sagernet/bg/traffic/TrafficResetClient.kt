package io.nekohasekai.sagernet.bg

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.aidl.ISagerNetService
import kotlinx.coroutines.*

/** Reset always runs in :bg, including when the UI is not currently bound. */
object TrafficResetClient {
    suspend fun clear(context: Context, ids: List<Long>) {
        if (ids.isEmpty()) return
        val ready = CompletableDeferred<ISagerNetService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                ready.complete(ISagerNetService.Stub.asInterface(binder))
            }
            override fun onServiceDisconnected(name: ComponentName) {
                ready.completeExceptionally(IllegalStateException("Traffic service disconnected"))
            }
        }
        var bound = false
        try {
            withContext(Dispatchers.Main.immediate) {
                bound = context.bindService(
                    Intent(context, SagerConnection.serviceClass).setAction(Action.SERVICE),
                    connection, Context.BIND_AUTO_CREATE,
                )
                check(bound) { "Traffic service unavailable" }
            }
            val service = withTimeout(5000) { ready.await() }
            // A synchronous IPC acknowledges the transaction before updating the UI.
            withContext(Dispatchers.IO) {
                check(service.clearTraffic(ids.distinct().toLongArray())) { "Traffic reset failed" }
            }
        } finally {
            withContext(NonCancellable + Dispatchers.Main.immediate) {
                if (bound) context.unbindService(connection)
            }
        }
    }
}
