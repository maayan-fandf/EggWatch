package com.eggwatch.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Lets adb exercise the phone → watch path while the phone is locked in a pocket. */
class DebugCommandReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SEND_TEST) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val watches = WatchLink.publishTest(context.applicationContext)
                Log.i(TAG, "test ticket sent; watches=$watches")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "DebugCommand"
        private const val ACTION_SEND_TEST = "com.eggwatch.app.SEND_TEST"
    }
}
