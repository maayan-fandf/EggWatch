package com.eggwatch.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
            ACTION_WAKE_TEST -> suspend {
                // Time to put the phone in a pocket before it fires.
                delay(intent.getIntExtra("delaySec", 0) * 1_000L)
                val result = PhoneWaker.wakeAndUnlock(app)
                Log.i(TAG, "wake test: $result")
                if (intent.getBooleanExtra("relock", true)) {
                    delay(3_000)
                    val locked = TicketWatcherService.instance?.lockScreen()
                    Log.i(TAG, "relocked=$locked")
                }
            }
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
        private const val ACTION_WAKE_TEST = "com.eggwatch.app.WAKE_TEST"
    }
}
