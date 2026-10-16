package com.eggwatch.shared

import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataRequest
import kotlinx.coroutines.tasks.await

/**
 * Phone → watch contract. The phone keeps a single DataItem at [PATH] holding
 * the latest ticket; the watch reads it on launch and listens for changes.
 */
object TicketProtocol {
    const val PATH = "/ticket"
    const val KEY_SIZE = "size"
    const val KEY_BITS = "bits"
    const val KEY_CAPTURED_AT = "capturedAt"
    const val KEY_HASH = "hash"
    const val KEY_TEST = "test"
}

/**
 * The module grid of a QR code, copied 1:1 from the code the Egged app drew.
 * We ship modules rather than a screenshot or the decoded text: it is tiny,
 * renders crisp at any size, and never re-encodes the payload.
 */
class QrModules(val size: Int, private val dark: BooleanArray) {

    init {
        require(dark.size == size * size) { "expected ${size * size} modules, got ${dark.size}" }
    }

    operator fun get(x: Int, y: Int): Boolean = dark[y * size + x]

    /** Row-major, MSB-first, 1 = dark. */
    fun pack(): ByteArray {
        val out = ByteArray((dark.size + 7) / 8)
        for (i in dark.indices) {
            if (dark[i]) out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
        }
        return out
    }

    /** One pixel per module, no quiet zone — scale it up with nearest-neighbour filtering. */
    fun toBitmap(): Bitmap {
        val pixels = IntArray(size * size) { if (dark[it]) Color.BLACK else Color.WHITE }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    companion object {
        fun unpack(size: Int, packed: ByteArray): QrModules {
            val dark = BooleanArray(size * size) { i ->
                (packed[i / 8].toInt() and (0x80 ushr (i % 8))) != 0
            }
            return QrModules(size, dark)
        }
    }
}

/** A ticket as it travels over the Wearable data layer. */
class Ticket(
    val modules: QrModules,
    val capturedAt: Long,
    val hash: String,
    val isTest: Boolean,
) {
    fun writeTo(map: DataMap) {
        map.putInt(TicketProtocol.KEY_SIZE, modules.size)
        map.putByteArray(TicketProtocol.KEY_BITS, modules.pack())
        map.putLong(TicketProtocol.KEY_CAPTURED_AT, capturedAt)
        map.putString(TicketProtocol.KEY_HASH, hash)
        map.putBoolean(TicketProtocol.KEY_TEST, isTest)
    }

    companion object {
        fun readFrom(map: DataMap): Ticket? {
            val size = map.getInt(TicketProtocol.KEY_SIZE, 0)
            val bits = map.getByteArray(TicketProtocol.KEY_BITS) ?: return null
            if (size <= 0 || bits.size < (size * size + 7) / 8) return null
            return Ticket(
                modules = QrModules.unpack(size, bits),
                capturedAt = map.getLong(TicketProtocol.KEY_CAPTURED_AT),
                hash = map.getString(TicketProtocol.KEY_HASH, ""),
                isTest = map.getBoolean(TicketProtocol.KEY_TEST, false),
            )
        }
    }
}

/** The newest ticket in the data layer, whichever node wrote it. */
suspend fun DataClient.latestTicket(): Ticket? {
    val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).path(TicketProtocol.PATH).build()
    val items = getDataItems(uri).await()
    return try {
        items.mapNotNull { Ticket.readFrom(DataMapItem.fromDataItem(it).dataMap) }
            .maxByOrNull { it.capturedAt }
    } finally {
        items.release()
    }
}
