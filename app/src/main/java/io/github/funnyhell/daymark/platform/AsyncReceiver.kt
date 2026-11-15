package io.github.funnyhell.daymark.platform

import android.content.BroadcastReceiver
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private val receiverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

/**
 * Runs suspend work from a broadcast receiver using [BroadcastReceiver.goAsync]. The system allows roughly
 * ten seconds; the work is bounded below that.
 */
fun BroadcastReceiver.goAsyncWork(block: suspend () -> Unit) {
    // Null when the receiver already went asynchronous (Glance's widget receivers do that themselves).
    val pending: BroadcastReceiver.PendingResult? = goAsync()
    receiverScope.launch {
        try {
            withTimeout(9_000) { block() }
        } catch (e: Exception) {
            Log.e("DaymarkReceiver", "Receiver work failed", e)
        } finally {
            pending?.finish()
        }
    }
}
