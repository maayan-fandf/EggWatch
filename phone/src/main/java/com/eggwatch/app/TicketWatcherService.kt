package com.eggwatch.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.eggwatch.shared.Ticket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONArray
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/**
 * Mirror. While the Egged app is on screen, it screenshots every second or
 * so, and whenever a QR code shows up it copies it to the watch.
 *
 * On its own it never taps, scrolls or types — the previous version
 * auto-tapped buttons in Egged and got in the way of scanning on the bus.
 * Taps ([tap]) and clicks run only for a ride the user starts from the watch.
 * Everything it sees goes to [RideLog] so one real ride tells us how the
 * ticket behaves.
 */
class TicketWatcherService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val shotExecutor = Executors.newSingleThreadExecutor()
    private var watchJob: Job? = null

    override fun onServiceConnected() {
        RideLog.init(applicationContext)
        RideLog.add("service_on")
        instance = this
    }

    /**
     * Egged's top window, if it is on screen. Looks through all windows rather
     * than the "active" one, which can be a picture-in-picture video or the
     * notification shade while Egged is in front.
     */
    fun eggRoot(): AccessibilityNodeInfo? =
        windows.firstNotNullOfOrNull { window ->
            window.takeIf { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.root
                ?.takeIf { it.packageName == EGG_PACKAGE }
        }

    /** Puts the phone back to sleep after a watch-driven run. */
    fun lockScreen(): Boolean = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)

    /** A plain tap, for Egged views that don't answer ACTION_CLICK. */
    suspend fun tap(x: Float, y: Float): Boolean {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        return suspendCancellableCoroutine { cont ->
            val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) = cont.resume(true)
                override fun onCancelled(gestureDescription: GestureDescription) = cont.resume(false)
            }, null)
            if (!dispatched) cont.resume(false)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.packageName != EGG_PACKAGE) return
        if (watchJob?.isActive == true) return
        watchJob = scope.launch { watchEgg() }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) instance = null
        scope.cancel()
        shotExecutor.shutdown()
        super.onDestroy()
    }

    /** Runs for as long as Egged stays in front with the screen on. */
    private suspend fun watchEgg() {
        RideLog.add("egg_open")
        val session = Session()
        val startedAt = SystemClock.elapsedRealtime()
        try {
            while (currentCoroutineContext().isActive) {
                val root = eggRoot()
                if (root == null || !isScreenOn()) break
                session.noteScreen(root)
                session.scan()
                val elapsed = SystemClock.elapsedRealtime() - startedAt
                delay(if (elapsed < FAST_PHASE_MS) FAST_SCAN_MS else SLOW_SCAN_MS)
            }
        } finally {
            session.close()
            RideLog.add("egg_closed")
        }
    }

    /** State for one stretch of Egged being visible. */
    private inner class Session {
        private var screenIds: String? = null
        private var blackFrameLogged = false
        private var lastShotError: Int? = null
        private var shown: Shown? = null

        fun noteScreen(root: AccessibilityNodeInfo) {
            val ids = sortedSetOf<String>()
            val texts = mutableListOf<String>()
            walk(root) { node ->
                node.viewIdResourceName?.substringAfter(":id/")?.let(ids::add)
                val text = node.text?.toString()?.trim().orEmpty()
                if (text.isNotEmpty() && texts.size < MAX_TEXTS) texts += text.take(MAX_TEXT_LEN)
            }
            val signature = ids.joinToString(",")
            if (signature == screenIds) return
            screenIds = signature
            blackFrameLogged = false
            RideLog.add("screen") {
                put("ids", JSONArray(ids.toList()))
                put("texts", JSONArray(texts))
            }
        }

        suspend fun scan() {
            val bitmap = when (val shot = screenshot()) {
                is Shot.Ok -> shot.bitmap
                is Shot.Failed -> {
                    if (shot.code != lastShotError) RideLog.add("shot_error") { put("code", shot.code) }
                    lastShotError = shot.code
                    return
                }
            }
            lastShotError = null
            val hit = try {
                if (!blackFrameLogged && bitmap.isMostlyBlack()) {
                    // A secure (FLAG_SECURE) window comes back black.
                    RideLog.add("black_frame") { put("screen", screenIds) }
                    blackFrameLogged = true
                }
                QrScanner.scan(bitmap)
            } finally {
                bitmap.recycle()
            }
            onScanned(hit)
        }

        private suspend fun onScanned(hit: QrScanner.Hit?) {
            val now = System.currentTimeMillis()
            val current = shown
            if (hit == null) {
                // Tolerate one missed frame (animations, scrolling) before calling it gone.
                if (current != null && ++current.misses >= 2) end(current, now)
                return
            }
            if (current != null && current.hash == hit.hash) {
                current.scans++
                current.misses = 0
                current.lastSeen = now
                return
            }
            current?.let { end(it, now) }
            shown = Shown(hit.hash, firstSeen = now, lastSeen = now)
            RideLog.add("qr") {
                put("hash", hit.hash)
                put("replaces", current?.hash)
                put("size", hit.modules.size)
                put("errorsCorrected", hit.errorsCorrected)
                put("screen", screenIds)
                put("text", hit.text)
            }
            val watches = WatchLink.publish(
                applicationContext,
                Ticket(hit.modules, capturedAt = now, hash = hit.hash, isTest = false),
            )
            RideLog.add("sent") {
                put("hash", hit.hash)
                put("watches", watches)
            }
        }

        private fun end(gone: Shown, now: Long) {
            RideLog.add("qr_gone") {
                put("hash", gone.hash)
                put("shownMs", gone.lastSeen - gone.firstSeen)
                put("scans", gone.scans)
                put("goneAfterMs", now - gone.firstSeen)
            }
            shown = null
        }

        fun close() {
            shown?.let { end(it, System.currentTimeMillis()) }
        }
    }

    private class Shown(val hash: String, val firstSeen: Long, var lastSeen: Long) {
        var scans = 1
        var misses = 0
    }

    private sealed interface Shot {
        class Ok(val bitmap: Bitmap) : Shot
        class Failed(val code: Int) : Shot
    }

    private suspend fun screenshot(): Shot = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, shotExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val software = try {
                    val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                    hardware?.copy(Bitmap.Config.ARGB_8888, false).also { hardware?.recycle() }
                } finally {
                    buffer.close()
                }
                cont.resume(if (software != null) Shot.Ok(software) else Shot.Failed(-1))
            }

            override fun onFailure(errorCode: Int) {
                cont.resume(Shot.Failed(errorCode))
            }
        })
    }

    private fun isScreenOn(): Boolean = getSystemService(PowerManager::class.java).isInteractive

    private fun walk(root: AccessibilityNodeInfo, visit: (AccessibilityNodeInfo) -> Unit) {
        val queue = ArrayDeque<AccessibilityNodeInfo>().apply { add(root) }
        var visited = 0
        while (queue.isNotEmpty() && visited++ < MAX_NODES) {
            val node = queue.removeFirst()
            visit(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let(queue::addLast)
        }
    }

    private fun Bitmap.isMostlyBlack(): Boolean {
        var sum = 0L
        var count = 0
        for (y in 0 until height step maxOf(1, height / 40)) {
            for (x in 0 until width step maxOf(1, width / 20)) {
                val p = getPixel(x, y)
                sum += ((p shr 16) and 0xff) + ((p shr 8) and 0xff) + (p and 0xff)
                count++
            }
        }
        return sum / (count * 3) < 6
    }

    companion object {
        const val EGG_PACKAGE = "com.egged.egg"

        /** The running service, while the user has it enabled. */
        @Volatile
        var instance: TicketWatcherService? = null
            private set

        // Scan quickly right after Egged opens, then back off to save battery
        // while it just sits on screen. Still fine-grained enough to time a rotating code.
        private const val FAST_PHASE_MS = 60_000L
        private const val FAST_SCAN_MS = 1_000L
        private const val SLOW_SCAN_MS = 3_000L

        private const val MAX_NODES = 600
        private const val MAX_TEXTS = 15
        private const val MAX_TEXT_LEN = 40
    }
}
