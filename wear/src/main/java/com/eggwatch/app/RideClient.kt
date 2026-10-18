package com.eggwatch.app

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/** Watch side of the ride messages (see RideProtocol). */
object RideClient {

    private const val TAG = "RideClient"

    /** Sends [path] to the paired phone. False when no phone is reachable. */
    suspend fun send(context: Context, path: String): Boolean = try {
        val nodes = Wearable.getNodeClient(context).connectedNodes.await()
        val phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        if (phone == null) {
            false
        } else {
            Wearable.getMessageClient(context).sendMessage(phone.id, path, ByteArray(0)).await()
            true
        }
    } catch (e: Exception) {
        Log.w(TAG, "send $path failed", e)
        false
    }
}
