package com.spends.app.core

import android.content.Intent

/** Pure launch-intent rules for [MainActivity], kept out of the Activity so a plain JVM test can call them. */
object LaunchIntents {

    /**
     * Whether onCreate's intent is a NEW request rather than the task's original intent handed back.
     *
     * Android keeps the intent that first opened the task. After the process dies, a recreate (saved state
     * present) or a relaunch from Recents (LAUNCHED_FROM_HISTORY) delivers that original intent again — for
     * an app first opened from a capture notification, an alert from days ago. Only a fresh launch carries a
     * request the user just made.
     */
    fun isFreshLaunch(hasSavedState: Boolean, intentFlags: Int): Boolean =
        !hasSavedState && intentFlags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY == 0
}
