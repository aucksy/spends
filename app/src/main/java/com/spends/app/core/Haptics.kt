package com.spends.app.core

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
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

    /**
     * A firmer, clearly-felt "click" — used for the calculator keypad keys (#4, the old KEYBOARD_TAP was
     * too soft, GPay-like crispness wanted) and the home-screen widget buttons (#2, the faint TICK on the
     * eye read as no feedback at all). Stronger than [tick]; still wrapped so it can never crash a caller.
     */
    fun click(context: Context) {
        runCatching {
            val vibrator = vibrator(context) ?: return
            if (!vibrator.hasVibrator()) return
            // A short, firm one-shot at full amplitude. We deliberately do NOT use
            // createPredefined(EFFECT_CLICK): some devices don't implement that primitive and it SILENTLY
            // does nothing — that's why the keypad + widget buttons lost their haptic. createOneShot is the
            // most universally-supported effect, so it actually fires; amplitude 255 gives the stronger,
            // GPay-like intensity that was asked for. AudioAttributes tag it as sonification/touch feedback
            // so the system is less likely to suppress it (notably from the widget's background broadcast).
            val effect = VibrationEffect.createOneShot(30L, 255)
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            vibrator.vibrate(effect, attrs)
        }
    }

    /**
     * The same firm 30 ms / full-amplitude buzz as [click], but allowed to fire from a BACKGROUND broadcast —
     * the summary widget's eye tap. Android's vibrator service drops background vibrations unless their
     * usage is on its allowlist (VibrationSettings.BACKGROUND_PROCESS_USAGE_ALLOWLIST: ringtone, alarm,
     * notification, communication request, HARDWARE_FEEDBACK, PHYSICAL_EMULATION). A widget button is a
     * soft hardware button, so HARDWARE_FEEDBACK is the honest tag. Needs Android 13 (the
     * vibrate(effect, VibrationAttributes) call); returns false below that so the caller can fall back.
     */
    fun widgetClick(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        runCatching {
            val vibrator = vibrator(context) ?: return true
            if (!vibrator.hasVibrator()) return true
            vibrator.vibrate(
                VibrationEffect.createOneShot(30L, 255),
                VibrationAttributes.createForUsage(VibrationAttributes.USAGE_HARDWARE_FEEDBACK),
            )
        }
        return true
    }

    private fun vibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
}
