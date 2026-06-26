package com.spends.app.core

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * Tiny tactile "tick" for confirming taps that happen OUTSIDE Compose — specifically the home-screen
 * widget buttons, whose taps fire a PendingIntent so there's no View to call performHapticFeedback on.
 * Uses the system Vibrator with a short, light effect. Wrapped in runCatching so it can never crash a
 * widget broadcast or activity launch (a missing vibrator / OEM quirk just means no buzz).
 */
object Haptics {

    fun tick(context: Context) {
        runCatching {
            val vibrator = vibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            val effect = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
                    VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
                else ->
                    VibrationEffect.createOneShot(18L, VibrationEffect.DEFAULT_AMPLITUDE)
            }
            vibrator.vibrate(effect)
        }
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
