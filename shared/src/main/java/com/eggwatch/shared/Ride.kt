package com.eggwatch.shared

import org.json.JSONObject

/**
 * Watch ↔ phone messages for starting a light-rail ride from the watch.
 *
 * The watch sends [PATH_PREPARE]; the phone opens Egged up to the station
 * panel and answers with a [RideStatus.State.CONFIRM] carrying station, price
 * and passengers. Only after the watch sends [PATH_CONFIRM] does the phone
 * press "create ticket" — creating a ticket charges the user.
 */
object RideProtocol {
    const val PATH_PREFIX = "/ride"
    const val PATH_PREPARE = "/ride/prepare"
    const val PATH_CONFIRM = "/ride/confirm"
    const val PATH_CANCEL = "/ride/cancel"
    const val PATH_STATUS = "/ride/status"
}

/** Progress the phone reports back to the watch. */
data class RideStatus(
    val state: State,
    val message: String = "",
    val station: String = "",
    val price: String = "",
    val passengers: Int = 1,
) {
    enum class State { WORKING, NEEDS_UNLOCK, CONFIRM, NOT_AT_STATION, TICKET_READY, CANCELLED, ERROR }

    fun toBytes(): ByteArray = JSONObject()
        .put("state", state.name)
        .put("message", message)
        .put("station", station)
        .put("price", price)
        .put("passengers", passengers)
        .toString()
        .toByteArray()

    companion object {
        fun fromBytes(bytes: ByteArray): RideStatus? = runCatching {
            val o = JSONObject(String(bytes))
            RideStatus(
                state = State.valueOf(o.getString("state")),
                message = o.optString("message"),
                station = o.optString("station"),
                price = o.optString("price"),
                passengers = o.optInt("passengers", 1),
            )
        }.getOrNull()
    }
}
