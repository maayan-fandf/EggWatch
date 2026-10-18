package com.eggwatch.app

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay

/**
 * Reads and presses Egged's screens through the accessibility service.
 *
 * View ids come from real runs' logs (2026-10-18, Carlebach station and the office):
 *   home:       other_scan_layout ("רכבת קלה / כרמלית / רכבלית") toggles an inline panel
 *   panel:      tv_other_boarding_station_name, sum_passengers_other,
 *               create_ticket_wrapper_other ("צרו לי כרטיס נסיעה");
 *               other_scan_progress while locating;
 *               at_the_station ("אני בתחנה, בואו ננסה שוב") when no station was found
 *   no station: train_not_in_station_title, train_not_in_station_positive_button_wrapper
 *   ticket:     boarding_pass_train_card ("כרטיס נסיעה פעיל")
 */
class EggDriver(private val service: TicketWatcherService) {

    sealed interface Screen {
        data object Home : Screen
        /** The light-rail panel is open and Egged is still looking for a station. */
        data object Locating : Screen
        /** The panel is open but Egged found no station (it offers "I'm at the station, try again"). */
        data object NoStationFound : Screen
        data class StationPanel(val station: String, val price: String, val passengers: Int) : Screen
        data object NotAtStation : Screen
        data object ActiveTicket : Screen
        data object Other : Screen
    }

    fun open(): Boolean {
        val launch = service.packageManager.getLaunchIntentForPackage(TicketWatcherService.EGG_PACKAGE)
            ?: return false
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        service.startActivity(launch)
        return true
    }

    /** What Egged is showing right now, or null if it isn't in front. */
    fun screen(): Screen? {
        val root = service.eggRoot() ?: return null
        return when {
            root.has(ACTIVE_TICKET) -> Screen.ActiveTicket
            root.has(NOT_IN_STATION) -> Screen.NotAtStation
            root.has(STATION_NAME) -> {
                val station = root.text(STATION_NAME)
                when {
                    root.has(PANEL_PROGRESS) -> Screen.Locating
                    station.isNotBlank() && root.has(CREATE_TICKET) -> Screen.StationPanel(
                        station = station,
                        price = root.firstText { it.contains('₪') },
                        passengers = root.text(PASSENGERS).toIntOrNull() ?: 1,
                    )
                    root.has(RETRY_STATION) -> Screen.NoStationFound
                    else -> Screen.Locating
                }
            }
            root.has(LIGHT_RAIL) -> Screen.Home
            else -> Screen.Other
        }
    }

    /** Polls until [accept] returns non-null or [timeoutMs] passes. */
    suspend fun <T> await(timeoutMs: Long, accept: (Screen?) -> T?): T? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            accept(screen())?.let { return it }
            delay(250)
        }
        return null
    }

    suspend fun click(id: String): Boolean {
        val node = service.eggRoot()?.find(id) ?: return false
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
        }
        // Some Egged views only react to a real touch.
        val bounds = Rect().also(node::getBoundsInScreen)
        return service.tap(bounds.exactCenterX(), bounds.exactCenterY())
    }

    suspend fun openLightRailPanel() = click(LIGHT_RAIL)
    suspend fun createTicket() = click(CREATE_TICKET)
    suspend fun dismissNotAtStation() = click(NOT_IN_STATION_OK)
    suspend fun retryStation() = click(RETRY_STATION)
    suspend fun goHomeTab() = click(PAYMENT_TAB)

    private fun AccessibilityNodeInfo.find(id: String): AccessibilityNodeInfo? =
        findAccessibilityNodeInfosByViewId("${TicketWatcherService.EGG_PACKAGE}:id/$id").firstOrNull()

    private fun AccessibilityNodeInfo.has(id: String) = find(id) != null

    private fun AccessibilityNodeInfo.text(id: String) = find(id)?.text?.toString()?.trim().orEmpty()

    private fun AccessibilityNodeInfo.firstText(match: (String) -> Boolean): String {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(this@firstText) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < 600) {
            val node = queue.removeFirst()
            val text = node.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty() && match(text)) return text
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
        return ""
    }

    private companion object {
        const val LIGHT_RAIL = "other_scan_layout"
        const val STATION_NAME = "tv_other_boarding_station_name"
        const val PASSENGERS = "sum_passengers_other"
        const val CREATE_TICKET = "create_ticket_wrapper_other"
        const val PANEL_PROGRESS = "other_scan_progress"
        const val RETRY_STATION = "at_the_station"
        const val NOT_IN_STATION = "train_not_in_station_title"
        const val NOT_IN_STATION_OK = "train_not_in_station_positive_button_wrapper"
        const val ACTIVE_TICKET = "boarding_pass_train_card"
        const val PAYMENT_TAB = "rp_bottom_bar_item_israpass"
    }
}
