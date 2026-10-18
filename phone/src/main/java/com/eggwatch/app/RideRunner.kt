package com.eggwatch.app

import android.content.Context
import android.util.Log
import com.eggwatch.shared.RideProtocol
import com.eggwatch.shared.RideStatus
import com.eggwatch.shared.RideStatus.State
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Woken by Play Services when the watch asks for a ride. */
class RideRequestListener : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path.startsWith(RideProtocol.PATH_PREFIX)) {
            RideRunner.onMessage(this, event.path, event.sourceNodeId)
        }
    }
}

/**
 * Starts a light-rail ride in Egged on behalf of the watch.
 *
 * It walks Egged to the station panel, sends the watch the station, price and
 * passenger count, and presses "create ticket" only after the watch confirms —
 * creating a ticket charges the user. An already-active ticket is just
 * re-sent. The QR itself reaches the watch through the normal mirror
 * ([TicketWatcherService] screenshots the ticket screen and publishes it).
 */
object RideRunner {

    private const val TAG = "RideRunner"
    private const val UNLOCK_WAIT_MS = 60_000L
    private const val OPEN_WAIT_MS = 10_000L
    private const val STATION_WAIT_MS = 15_000L
    private const val CONFIRM_WAIT_MS = 30_000L
    private const val TICKET_WAIT_MS = 20_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var job: Job? = null
    @Volatile private var confirmation: CompletableDeferred<Boolean>? = null
    @Volatile private var lastStatus: RideStatus? = null

    fun onMessage(context: Context, path: String, watchNode: String?) {
        val app = context.applicationContext
        RideLog.init(app)
        when (path) {
            RideProtocol.PATH_PREPARE -> {
                if (job?.isActive == true) {
                    // A second tap while we work: just repeat where we are.
                    lastStatus?.let { scope.launch { WatchLink.sendRideStatus(app, watchNode, it) } }
                    return
                }
                job = scope.launch { run(app, watchNode) }
            }
            RideProtocol.PATH_CONFIRM -> confirmation?.complete(true)
            RideProtocol.PATH_CANCEL -> {
                confirmation?.complete(false)
                job?.cancel()
            }
        }
    }

    private suspend fun run(context: Context, watchNode: String?) {
        val startedAt = System.currentTimeMillis()
        RideLog.add("ride_request")
        suspend fun say(status: RideStatus) {
            lastStatus = status
            Log.i(TAG, "status: $status")
            RideLog.add("ride_status") {
                put("state", status.state.name)
                put("message", status.message)
            }
            WatchLink.sendRideStatus(context, watchNode, status)
        }
        suspend fun fail(message: String) = say(RideStatus(State.ERROR, message))

        val service = TicketWatcherService.instance ?: return fail("שירות השיקוף כבוי בטלפון")
        val egg = EggDriver(service)
        var lockWhenDone = false
        try {
            say(RideStatus(State.WORKING, "מעיר את הטלפון…"))
            when (PhoneWaker.wake(context)) {
                PhoneWaker.Result.ALREADY_ON -> Unit
                PhoneWaker.Result.WOKE -> lockWhenDone = true
                PhoneWaker.Result.LOCKED -> {
                    lockWhenDone = true
                    say(RideStatus(State.NEEDS_UNLOCK, "צריך לפתוח את הטלפון פעם אחת"))
                    PhoneWaker.nudge(context)
                    if (!PhoneWaker.awaitUnlocked(context, UNLOCK_WAIT_MS)) return fail("הטלפון לא נפתח")
                }
                PhoneWaker.Result.NOT_STARTED -> return fail("לא הצלחתי להדליק את מסך הטלפון")
            }

            say(RideStatus(State.WORKING, "פותח את אגד…"))
            if (!egg.open()) return fail("אגד לא מותקנת בטלפון")
            var screen = egg.await(OPEN_WAIT_MS) { it.known() }
            if (screen == null) {
                // Egged opened on some other tab or page: try its payment tab once.
                egg.goHomeTab()
                screen = egg.await(5_000) { it.known() } ?: return fail("מסך לא מוכר באגד")
            }

            if (screen == EggDriver.Screen.Home) {
                say(RideStatus(State.WORKING, "מחפש תחנה…"))
                egg.openLightRailPanel()
                screen = egg.await(STATION_WAIT_MS) { it.takeUnless { s -> s == EggDriver.Screen.Home }?.known() }
                    ?: return fail("אגד לא הציגה תחנה")
            }

            val panel = when (screen) {
                EggDriver.Screen.ActiveTicket -> return deliver(startedAt, { say(it) }, "יש כבר כרטיס פעיל")
                EggDriver.Screen.NotAtStation -> {
                    egg.dismissNotAtStation()
                    return say(RideStatus(State.NOT_AT_STATION, "אגד לא מזהה תחנה בסביבה"))
                }
                is EggDriver.Screen.StationPanel -> screen
                else -> return fail("מסך לא מוכר באגד")
            }

            say(RideStatus(State.CONFIRM, station = panel.station, price = panel.price, passengers = panel.passengers))
            val gate = CompletableDeferred<Boolean>()
            confirmation = gate
            val confirmed = withTimeoutOrNull(CONFIRM_WAIT_MS) { gate.await() } ?: false
            confirmation = null
            if (!confirmed) return say(RideStatus(State.CANCELLED, "בוטל – לא נוצר כרטיס"))

            // Press only if Egged still shows exactly what the user approved.
            if (egg.screen() != panel) return fail("המסך באגד השתנה – לא נוצר כרטיס")
            say(RideStatus(State.WORKING, "יוצר כרטיס…"))
            RideLog.add("ride_create") {
                put("station", panel.station)
                put("price", panel.price)
                put("passengers", panel.passengers)
            }
            if (!egg.createTicket()) return fail("לא הצלחתי ללחוץ על יצירת כרטיס")
            deliver(startedAt, { say(it) }, "הכרטיס נוצר")
        } finally {
            confirmation = null
            if (lockWhenDone) withContext(NonCancellable) {
                delay(1_500)
                service.lockScreen()
            }
        }
    }

    /** Waits for the mirror to publish the ticket Egged is now showing. */
    private suspend fun deliver(startedAt: Long, say: suspend (RideStatus) -> Unit, message: String) {
        say(RideStatus(State.WORKING, "שולח לשעון…"))
        val ticket = withTimeoutOrNull(TICKET_WAIT_MS) {
            WatchLink.latest.first { it != null && !it.isTest && it.capturedAt >= startedAt }
        }
        if (ticket != null) say(RideStatus(State.TICKET_READY, message))
        else say(RideStatus(State.ERROR, "$message, אבל לא הגיע לשעון – אפשר להציג מהטלפון"))
    }

    private fun EggDriver.Screen?.known(): EggDriver.Screen? =
        this?.takeUnless { it == EggDriver.Screen.Other }
}
