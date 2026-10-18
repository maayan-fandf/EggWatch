package com.eggwatch.app

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Gets the phone screen on for a watch-driven ride.
 *
 * It can light the screen of an unlocked phone, but it cannot open a locked
 * one: Pixel Watch Unlock only answers a real touch (tested 2026-10-16 with
 * dismiss requests and lock-screen swipes, in a pocket and on a desk). A locked
 * phone therefore waits for the user to unlock it once.
 */
object PhoneWaker {

    enum class Result { ALREADY_ON, WOKE, LOCKED, NOT_STARTED }

    private const val TAG = "PhoneWaker"

    @Volatile
    private var woke: CompletableDeferred<Unit>? = null

    suspend fun wake(context: Context): Result {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val result = when {
            keyguard.isKeyguardLocked -> Result.LOCKED
            power.isInteractive -> Result.ALREADY_ON
            else -> lightScreen(context)
        }
        Log.i(TAG, "wake: $result")
        RideLog.add("wake") { put("result", result.name) }
        return result
    }

    /** Waits for the user to unlock the phone themselves. */
    suspend fun awaitUnlocked(context: Context, timeoutMs: Long): Boolean {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (power.isInteractive && !keyguard.isKeyguardLocked) return true
            delay(300)
        }
        return false
    }

    /** A short double buzz, so the phone is noticed in a pocket. */
    fun nudge(context: Context) {
        val vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
    }

    private suspend fun lightScreen(context: Context): Result {
        val signal = CompletableDeferred<Unit>()
        woke = signal
        return try {
            context.startActivity(
                Intent(context, UnlockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            if (withTimeoutOrNull(3_000) { signal.await() } != null) Result.WOKE else Result.NOT_STARTED
        } catch (e: Exception) {
            Log.w(TAG, "could not start UnlockActivity", e)
            Result.NOT_STARTED
        } finally {
            woke = null
        }
    }

    internal fun onScreenWoken() {
        woke?.complete(Unit)
    }
}

/** Invisible activity whose only job is to turn the screen on. */
class UnlockActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneWaker.onScreenWoken()
        finish()
    }
}
