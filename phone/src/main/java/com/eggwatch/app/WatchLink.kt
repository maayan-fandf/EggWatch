package com.eggwatch.app

import android.content.Context
import android.util.Log
import com.eggwatch.shared.RideProtocol
import com.eggwatch.shared.RideStatus
import com.eggwatch.shared.Ticket
import com.eggwatch.shared.TicketProtocol
import com.eggwatch.shared.latestTicket
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Phone side of the Wearable data layer. */
object WatchLink {

    private const val TAG = "WatchLink"

    private val _latest = MutableStateFlow<Ticket?>(null)

    /** The last ticket handed to the watch, for the phone UI. */
    val latest: StateFlow<Ticket?> = _latest

    /**
     * Makes [ticket] the watch's current ticket. The data layer stores it and
     * syncs whenever the watch is reachable, so this succeeds offline too.
     * Returns how many watches are connected right now, or -1 on failure.
     */
    suspend fun publish(context: Context, ticket: Ticket): Int = try {
        val request = PutDataMapRequest.create(TicketProtocol.PATH)
            .apply { ticket.writeTo(dataMap) }
            .asPutDataRequest()
            .setUrgent()
        Log.i(TAG, "publishing ${ticket.hash} (${ticket.modules.size}x${ticket.modules.size})")
        val item = Wearable.getDataClient(context).putDataItem(request).await()
        _latest.value = ticket
        val watches = Wearable.getNodeClient(context).connectedNodes.await().size
        Log.i(TAG, "published ${item.uri}; $watches watch(es) connected")
        watches
    } catch (e: Exception) {
        Log.w(TAG, "publish failed", e)
        -1
    }

    /** Sends a generated code, to check the phone → watch path without a real ticket. */
    suspend fun publishTest(context: Context): Int {
        val now = System.currentTimeMillis()
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(Date(now))
        val ticket = Ticket(
            modules = QrScanner.encodeTest("EggWatch test $stamp"),
            capturedAt = now,
            hash = "test-$now",
            isTest = true,
        )
        return publish(context, ticket)
    }

    /** Progress for a ride started from the watch; to every connected watch when [nodeId] is null. */
    suspend fun sendRideStatus(context: Context, nodeId: String?, status: RideStatus) {
        try {
            val client = Wearable.getMessageClient(context)
            val targets = nodeId?.let(::listOf)
                ?: Wearable.getNodeClient(context).connectedNodes.await().map { it.id }
            for (target in targets) {
                client.sendMessage(target, RideProtocol.PATH_STATUS, status.toBytes()).await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ride status not delivered", e)
        }
    }

    suspend fun connectedWatches(context: Context): List<String> = try {
        Wearable.getNodeClient(context).connectedNodes.await().map { it.displayName }
    } catch (e: Exception) {
        Log.w(TAG, "node query failed", e)
        emptyList()
    }

    suspend fun refreshLatest(context: Context) {
        try {
            Wearable.getDataClient(context).latestTicket()?.let { _latest.value = it }
        } catch (e: Exception) {
            Log.w(TAG, "load failed", e)
        }
    }
}
