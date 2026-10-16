package com.eggwatch.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.eggwatch.shared.Ticket
import com.eggwatch.shared.TicketProtocol
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService

/**
 * Buzzes the wrist when the phone publishes a ticket we haven't announced yet,
 * so one tap on the notification opens the QR.
 */
class TicketListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        Log.i(TAG, "onDataChanged: ${events.count} event(s), appVisible=${MainActivity.isVisible}")
        val ticket = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == TicketProtocol.PATH }
            .mapNotNull { Ticket.readFrom(DataMapItem.fromDataItem(it.dataItem).dataMap) }
            .lastOrNull() ?: return

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getString(KEY_NOTIFIED_HASH, null) == ticket.hash) return
        prefs.edit { putString(KEY_NOTIFIED_HASH, ticket.hash) }

        // The open app already shows it.
        if (MainActivity.isVisible) return
        notifyNewTicket(ticket)
    }

    private fun notifyNewTicket(ticket: Ticket) {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "notifications not allowed; skipping")
            return
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "כרטיס חדש", NotificationManager.IMPORTANCE_HIGH)
        )
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.eggwatch.shared.R.drawable.ic_stat_egg)
            .setContentTitle(if (ticket.isTest) "קוד בדיקה הגיע" else "כרטיס חדש בשעון")
            .setContentText("הקשה לפתיחת הקוד")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "TicketListener"
        private const val PREFS = "eggwatch"
        private const val KEY_NOTIFIED_HASH = "notifiedHash"
        private const val CHANNEL_ID = "ticket"
        private const val NOTIFICATION_ID = 1
    }
}
