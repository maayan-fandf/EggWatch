package com.eggwatch.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Wakes the phone and tries to get past the lock screen without a touch.
 *
 * Android only lets a trust agent (Pixel "Watch Unlock") open the phone after a
 * user-style unlock request, so after lighting the screen we swipe up on the
 * lock screen from the accessibility service. Watch Unlock then decides; if it
 * says no, the PIN bouncer is up and we lock the phone again — nothing here
 * can get past a PIN.
 */
object PhoneWaker {

    enum class Result { ALREADY_UNLOCKED, UNLOCKED, NEEDS_USER, NO_SERVICE, NOT_STARTED }

    private const val TAG = "PhoneWaker"
    private const val UNLOCK_WAIT_MS = 5_000L

    @Volatile
    private var woke: CompletableDeferred<Unit>? = null

    suspend fun wakeAndUnlock(context: Context): Result {
        val started = SystemClock.elapsedRealtime()
        val result = attempt(context)
        val ms = SystemClock.elapsedRealtime() - started
        Log.i(TAG, "wakeAndUnlock: $result in ${ms}ms")
        RideLog.add("unlock") {
            put("result", result.name)
            put("ms", ms)
        }
        return result
    }

    private suspend fun attempt(context: Context): Result {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        if (power.isInteractive && !keyguard.isKeyguardLocked) return Result.ALREADY_UNLOCKED
        val service = TicketWatcherService.instance ?: return Result.NO_SERVICE

        val signal = CompletableDeferred<Unit>()
        woke = signal
        try {
            context.startActivity(
                Intent(context, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            withTimeoutOrNull(3_000) { signal.await() } ?: return Result.NOT_STARTED
        } catch (e: Exception) {
            Log.w(TAG, "could not start UnlockActivity", e)
            return Result.NOT_STARTED
        } finally {
            woke = null
        }

        delay(500) // let the lock screen settle before swiping
        if (!keyguard.isKeyguardLocked) return Result.UNLOCKED
        service.swipeUp()

        val deadline = SystemClock.elapsedRealtime() + UNLOCK_WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!keyguard.isKeyguardLocked) return Result.UNLOCKED
            delay(200)
        }
        // Watch Unlock said no: don't leave the PIN pad lit in a pocket.
        service.lockScreen()
        return Result.NEEDS_USER
    }

    internal fun onScreenWoken() {
        woke?.complete(Unit)
    }
}

/** Invisible activity whose only job is to turn the screen on over the lock screen. */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneWaker.onScreenWoken()
        finish()
    }
}
