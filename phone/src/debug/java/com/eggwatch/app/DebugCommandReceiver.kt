package com.eggwatch.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.eggwatch.shared.RideProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Lets adb exercise the phone → watch path while the phone is locked in a pocket. */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val command: suspend () -> Unit = when (intent.action) {
            ACTION_SEND_TEST -> suspend {
                val watches = WatchLink.publishTest(app)
                Log.i(TAG, "test ticket sent; watches=$watches")
            }
            // Starts a ride as if from the watch. There is deliberately no confirm
            // command: creating a ticket charges money, so only the watch can approve it.
            ACTION_RIDE_PREPARE -> suspend { RideRunner.onMessage(app, RideProtocol.PATH_PREPARE, null) }
            ACTION_RIDE_CANCEL -> suspend { RideRunner.onMessage(app, RideProtocol.PATH_CANCEL, null) }
            else -> return
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                command()
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DebugCommand"
        private const val ACTION_SEND_TEST = "com.eggwatch.app.SEND_TEST"
        private const val ACTION_RIDE_PREPARE = "com.eggwatch.app.RIDE_PREPARE"
        private const val ACTION_RIDE_CANCEL = "com.eggwatch.app.RIDE_CANCEL"
    }
}
